#!/system/bin/sh
# Cabin control script. Runs as root.
#
#   cabin on | off | toggle        turn Cabin on (last mode) / off / flip
#   cabin hfp | sap                switch mode (and turn on)
#   cabin set KEY VALUE            CALL_AUDIO tablet|phone, MEDIA_SINK 0|1, HFP_AG keep|off
#   cabin status                   show mode, settings and Bluetooth profile state
#   cabin restart-bt               restart Bluetooth so profile changes take effect
#   cabin sap-report               what this device's modem offers for SAP mode
#
# Internal (module scripts): boot-props, boot, from-app KEY VALUE

MODDIR=/data/adb/modules/cabin
[ -f "$MODDIR/module.prop" ] || MODDIR=${0%/*}
DATA=${CABIN_DATA:-/data/adb/cabin}
CONF=$DATA/config
ORIG=$DATA/orig.prop
LOG=$DATA/cabin.log
PKG=com.cabin.bridge
OVERLAY=com.cabin.btoverlay
API=$(getprop ro.build.version.sdk)

# Every property Cabin may touch. Stock values are saved at each boot so "off" can restore them.
PROPS="bluetooth.profile.hfp.hf.enabled
bluetooth.profile.map.client.enabled
bluetooth.profile.pbap.client.enabled
bluetooth.profile.a2dp.sink.enabled
bluetooth.profile.a2dp.source.enabled
bluetooth.profile.avrcp.controller.enabled
bluetooth.profile.hfp.ag.enabled
bluetooth.device.class_of_device"

# Car-audio class of device (0x260408) so phones offer "calls, contacts, messages".
CAR_COD="38,4,8"

mkdir -p "$DATA"

log() {
  echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG"
}

rp() {
  if command -v resetprop >/dev/null 2>&1; then
    resetprop "$@"
    return
  fi
  for b in /data/adb/ksu/bin/resetprop /data/adb/ap/bin/resetprop; do
    [ -x "$b" ] && { "$b" "$@"; return; }
  done
  for b in /data/adb/magisk/magisk /data/adb/magisk/magisk64 /data/adb/magisk/magisk32; do
    [ -x "$b" ] && { "$b" resetprop "$@"; return; }
  done
  log "resetprop not found"
  return 1
}

load_conf() {
  MODE=hfp
  LAST_ON=hfp
  CALL_AUDIO=tablet
  MEDIA_SINK=0
  HFP_AG=keep
  [ -f "$CONF" ] || return 0
  while IFS='=' read -r k v; do
    case "$k" in
      MODE) case "$v" in hfp|sap|off) MODE=$v ;; esac ;;
      LAST_ON) case "$v" in hfp|sap) LAST_ON=$v ;; esac ;;
      CALL_AUDIO) case "$v" in tablet|phone) CALL_AUDIO=$v ;; esac ;;
      MEDIA_SINK) case "$v" in 0|1) MEDIA_SINK=$v ;; esac ;;
      HFP_AG) case "$v" in keep|off) HFP_AG=$v ;; esac ;;
    esac
  done < "$CONF"
}

save_conf() {
  cat > "$CONF.tmp" <<EOF
MODE=$MODE
LAST_ON=$LAST_ON
CALL_AUDIO=$CALL_AUDIO
MEDIA_SINK=$MEDIA_SINK
HFP_AG=$HFP_AG
EOF
  mv "$CONF.tmp" "$CONF"
}

save_orig() {
  : > "$ORIG.tmp"
  for p in $PROPS; do
    echo "$p=$(getprop "$p")" >> "$ORIG.tmp"
  done
  mv "$ORIG.tmp" "$ORIG"
}

restore_orig() {
  [ -f "$ORIG" ] || return 0
  while IFS='=' read -r k v; do
    [ -n "$k" ] || continue
    if [ -n "$v" ]; then
      rp "$k" "$v"
    else
      rp -d "$k" >/dev/null 2>&1
    fi
  done < "$ORIG"
}

orig_value() {
  [ -f "$ORIG" ] && sed -n "s/^$1=//p" "$ORIG"
}

# $1 = -n during post-fs-data (skip property triggers), empty at runtime.
set_props() {
  restore_orig
  [ "$MODE" = hfp ] || return 0
  rp $1 bluetooth.profile.hfp.hf.enabled true
  rp $1 bluetooth.profile.map.client.enabled true
  rp $1 bluetooth.profile.pbap.client.enabled true
  rp $1 bluetooth.device.class_of_device "$CAR_COD"
  if [ "$MEDIA_SINK" = 1 ]; then
    # Most stacks run only one A2DP role at a time.
    rp $1 bluetooth.profile.a2dp.sink.enabled true
    rp $1 bluetooth.profile.avrcp.controller.enabled true
    rp $1 bluetooth.profile.a2dp.source.enabled false
  fi
  [ "$HFP_AG" = off ] && rp $1 bluetooth.profile.hfp.ag.enabled false
  return 0
}

bt_pkg() {
  for p in com.android.bluetooth com.google.android.bluetooth; do
    pm path "$p" >/dev/null 2>&1 && { echo "$p"; return; }
  done
  echo com.android.bluetooth
}

HFP_CONNSERV="com.android.bluetooth.hfpclient.HfpClientConnectionService
com.android.bluetooth.hfpclient.connserv.HfpClientConnectionService"

set_components() {
  bp=$(bt_pkg)
  for c in $HFP_CONNSERV; do
    if [ "$MODE" = hfp ]; then
      pm enable "$bp/$c" >/dev/null 2>&1 && log "enabled $bp/$c"
    else
      pm default-state "$bp/$c" >/dev/null 2>&1
    fi
  done
}

# Android 12 and older read profile support from the Bluetooth app's resources.
# Returns 0 when the overlay state changed (Bluetooth needs a restart).
set_overlay() {
  [ "$API" -le 32 ] 2>/dev/null || return 1
  cmd overlay list 2>/dev/null | grep -q "$OVERLAY" || return 1
  if [ "$MODE" = hfp ]; then want=enable; else want=disable; fi
  if cmd overlay list 2>/dev/null | grep -q "\[x\] $OVERLAY"; then have=enable; else have=disable; fi
  [ "$want" = "$have" ] && return 1
  cmd overlay "$want" --user 0 "$OVERLAY" >/dev/null 2>&1
  log "overlay $want"
  return 0
}

restart_bt() {
  on=$(settings get global bluetooth_on 2>/dev/null)
  log "restarting bluetooth (was $on)"
  cmd bluetooth_manager disable >/dev/null 2>&1 || svc bluetooth disable >/dev/null 2>&1
  i=0
  while [ $i -lt 8 ]; do
    [ "$(settings get global bluetooth_on 2>/dev/null)" = 0 ] && break
    sleep 1
    i=$((i + 1))
  done
  # The Bluetooth process caches its profile config; make sure it starts fresh.
  for p in com.android.bluetooth com.google.android.bluetooth; do
    pid=$(pidof "$p" 2>/dev/null) && kill $pid 2>/dev/null
  done
  sleep 2
  if [ "$on" != 0 ]; then
    cmd bluetooth_manager enable >/dev/null 2>&1 || svc bluetooth enable >/dev/null 2>&1
  fi
}

modem_kind() {
  if ! pm list features 2>/dev/null | grep -q '^feature:android.hardware.telephony$'; then
    echo none
  elif (lshal 2>/dev/null; service list 2>/dev/null) | grep -qi 'uim_remote\|uimremote'; then
    echo qcom
  else
    echo unknown
  fi
}

# What this device's modem offers for using a SIM over Bluetooth (SAP mode).
sap_report() {
  echo "telephony feature: $(pm list features 2>/dev/null | grep -q '^feature:android.hardware.telephony$' && echo yes || echo 'no (Wi-Fi only: SAP cannot work, use HFP)')"
  echo "modem: $(modem_kind)"
  echo "vendor remote-SIM app: $(pm path com.qualcomm.uimremoteclient 2>/dev/null | head -1 || true)"
  echo "remote-SIM HAL instances:"
  (lshal 2>/dev/null; service list 2>/dev/null) | grep -i 'uim_remote\|uimremote' | sed 's/^/  /' | sort -u
  echo "baseband: $(getprop gsm.version.baseband) / $(getprop ro.board.platform)"
}

notify_app() {
  if [ "$MEDIA_SINK" = 1 ]; then ms=true; else ms=false; fi
  am broadcast -f 0x20 -n "$PKG/.ConfigReceiver" -a com.cabin.bridge.CONFIG \
    --es mode "$MODE" --es last_on "$LAST_ON" --es call_audio "$CALL_AUDIO" \
    --ez media_sink "$ms" --es hfp_ag "$HFP_AG" --es modem "$(modem_kind)" >/dev/null 2>&1
}

describe() {
  case "$MODE" in
    hfp) d="ON - Hands-free (HFP). Pair your phone; calls and texts open in this device's Phone and Messages apps. Tap Action to turn off." ;;
    sap) d="ON - SIM Access (SAP). This device borrows your phone's SIM. Experimental; see the Cabin app. Tap Action to turn off." ;;
    *) d="OFF - Bluetooth is stock. Tap Action to turn Cabin back on ($LAST_ON)." ;;
  esac
  [ -f "$MODDIR/module.prop" ] && sed -i "s|^description=.*|description=$d|" "$MODDIR/module.prop"
}

# Apply the current config. $1 = restart (restart Bluetooth) or empty.
apply() {
  save_conf
  set_props
  set_components
  set_overlay && [ "$1" != restart ] && restart_bt
  [ "$1" = restart ] && restart_bt
  describe
  notify_app
  log "mode=$MODE call_audio=$CALL_AUDIO media_sink=$MEDIA_SINK hfp_ag=$HFP_AG"
}

set_mode() {
  case "$1" in
    on) MODE=$LAST_ON ;;
    off) MODE=off ;;
    toggle) if [ "$MODE" = off ]; then MODE=$LAST_ON; else MODE=off; fi ;;
    hfp|sap) MODE=$1 ;;
    *) return 1 ;;
  esac
  [ "$MODE" = off ] || LAST_ON=$MODE
}

set_key() {
  case "$1=$2" in
    MODE=*) set_mode "$2" ;;
    CALL_AUDIO=tablet|CALL_AUDIO=phone) CALL_AUDIO=$2 ;;
    MEDIA_SINK=0|MEDIA_SINK=1) MEDIA_SINK=$2 ;;
    HFP_AG=keep|HFP_AG=off) HFP_AG=$2 ;;
    *) return 1 ;;
  esac
}

# Keys whose change needs Bluetooth to restart.
needs_restart() {
  case "$1" in
    MODE|MEDIA_SINK|HFP_AG) return 0 ;;
  esac
  return 1
}

status() {
  echo "mode:        $MODE (last on: $LAST_ON)"
  echo "call audio:  $CALL_AUDIO"
  echo "media sink:  $MEDIA_SINK"
  echo "hfp ag:      $HFP_AG"
  echo "modem:       $(modem_kind)"
  echo "bluetooth:   $(bt_pkg), on=$(settings get global bluetooth_on 2>/dev/null)"
  for p in $PROPS; do
    printf '  %-45s %s (stock: %s)\n' "$p" "$(getprop "$p")" "$(orig_value "$p")"
  done
  if [ -f "$DATA/cabind.pid" ] && kill -0 "$(cat "$DATA/cabind.pid")" 2>/dev/null; then
    echo "helper:      running"
  else
    echo "helper:      not running"
  fi
}

if [ "$(id -u)" != 0 ]; then
  echo "cabin: run as root (su -c cabin $*)" >&2
  exit 1
fi

load_conf
cmd=$1
[ $# -gt 0 ] && shift
case "$cmd" in
  boot-props)
    save_orig
    set_props -n
    ;;
  boot)
    set_components
    set_overlay && restart_bt
    describe
    notify_app
    ;;
  on|off|toggle|hfp|sap)
    old=$MODE
    set_mode "$cmd"
    if [ "$MODE" = "$old" ]; then apply; else apply restart; fi
    echo "Cabin: $MODE"
    ;;
  set|from-app)
    key=$1
    val=$2
    case "$key" in
      MODE|CALL_AUDIO|MEDIA_SINK|HFP_AG) ;;
      *) echo "cabin: unknown setting $key" >&2; log "rejected key"; exit 2 ;;
    esac
    old_mode=$MODE
    old_val=$(eval "echo \$$key")
    if ! set_key "$key" "$val"; then
      echo "cabin: unknown setting $key=$val" >&2
      log "rejected $key=$val"
      exit 2
    fi
    new_val=$(eval "echo \$$key")
    [ "$key" = MODE ] && { old_val=$old_mode; new_val=$MODE; }
    if needs_restart "$key" && [ "$old_val" != "$new_val" ]; then apply restart; else apply; fi
    echo "Cabin: $key=$new_val"
    ;;
  restart-bt)
    restart_bt
    ;;
  sap-report)
    sap_report
    ;;
  status|"")
    status
    ;;
  *)
    sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac
