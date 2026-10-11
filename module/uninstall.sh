#!/system/bin/sh
if [ -f /data/adb/cabin/cabind.pid ]; then
  kill "$(cat /data/adb/cabin/cabind.pid)" 2>/dev/null
fi
# Hand calls back to the Bluetooth app's own call service.
for c in com.android.bluetooth.hfpclient.connserv.HfpClientConnectionService \
  com.android.bluetooth.hfpclient.HfpClientConnectionService; do
  pm default-state "com.android.bluetooth/$c" >/dev/null 2>&1
done
# Put back the stock disabled-profiles setting (Android 12 and older).
if [ -f /data/adb/cabin/orig.disabled_profiles ]; then
  settings put global bluetooth_disabled_profiles "$(cat /data/adb/cabin/orig.disabled_profiles)" >/dev/null 2>&1
fi
rm -rf /data/adb/cabin
