#!/usr/bin/env bash
# Install the debug APK on test devices without wiping data, grant runtime permissions, start the app.
# Usage: deploy.sh [serial...]   (default: devices in ../config.json, else every connected device)
set -u
cd "$(dirname "$0")/../../../.." || exit 1

APK=app/build/outputs/apk/debug/app-debug.apk
PKG=com.hieu.p2p
PERMS="BLUETOOTH_SCAN BLUETOOTH_ADVERTISE BLUETOOTH_CONNECT NEARBY_WIFI_DEVICES ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION POST_NOTIFICATIONS RECORD_AUDIO CAMERA"
CONFIG=.claude/skills/meshlink-verify/config.json

[ -f "$APK" ] || { echo "No APK at $APK — run ./gradlew :app:assembleDebug first"; exit 1; }

if [ $# -gt 0 ]; then
  serials="$*"
else
  serials=$(jq -r '.devices[]? | .serial // .' "$CONFIG" 2>/dev/null)
  [ -n "$serials" ] || serials=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
fi
[ -n "$serials" ] || { echo "No device connected"; exit 1; }

for s in $serials; do
  (
    out=$(adb -s "$s" install -r "$APK" 2>&1 | tail -1)
    # Not every permission is runtime on every API level; failures there are expected.
    for p in $PERMS; do adb -s "$s" shell pm grant "$PKG" "android.permission.$p" >/dev/null 2>&1; done
    adb -s "$s" shell am start -n "$PKG/.ui.MainActivity" >/dev/null 2>&1
    model=$(adb -s "$s" shell getprop ro.product.model | tr -d '\r')
    echo "$s ($model): $out"
  ) &
done
wait
