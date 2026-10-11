#!/system/bin/sh
if [ -f /data/adb/cabin/cabind.pid ]; then
  kill "$(cat /data/adb/cabin/cabind.pid)" 2>/dev/null
fi
cmd overlay disable --user 0 com.cabin.btoverlay >/dev/null 2>&1
rm -rf /data/adb/cabin
