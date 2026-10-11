#!/system/bin/sh
if [ -f /data/adb/cabin/smsd.pid ]; then
  kill "$(cat /data/adb/cabin/smsd.pid)" 2>/dev/null
fi
rm -rf /data/adb/cabin
