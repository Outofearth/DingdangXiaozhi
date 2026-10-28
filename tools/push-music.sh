#!/usr/bin/env bash
#
# push-music.sh - 把电脑上的音乐推到音箱，让小智能语音点播。
#
# 用法：
#   bash tools/push-music.sh "D:/我的音乐"                  # 整个文件夹（含子目录）递归推
#   bash tools/push-music.sh "D:/a.mp3" "D:/b.flac"          # 多个文件
#   bash tools/push-music.sh --list                          # 看音箱上现在有哪些歌
#   bash tools/push-music.sh --clear                         # 清空音箱上的本地音乐
#
# 支持的格式：音频 mp3 / flac / wav / m4a / aac / ogg / opus
#             视频 mp4 / m4v / mov / 3gp / mkv（当视频看或只听声音都行）
#   （这台机器的解码器就这些；ape / wma / avi / rmvb 系统放不了，需要先转格式）
#
# 推送完成后对音箱说「有哪些歌」即可确认，说「播放 歌名」就开始放。
# 文件名就是歌名：建议命名成「01 稻香.mp3」，前导序号会被自动去掉。
#
# 同名文件怎么处理（不会静默覆盖）：
#   · 设备上已有同名且字节数一样  → 跳过（视为同一首歌，重复推不浪费空间）
#   · 设备上已有同名但内容不同    → 另存为「歌名 (2).mp3」「(3)」…，两份都留着
#   · 同一批里两个不同目录下同名  → 同上，后来的自动加序号，不会互相盖掉
#
set -e

SERIAL="${SERIAL:-192.168.1.100:5555}"
DEST="/sdcard/Music/xiaozhi"       # 音频去哪
DEST_VIDEO="/sdcard/Movies"       # 视频去哪（放 Movies 当视频，放 Music 当音乐）
ADB="${ADB:-adb}"

run() { MSYS_NO_PATHCONV=1 "$ADB" -s "$SERIAL" "$@"; }

if ! run shell "echo ok" >/dev/null 2>&1; then
  echo "连不上音箱（$SERIAL）。先确认 adb connect 成功，或用 SERIAL=其他地址 运行。"
  exit 1
fi

case "$1" in
  --list)
    echo "音箱上的本地音乐（$DEST）："
    run shell "ls -l $DEST 2>/dev/null | tail -n +2" || true
    echo
    echo "音箱上的本地视频（$DEST_VIDEO）："
    run shell "ls -l $DEST_VIDEO 2>/dev/null | tail -n +2" || true
    exit 0
    ;;
  --clear)
    run shell "rm -f $DEST/*.mp3 $DEST/*.flac $DEST/*.wav $DEST/*.m4a $DEST/*.aac $DEST/*.ogg $DEST/*.opus"
    echo "已清空 $DEST 下的音频文件"
    exit 0
    ;;
  ""|--help|-h)
    sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//'
    exit 0
    ;;
esac

run shell "mkdir -p '$DEST' '$DEST_VIDEO'"

# ---------- 建立「设备上已有文件」的索引：文件名 -> 字节数 ----------
declare -A RSIZE=()
while IFS='|' read -r sz p; do
  [ -n "$sz" ] && [ -n "$p" ] || continue
  RSIZE["${p##*/}"]="$sz"
done <<< "$(run shell "stat -c '%s|%n' $DEST/* $DEST_VIDEO/* 2>/dev/null" | tr -d '\r' || true)"

PUSHED=0
SKIPPED=0
RENAMED=0

# 推一个文件；重名时按上面的规则跳过或另存
push_one() {
  local src="$1" dir="$2" name size target stem ext i
  name="${src##*/}"
  size=$(wc -c < "$src" | tr -d ' \r')
  target="$name"

  if [ -n "${RSIZE[$target]+x}" ]; then
    if [ "${RSIZE[$target]}" = "$size" ]; then
      echo "  跳过（设备上已有同名同大小）：$name"
      SKIPPED=$((SKIPPED + 1))
      return 0
    fi
    if [[ "$name" == *.* ]]; then
      stem="${name%.*}"; ext=".${name##*.}"
    else
      stem="$name"; ext=""
    fi
    local cand=""
    i=2
    while [ "$i" -le 99 ]; do
      cand="$stem ($i)$ext"
      if [ -z "${RSIZE[$cand]+x}" ]; then break; fi
      # 已有同内容副本（上次推过同一首歌）：直接跳过，不必再存一份
      if [ "${RSIZE[$cand]}" = "$size" ]; then
        echo "  跳过（设备上已有同内容副本）：$cand"
        SKIPPED=$((SKIPPED + 1))
        return 0
      fi
      cand=""
      i=$((i + 1))
    done
    if [ -z "$cand" ]; then
      echo "  跳过（重名副本已到上限 99）：$name"
      SKIPPED=$((SKIPPED + 1))
      return 0
    fi
    target="$cand"
    echo "  同名但内容不同 → 另存为：$target"
    RENAMED=$((RENAMED + 1))
  fi

  run push "$src" "$dir/$target"
  RSIZE["$target"]="$size"
  PUSHED=$((PUSHED + 1))
}

# ---------- 先收集要推的文件清单 ----------
files=()
for src in "$@"; do
  if [ ! -e "$src" ]; then
    echo "跳过（不存在）: $src"
    continue
  fi
  if [ -d "$src" ]; then
    # 只推能播的扩展名（视频进 Movies、音频进 Music），其它文件不上设备。
    # 推目录「内容」（而非目录本身），免得音箱上多出一层同名文件夹。
    while IFS= read -r -d '' f; do
      case "$(printf '%s' "$f" | tr '[:upper:]' '[:lower:]')" in
        *.mp4|*.m4v|*.mov|*.3gp|*.mkv) files+=("$f") ;;
        *.mp3|*.flac|*.wav|*.m4a|*.aac|*.ogg|*.opus) files+=("$f") ;;
      esac
    done < <(find "$src" -type f -print0)
  else
    case "$(printf '%s' "$src" | tr '[:upper:]' '[:lower:]')" in
      *.mp4|*.m4v|*.mov|*.3gp|*.mkv|*.mp3|*.flac|*.wav|*.m4a|*.aac|*.ogg|*.opus)
        files+=("$src") ;;
      *) echo "跳过（不是支持的音视频格式）: $src" ;;
    esac
  fi
done

if [ ${#files[@]} -eq 0 ]; then
  echo "没有可推送的音视频文件。"
  exit 0
fi

# ---------- 逐个推送 ----------
for f in "${files[@]}"; do
  case "$(printf '%s' "$f" | tr '[:upper:]' '[:lower:]')" in
    *.mp4|*.m4v|*.mov|*.3gp|*.mkv) d="$DEST_VIDEO" ;;
    *) d="$DEST" ;;
  esac
  echo "推送: $f  ->  $d"
  push_one "$f" "$d"
done

echo
echo "完成：新推/更新 $PUSHED 个，跳过 $SKIPPED 个，因重名另存 $RENAMED 个（共扫描到 ${#files[@]} 个文件）。"
echo "对音箱说「有哪些歌」确认；说「播放 歌名」只出声，说「看 片名」出画面。"
