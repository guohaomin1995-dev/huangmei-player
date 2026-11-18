#!/usr/bin/env bash
# 把原片统一转成平板好放的格式：720p H.264 + AAC 的 MP4，音量拉齐，再截一帧当海报。
# 用法：tools/prepare_videos.sh <原片目录> <输出目录>
# 需要 ffmpeg。已经转过的（输出目录里有同名 mp4）会跳过；已有同名 jpg 海报不会覆盖。
set -euo pipefail

src="${1:?用法: $0 <原片目录> <输出目录>}"
out="${2:?用法: $0 <原片目录> <输出目录>}"
mkdir -p "$out"

find "$src" -type f \( -iname '*.mp4' -o -iname '*.mkv' -o -iname '*.flv' -o -iname '*.avi' -o -iname '*.mov' \
  -o -iname '*.ts' -o -iname '*.wmv' -o -iname '*.mpg' -o -iname '*.mpeg' -o -iname '*.rmvb' -o -iname '*.webm' \) \
  ! -name '._*' -print0 |
while IFS= read -r -d '' f; do
  name="$(basename "${f%.*}")"
  dst="$out/$name.mp4"
  if [[ -f "$dst" ]]; then
    echo "跳过（已转过）：$name"
  else
    echo "转码：$name"
    ffmpeg -nostdin -hide_banner -loglevel error -y -i "$f" \
      -map 0:v:0 -map '0:a:0?' \
      -c:v libx264 -preset medium -crf 22 -vf "scale=-2:'min(720,ih)'" -pix_fmt yuv420p \
      -c:a aac -b:a 160k -ac 2 -af loudnorm=I=-14:TP=-1.5:LRA=11 \
      -movflags +faststart "$dst.part.mp4"
    mv "$dst.part.mp4" "$dst"
  fi

  poster="$out/$name.jpg"
  if [[ ! -f "$poster" ]]; then
    if [[ -f "${f%.*}.jpg" ]]; then
      cp "${f%.*}.jpg" "$poster"
    else
      # 第 5 分钟截一帧；片子太短就截第 5 秒
      ffmpeg -nostdin -hide_banner -loglevel error -y -ss 300 -i "$dst" -frames:v 1 -q:v 3 "$poster" 2>/dev/null \
        || ffmpeg -nostdin -hide_banner -loglevel error -y -ss 5 -i "$dst" -frames:v 1 -q:v 3 "$poster"
      [[ -s "$poster" ]] || ffmpeg -nostdin -hide_banner -loglevel error -y -ss 5 -i "$dst" -frames:v 1 -q:v 3 "$poster"
    fi
  fi
done
echo "完成：$out"
