#!/system/bin/sh
MODDIR=${0%/*}
DATA=/data/adb/cabin
PKG=com.cabin.bridge
mkdir -p "$DATA"

while [ "$(getprop sys.boot_completed)" != "1" ]; do
  sleep 1
done
sleep 3

for perm in \
  android.permission.RECEIVE_SMS \
  android.permission.READ_SMS \
  android.permission.SEND_SMS \
  android.permission.READ_PHONE_STATE \
  android.permission.CALL_PHONE \
  android.permission.ANSWER_PHONE_CALLS \
  android.permission.READ_CALL_LOG \
  android.permission.READ_CONTACTS \
  android.permission.WRITE_CONTACTS \
  android.permission.BLUETOOTH_CONNECT \
  android.permission.BLUETOOTH_SCAN \
  android.permission.POST_NOTIFICATIONS
do
  pm grant "$PKG" "$perm" >/dev/null 2>&1
done
# Lets the app write received texts straight into Messages without being the SMS app.
appops set "$PKG" WRITE_SMS allow >/dev/null 2>&1
dumpsys deviceidle whitelist +"$PKG" >/dev/null 2>&1

PIDFILE=$DATA/cabind.pid
# The pid from before a reboot may now belong to some other process; check it is the helper.
if [ -f "$PIDFILE" ] && grep -q cabind.sh "/proc/$(cat "$PIDFILE")/cmdline" 2>/dev/null; then
  :
else
  sh "$MODDIR/cabind.sh" >/dev/null 2>&1 &
  echo $! > "$PIDFILE"
fi

sh "$MODDIR/cabin.sh" boot
