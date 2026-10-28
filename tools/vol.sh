#!/usr/bin/env bash
#
# vol.sh - 音箱音量快捷开关（调试规范配套工具）
#
# 规矩：**调试期间设备音量必须归零**（不然定时任务/截图/切前台都会突然出声，
#       深夜尤其要命）；调试结束后恢复到 5% 左右（这台机器 0~15 共 16 档，index 1 ≈ 7%）。
#
# 用法：
#   bash tools/vol.sh mute            # 静音（index 0）—— 开始调试前跑这一条
#   bash tools/vol.sh restore         # 恢复到 index 1（≈7%，接近 5%）
#   bash tools/vol.sh show            # 只看当前档位（不动音量）
#   bash tools/vol.sh 8               # 直接设到某一档（0~15）
#   bash tools/vol.sh mute 192.168.1.101:5555   # 第二台机器：第二个参数指定设备
#
# 说明：
#   · 这台机器 `cmd media_session volume` 不支持，必须用 `media volume --stream 3`。
#   · STREAM_MUSIC 的音量是按输出设备分开存的（volume_music_speaker 等），
#     所以脚本里带 --stream 3 改的是「扬声器」那条，和机身音量键改的是同一个。
#   · `media volume --stream 3 --set N` 是「设档位」，不是百分比；0~15。

set -u

DEV="${2:-192.168.1.100:5555}"
ADB=(adb -s "$DEV")

show() {
  "${ADB[@]}" shell dumpsys audio 2>/dev/null \
    | sed -n '/STREAM_MUSIC:/,/STREAM_ALARM/p' \
    | sed -n 's/.*Current:.*(speaker): \([0-9]*\).*/speaker index = \1/p' \
    | head -1
}

set_idx() {
  local n="$1"
  "${ADB[@]}" shell media volume --stream 3 --set "$n" >/dev/null 2>&1
  printf 'volume -> %s (%s)\n' "$n" "$(show)"
}

case "${1:-}" in
  mute)    set_idx 0 ;;
  restore) set_idx 1 ;;
  show|"") echo "$(show)" ;;
  *[!0-9]*) echo "用法: bash tools/vol.sh mute|restore|show|<0-15> [设备地址]" >&2; exit 2 ;;
  *)       set_idx "$1" ;;
esac
