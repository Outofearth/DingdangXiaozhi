#!/usr/bin/env bash
#
# cache.sh - 小智播放缓存的查看与清理
#
# 背景（缓存都在 app 私有外部目录，删掉不影响任何可播内容）：
#   files/smb_<时间戳>_<名>   共享盘文件的整包缓存：边下边播下完就转正到这里，
#                             下次点同一首直接播本地、不再走网络（LRU 上限 600MB）
#   files/part_<时间戳>_<名>  **正在**边下边播的临时文件，或下载被打断的残片。
#                             ⚠️ 正在播的那首就在这里面 —— 本脚本只删「3 分钟没动过」的，
#                             别改成无条件全删，否则一点清理就把当前播放拆了
#   files/music/*.pcm+.meta   云端 TTS 唱过的歌整首存下来的 PCM（无上限，清掉等于忘掉这些歌）
#   files/exports/*.txt       聊天记录导出
#   files/status.txt          调试日志（每次启动自动删）
#
# 本机文件播放（/sdcard/Music 等）不产生缓存，原地读。
#
# 用法：
#   bash tools/cache.sh                # 只看（默认 show）
#   bash tools/cache.sh show
#   bash tools/cache.sh parts          # 清 part_ 残片（跳过正在播的那份，最该清的就是它）
#   bash tools/cache.sh smb            # 清整包缓存：smb_（共享盘）+ ol_（网盘）
#   bash tools/cache.sh lib            # 清云端 PCM 曲库（听过的歌要从服务端重唱一遍）
#   bash tools/cache.sh exports        # 清导出的聊天记录 TXT
#   bash tools/cache.sh all            # parts + smb + lib（不动 exports）
#   bash tools/cache.sh show 192.168.1.101:5555          # 第二台机器：第二参数指定设备
#
# 注意：只按前缀删文件，不做 rm -rf，不碰目录本身。
#      设备 shell 是 toybox，**没有 awk** —— 统计一律拉回本机算。

set -u

ACTION="${1:-show}"
DEV="${2:-192.168.1.100:5555}"
DIR=/sdcard/Android/data/com.altc.xiaozhi/files
ADB=(adb -s "$DEV")

run() { "${ADB[@]}" shell "$1" 2>/dev/null; }

human() {
  awk -v b="${1:-0}" 'BEGIN{
    if (b < 1024) printf "%dB", b;
    else if (b < 1048576) printf "%.1fK", b/1024;
    else if (b < 1073741824) printf "%.1fM", b/1048576;
    else printf "%.2fG", b/1073741824;
  }'
}

# 列出某前缀的文件，输出「字节数 文件名」；没有就是空
list_prefix() {
  run "ls -l $DIR/$1*" | awk 'NF>5 && $1 ~ /^-/ {print $5"\t"$NF}'
}

# 目录总字节（du -sk → KB → 字节）
dir_bytes() {
  run "du -sk $DIR/$1" | awk '{print $1*1024}' | head -1
}

# 统计一行：「个数 字节数」
sum_prefix() {
  list_prefix "$1" | awk '{n++; s+=$1} END {printf "%d %d", n+0, s+0}'
}

print_prefix() {
  list_prefix "$1" | awk -F'\t' '{printf "    %8.1fM  %s\n", $1/1048576, $2}' | head -8
}

show() {
  echo "设备: $DEV"
  local total=0 n b exn exb
  for spec in "smb_:共享盘缓存" "ol_:网盘缓存" "part_:下载残片"; do
    local prefix="${spec%%:*}" label="${spec#*:}"
    read -r n b <<<"$(sum_prefix "$prefix")"
    [ "${n:-0}" -gt 0 ] && print_prefix "$prefix"
    b=${b:-0}
    printf '%-16s %3d 个  %8s\n' "$label" "${n:-0}" "$(human "$b")"
    total=$((total + b))
  done

  n=$(run "ls $DIR/music/*.pcm" | grep -c '\.pcm$'); n=${n:-0}
  b=$(dir_bytes music); b=${b:-0}
  if [ "${n:-0}" -gt 0 ]; then
    list_prefix "music/$(run "ls $DIR/music" | grep '\.pcm$' | head -1)" >/dev/null 2>&1
    run "ls -l $DIR/music" | awk '$1 ~ /^-/ && $NF ~ /\.pcm$/ {printf "    %8.1fM  %s\n", $5/1048576, $NF}' | head -8
  fi
  printf '%-16s %3d 个  %8s\n' "PCM 云端曲库" "${n:-0}" "$(human "$b")"
  total=$((total + b))

  exn=$(run "ls $DIR/exports" | grep -c '\.txt$'); exn=${exn:-0}
  exb=$(dir_bytes exports); exb=${exb:-0}
  printf '%-16s %3d 个  %8s\n' "聊天记录导出" "${exn:-0}" "$(human "$exb")"

  echo "-------------------------------------------"
  printf '合计缓存 %s（不含导出）\n' "$(human "$total")"
  run "df -h /sdcard" | tail -1
  echo
  echo "本机文件播放不产生缓存；共享盘/网盘边下边播下完的会留在 smb_ / ol_ 里（下次秒开）。"
}

del_parts() {
  local n b left old
  read -r n b <<<"$(sum_prefix "part_")"
  if [ "${n:-0}" -eq 0 ]; then echo "下载残片: 没有可清的"; return; fi
  # 边下边播正在播的那首就躺在 part_ 里 —— 只清 3 分钟没动过的，
  # 无条件 rm -f part_* 会把当前这首歌直接拆掉（设备上 find -mmin 可用）
  old=$(run "cd $DIR && find . -maxdepth 1 -name 'part_*' -type f -mmin +3" | sed 's|^\./||')
  if [ -z "$old" ]; then
    printf '下载残片: %d 个全是最近 3 分钟在写的（正在播），先停掉播放再清\n' "${n:-0}"
    return
  fi
  echo "$old" | while IFS= read -r f; do
    [ -n "$f" ] && run "rm -f '$DIR/$f'" >/dev/null 2>&1
  done
  left=$(sum_prefix "part_" | awk '{print $1}')
  printf '下载残片: 清掉 %d 个（正在播的已跳过），剩 %s 个\n' \
    $(( ${n:-0} - ${left:-0} )) "${left:-?}"
}

del_prefix() {
  local prefix="$1" label="$2" n b left
  read -r n b <<<"$(sum_prefix "$prefix")"
  if [ "${n:-0}" -eq 0 ]; then echo "$label: 没有可清的"; return; fi
  run "rm -f $DIR/$prefix*"
  left=$(sum_prefix "$prefix" | awk '{print $1}')
  printf '%s: 清掉 %d 个，释放 %s（剩 %s 个未删掉）\n' "$label" "$n" "$(human "${b:-0}")" "${left:-?}"
}

del_lib() {
  local n b
  n=$(run "ls $DIR/music/*.pcm" | grep -c '\.pcm$'); n=${n:-0}
  b=$(dir_bytes music); b=${b:-0}
  if [ "${n:-0}" -eq 0 ]; then echo "PCM 云端曲库: 没有可清的"; return; fi
  run "rm -f $DIR/music/*.pcm $DIR/music/*.meta"
  printf 'PCM 云端曲库: 清掉 %d 首，释放 %s\n' "$n" "$(human "$b")"
  echo "  注意：这些歌本地的整首副本没了，下次点播会重新从服务端流式唱一遍。"
}

case "$ACTION" in
  show|"") show ;;
  parts)   del_parts ;;
  smb|dl)  del_prefix "smb_" "共享盘缓存"; del_prefix "ol_" "网盘缓存" ;;
  lib)     del_lib ;;
  exports) del_prefix "exports/chat_" "聊天记录导出" ;;
  all)     del_parts; del_prefix "smb_" "共享盘缓存"; del_prefix "ol_" "网盘缓存"; del_lib ;;
  *) echo "用法: bash tools/cache.sh [show|parts|smb|lib|exports|all] [设备地址]" >&2; exit 2 ;;
esac
