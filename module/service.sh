#!/system/bin/sh
MODDIR=${0%/*}
mkdir -p /data/adb/cabin
LOG=/data/adb/cabin/boot.log

log() {
  echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG"
}

while [ "$(getprop sys.boot_completed)" != "1" ]; do
  sleep 1
done
sleep 3

if grep -q 'name="enabled" value="false"' /data/data/com.cabin.bridge/shared_prefs/cabin.xml 2>/dev/null; then
  log "cabin is off"
  exit 0
fi

enable_comp() {
  if pm enable "$1" >/dev/null 2>&1; then
    log "enabled $1"
  else
    log "no component $1"
  fi
}

enable_comp com.android.bluetooth/com.android.bluetooth.hfpclient.HeadsetClientService
enable_comp com.android.bluetooth/com.android.bluetooth.hfpclient.connserv.HfpClientConnectionService
enable_comp com.android.bluetooth/com.android.bluetooth.mapclient.MapClientService
enable_comp com.android.bluetooth/com.android.bluetooth.pbapclient.PbapClientService
enable_comp com.android.bluetooth/com.android.bluetooth.a2dpsink.A2dpSinkService
enable_comp com.android.bluetooth/com.android.bluetooth.avrcpcontroller.AvrcpControllerService

PKG=com.cabin.bridge
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
  pm grant "$PKG" "$perm" >/dev/null 2>&1 || true
done

dumpsys deviceidle whitelist +"$PKG" >/dev/null 2>&1 || true

killall com.android.bluetooth >/dev/null 2>&1 || true
sleep 1

PIDFILE=/data/adb/cabin/smsd.pid
if [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; then
  log "sms helper already running"
else
  sh "$MODDIR/cabin-smsd.sh" >> /data/adb/cabin/smsd.log 2>&1 &
  echo $! > "$PIDFILE"
  log "sms helper started"
fi

am start-foreground-service -n "$PKG/.LinkService" >/dev/null 2>&1 \
  || am startservice -n "$PKG/.LinkService" >/dev/null 2>&1 \
  || log "could not start Cabin"
log "boot script done"
