#!/usr/bin/env bash
# Capture MeshLink logs from several devices at once, then summarise the key events per device.
# Usage: logs.sh <seconds> [serial...]   (default: devices in ../config.json, else every connected device)
set -u
cd "$(dirname "$0")/../../../.." || exit 1

secs=${1:-60}; shift || true
CONFIG=.claude/skills/meshlink-verify/config.json
if [ $# -gt 0 ]; then
  serials="$*"
else
  serials=$(jq -r '.devices[]? | .serial // .' "$CONFIG" 2>/dev/null)
  [ -n "$serials" ] || serials=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
fi
[ -n "$serials" ] || { echo "No device connected"; exit 1; }

dir=.claude/skills/meshlink-verify/logs/$(date +%Y%m%d-%H%M%S)
mkdir -p "$dir"

for s in $serials; do
  # Only the running app process (from its start, so the node id line is included);
  # if the app isn't running yet, everything from now on.
  pid=$(adb -s "$s" shell pidof com.hieu.p2p | tr -d '\r')
  if [ -n "$pid" ]; then
    adb -s "$s" logcat -v time -s MeshLink:I --pid="$pid" > "$dir/$s.log" 2>&1 &
  else
    since=$(adb -s "$s" shell "date +'%m-%d %H:%M:%S.000'" | tr -d '\r')
    adb -s "$s" logcat -v time -s MeshLink:I -T "$since" > "$dir/$s.log" 2>&1 &
  fi
  echo $! >> "$dir/.pids"
done
echo "Capturing ${secs}s from: $serials"
sleep "$secs"
xargs kill < "$dir/.pids" 2>/dev/null
wait 2>/dev/null
rm -f "$dir/.pids"

for s in $serials; do
  f="$dir/$s.log"
  count() { grep -c -- "$1" "$f"; }
  echo "== $s  ($(wc -l < "$f" | tr -d ' ') lines) -> $f"
  echo "   node id:      $(grep -o 'Khởi động nút [^ ]*' "$f" | tail -1 | cut -d' ' -f4)"
  echo "   radio up:     advertising=$(count 'Đang quảng bá') discovery=$(count 'Đang dò tìm')"
  echo "   links:        new=$(count 'Kết nối trực tiếp mới') lost=$(count 'Mất kết nối') failed=$(grep -cE 'thất bại|không thành công' "$f")"
  echo "   nodes found:  $(grep -o 'Phát hiện nút .*' "$f" | sed 's/Phát hiện nút //' | paste -sd ';' -)"
  echo "   sync ops:     sent=$(count 'Đồng bộ: gửi') received=$(count 'Đồng bộ: nhận')"
  echo "   traceroute:   $(grep -o 'Dò đường xong.*' "$f" | tail -1)"
  echo "   calls:        $(grep -o 'call [0-9]* hop.*' "$f" | tail -1)"
  echo "   problems:     version=$(count 'giao thức v') errors=$(count 'Lỗi:') send-fail=$(count 'Gửi tới')"
done
