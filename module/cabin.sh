#!/system/bin/sh
# Cabin control script. Runs as root.
#
#   cabin on | off | toggle        turn Cabin on (last mode) / off / flip
#   cabin hfp | sap                switch mode (and turn on)
#   cabin set KEY VALUE            CALL_AUDIO tablet|phone, MEDIA_SINK 0|1, HFP_AG auto|keep|off,
#                                  CALL_HANDLER auto|system|cabin
#   cabin status                   show mode, settings and Bluetooth profile state
#   cabin restart-bt               restart Bluetooth so profile changes take effect
#   cabin sap-report               what this device's modem offers for SAP mode
#   cabin report                   save a debug report to /sdcard/Download/cabin-report.txt
#
# Internal (module scripts): boot-props, boot, from-app KEY VALUE

MODDIR=/data/adb/modules/cabin
[ -f "$MODDIR/module.prop" ] || MODDIR=${0%/*}
DATA=${CABIN_DATA:-/data/adb/cabin}
CONF=$DATA/config
ORIG=$DATA/orig.prop
LOG=$DATA/cabin.log
PKG=com.cabin.bridge
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
  HFP_AG=auto
  CALL_HANDLER=auto
  [ -f "$CONF" ] || return 0
  while IFS='=' read -r k v; do
    case "$k" in
      MODE) case "$v" in hfp|sap|off) MODE=$v ;; esac ;;
      LAST_ON) case "$v" in hfp|sap) LAST_ON=$v ;; esac ;;
      CALL_AUDIO) case "$v" in tablet|phone) CALL_AUDIO=$v ;; esac ;;
      MEDIA_SINK) case "$v" in 0|1) MEDIA_SINK=$v ;; esac ;;
      HFP_AG) case "$v" in auto|keep|off) HFP_AG=$v ;; esac ;;
      CALL_HANDLER) case "$v" in auto|system|cabin) CALL_HANDLER=$v ;; esac ;;
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
CALL_HANDLER=$CALL_HANDLER
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
  ag_off && rp $1 bluetooth.profile.hfp.ag.enabled false
  return 0
}

bt_pkg() {
  for p in com.android.bluetooth com.google.android.bluetooth; do
    pm path "$p" >/dev/null 2>&1 && { echo "$p"; return; }
  done
  echo com.android.bluetooth
}

# Bluetooth app components whose manifest "enabled" comes from the stock profile switches.
# Android reads those once at install time, so neither the overlay nor the props can turn
# them on; they have to be enabled by hand. Without AuthenticationService, Android refuses
# the "Bluetooth contacts" account and the Bluetooth process crashes when the phone connects
# (SecurityException: cannot explicitly add accounts of type com.android.bluetooth.pbapsink).
CLIENT_COMPONENTS="com.android.bluetooth.hfpclient.HfpClientConnectionService
com.android.bluetooth.hfpclient.connserv.HfpClientConnectionService
com.android.bluetooth.pbapclient.AuthenticationService"

# Who turns phone calls into calls in the Phone app: the Bluetooth app's own service
# ("system") or Cabin's. On Android 11 the system one ignored the Answer button (no ATA
# was ever sent), while the same answer request from Cabin worked, so "auto" uses Cabin
# on Android 12 and older.
cabin_handles_calls() {
  [ "$CALL_HANDLER" = cabin ] || { [ "$CALL_HANDLER" = auto ] && [ "$API" -le 32 ] 2>/dev/null; }
}

# Only ever enable these; never put them back when Cabin turns off. Changing a component
# makes Android kill the Bluetooth process, and once a "Bluetooth contacts" account exists,
# Bluetooth needs AuthenticationService to remove it or it crash-loops
# (SecurityException: cannot explicitly remove accounts of type ...pbapsink).
# "Off" is done by bluetooth_disabled_profiles / the props, which stop the profiles themselves.
set_components() {
  [ "$MODE" = hfp ] || return 0
  bp=$(bt_pkg)
  for c in $CLIENT_COMPONENTS; do
    case "$c" in
      *HfpClientConnectionService)
        # Cabin's own call service takes over; the system one must step aside.
        if cabin_handles_calls; then
          pm disable "$bp/$c" >/dev/null 2>&1 && log "disabled $bp/$c (Cabin handles calls)"
          continue
        fi ;;
    esac
    pm enable "$bp/$c" >/dev/null 2>&1 && log "enabled $bp/$c"
  done
}

# Kernel mixer controls, saved while the system is up (install time and every boot), so
# post-fs-data can check which controls really exist before the sound card is ready.
MIXER_CTLS=$DATA/mixer_ctls.txt
save_mixer_ctls() {
  command -v tinymix >/dev/null 2>&1 || return 0
  tinymix 2>/dev/null > "$MIXER_CTLS.tmp" && [ -s "$MIXER_CTLS.tmp" ] && mv "$MIXER_CTLS.tmp" "$MIXER_CTLS"
  rm -f "$MIXER_CTLS.tmp"
}

has_ctl() {
  [ -f "$MIXER_CTLS" ] && grep -qF "	$1 " "$MIXER_CTLS"
}

# Car-kit (HFP client) call audio on Qualcomm audio HALs, fixed up from the device's own
# mixer_paths files. Runs in post-fs-data, before Magisk mounts the module, so it always
# starts from the stock files and follows OTA updates. Found on an Android 11 msm8937 phone:
#  1. The HAL routes these calls to "voice-speaker-hfp" / "voice-speaker-mic-hfp", which
#     phone/tablet files often never define. Alias them to the speakerphone paths.
#  2. "hfp-sco" may name a "<PORT> Port Mixer INTERNAL_BT_SCO_TX" control the kernel lacks
#     (QUIN_MI2S_RX there), so the caller is never heard. Use a port from a sibling hfp path
#     whose control does exist (PRI_MI2S_RX there).
#  3. The port's hostless downlink switch ("<PORT>_DL_HL Switch") is missing from the path,
#     so the downlink never starts. Add it when the kernel has it.
#  4. With the call on the earpiece the HAL looks up "hfp-sco handset", which isn't defined,
#     so none of the car-kit switches get set. Alias it to "hfp-sco".
patch_mixer_paths() {
  out=$MODDIR/system/vendor/etc
  rm -f "$out"/mixer_paths*.xml
  for f in /vendor/etc/mixer_paths*.xml; do
    [ -f "$f" ] || continue
    name=${f##*/}
    tmp=$DATA/$name.tmp
    cp "$f" "$tmp"
    changed=

    if ! grep -q '<path name="voice-speaker-hfp"' "$tmp"; then
      spk=; mic=
      for p in voice-speaker speaker; do
        grep -q "<path name=\"$p\">" "$tmp" && { spk=$p; break; }
      done
      for p in voice-speaker-mic speaker-mic handset-mic; do
        grep -q "<path name=\"$p\">" "$tmp" && { mic=$p; break; }
      done
      if [ -n "$spk" ] && [ -n "$mic" ]; then
        sed -i "s|</mixer>|    <path name=\"voice-speaker-hfp\">\n        <path name=\"$spk\" />\n    </path>\n    <path name=\"voice-speaker-mic-hfp\">\n        <path name=\"$mic\" />\n    </path>\n</mixer>|" "$tmp"
        changed="aliases $spk/$mic"
      fi
    fi

    for base in hfp-sco hfp-sco-wb; do
      grep -q "<path name=\"$base\">" "$tmp" || continue
      grep -q "<path name=\"$base handset\">" "$tmp" && continue
      sed -i "s|</mixer>|    <path name=\"$base handset\">\n        <path name=\"$base\" />\n    </path>\n</mixer>|" "$tmp"
      changed="$changed, +$base handset"
    done

    if [ -f "$MIXER_CTLS" ]; then
      hfp=$(sed -n '/<path name="hfp-sco/,/<\/path>/p' "$tmp")
      ports=$(echo "$hfp" | sed -n 's/.*name="\([A-Z0-9_]*\) Port Mixer INTERNAL_BT_SCO_TX".*/\1/p' | sort -u)
      good=
      for p in $ports; do
        has_ctl "$p Port Mixer INTERNAL_BT_SCO_TX" && { good=$p; break; }
      done
      for p in $ports; do
        if ! has_ctl "$p Port Mixer INTERNAL_BT_SCO_TX" && [ -n "$good" ]; then
          sed -i "s|\"$p Port Mixer INTERNAL_BT_SCO_TX\"|\"$good Port Mixer INTERNAL_BT_SCO_TX\"|" "$tmp"
          changed="$changed, port $p->$good"
        fi
      done
      hfp=$(sed -n '/<path name="hfp-sco/,/<\/path>/p' "$tmp")
      for p in $(echo "$hfp" | sed -n 's/.*name="\([A-Z0-9_]*\) Port Mixer INTERNAL_BT_SCO_TX".*/\1/p' | sort -u); do
        sw="${p}_DL_HL Switch"
        has_ctl "$sw" || continue
        echo "$hfp" | grep -qF "\"$sw\"" && continue
        sed -i "s|^\([ 	]*\)<ctl name=\"$p Port Mixer INTERNAL_BT_SCO_TX\" value=\"1\" */>|&\n\1<ctl name=\"$sw\" value=\"1\" />|" "$tmp"
        changed="$changed, +$sw"
      done
    fi

    if [ -n "$changed" ]; then
      mkdir -p "$out"
      mv "$tmp" "$out/$name"
      chmod 0644 "$out/$name"
      ctx=$(ls -Z "$f" 2>/dev/null | awk '{print $1}')
      [ -n "$ctx" ] && chcon "$ctx" "$out/$name" 2>/dev/null
      log "mixer paths: $name: $changed"
    else
      rm -f "$tmp"
    fi
  done
}

# The PCM device the HAL's car-kit loopback must open: the front end that "hfp-sco" feeds
# ("INTERNAL_BT_SCO_RX Audio Mixer MultiMediaN"), looked up in the kernel's PCM list. The
# HAL's built-in default (24 on that msm8937 build) may not exist. Empty if unknown.
hfp_pcm_dev_id() {
  fe=$(cat /vendor/etc/mixer_paths*.xml 2>/dev/null | sed -n '/<path name="hfp-sco">/,/<\/path>/p' \
    | sed -n 's/.*INTERNAL_BT_SCO_RX Audio Mixer \(MultiMedia[0-9]*\)".*/\1/p' | head -1)
  [ -n "$fe" ] || return 0
  sed -n "s/^[0-9]*-0*\([0-9][0-9]*\): $fe (.*/\1/p" /proc/asound/pcm 2>/dev/null | head -1
}

# Android 12 and older: a static overlay makes the client profiles available (props are
# ignored there), and the Bluetooth app skips any profile whose bit is set in
# Settings.Global bluetooth_disabled_profiles. That setting is the runtime on/off switch.
# Bits are 1 << BluetoothProfile id: HEADSET(AG)=1, HEADSET_CLIENT=16, PBAP_CLIENT=17, MAP_CLIENT=18.
AG_BIT=2
CLIENT_BITS=$(( (1 << 16) | (1 << 17) | (1 << 18) ))
uses_disabled_profiles() {
  [ "$API" -le 32 ] 2>/dev/null
}

# AG (this device acting as a phone for headsets) off? "auto" = off where the stack
# can't run AG and hands-free together (Android 12 and older).
ag_off() {
  [ "$HFP_AG" = off ] || { [ "$HFP_AG" = auto ] && uses_disabled_profiles; }
}

# Returns 0 when the setting changed (Bluetooth needs a restart).
set_disabled_profiles() {
  uses_disabled_profiles || return 1
  [ -f "$DATA/orig.disabled_profiles" ] || {
    v=$(settings get global bluetooth_disabled_profiles 2>/dev/null)
    case "$v" in ''|null|*[!0-9]*) v=0 ;; esac
    echo "$v" > "$DATA/orig.disabled_profiles"
  }
  stock=$(cat "$DATA/orig.disabled_profiles")
  if [ "$MODE" = hfp ]; then
    want=$(( stock & ~CLIENT_BITS ))
    if ag_off; then want=$(( want | AG_BIT )); else want=$(( want & ~AG_BIT )); fi
  else
    want=$(( (stock | CLIENT_BITS) & ~AG_BIT | (stock & AG_BIT) ))
  fi
  have=$(settings get global bluetooth_disabled_profiles 2>/dev/null)
  case "$have" in ''|null|*[!0-9]*) have=0 ;; esac
  [ "$want" = "$have" ] && return 1
  settings put global bluetooth_disabled_profiles "$want"
  log "bluetooth_disabled_profiles $have -> $want"
  return 0
}

# "cmd bluetooth_manager" only exists on Android 13+. Older versions print
# "No shell command implementation" and still exit 0, so pick by API level.
bt_power() {
  if [ "$API" -ge 33 ] 2>/dev/null; then
    cmd bluetooth_manager "$1" >/dev/null 2>&1 && return
  fi
  svc bluetooth "$1" >/dev/null 2>&1
}

restart_bt() {
  on=$(settings get global bluetooth_on 2>/dev/null)
  log "restarting bluetooth (was $on)"
  bt_power disable
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
    bt_power enable
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

# Everything needed to debug a device, in one file.
report() {
  out=/sdcard/Download/cabin-report.txt
  bp=$(bt_pkg)
  {
    echo "===== DEVICE"
    for p in ro.product.manufacturer ro.product.model ro.build.version.release ro.build.version.sdk \
      ro.board.platform ro.build.fingerprint; do
      echo "$p=$(getprop "$p")"
    done
    echo; echo "===== CABIN"
    cat "$MODDIR/module.prop" 2>/dev/null
    status
    echo; echo "===== OVERLAY"
    cmd overlay list --user 0 2>/dev/null | grep -i cabin
    echo "bluetooth_disabled_profiles=$(settings get global bluetooth_disabled_profiles 2>/dev/null) (stock: $(cat "$DATA/orig.disabled_profiles" 2>/dev/null))"
    for r in profile_supported_hfpclient hfp_client_connection_service_enabled \
      profile_supported_pbapclient profile_supported_mapmce profile_supported_hs_hfp; do
      echo "$r=$(cmd overlay lookup "$bp" "$bp:bool/$r" 2>/dev/null)"
    done
    echo; echo "===== BLUETOOTH COMPONENTS"
    dumpsys package "$bp" 2>/dev/null | grep -i -A2 'enabledComponents\|disabledComponents' | head -40
    dumpsys package "$bp" 2>/dev/null | grep -i 'client' | sort -u | head -40
    echo; echo "===== BLUETOOTH STATE"
    dumpsys bluetooth_manager 2>/dev/null | head -80
    echo; echo "===== CABIN APP"
    pm path com.cabin.bridge 2>&1
    dumpsys package com.cabin.bridge 2>/dev/null | grep -E 'versionName|privateFlags|granted=false' | head -20
    echo; echo "===== ROLES"
    cmd role get-role-holders android.app.role.DIALER 2>/dev/null
    cmd role get-role-holders android.app.role.SMS 2>/dev/null
    echo; echo "===== TELECOM ACCOUNTS"
    dumpsys telecom 2>/dev/null | grep -i 'PhoneAccount' | head -30
    echo; echo "===== CRASHES"
    logcat -b crash -d -v threadtime 2>/dev/null | tail -150
    dumpsys dropbox --print system_app_crash 2>/dev/null | grep -i -B4 -A40 'bluetooth\|cabin' | tail -200
    echo; echo "===== LOGCAT (Bluetooth / Cabin)"
    logcat -b all -d -v threadtime 2>/dev/null \
      | grep -i -E 'Cabin|HeadsetClient|HfpClient|MapClient|PbapClient|AndroidRuntime|bt_btif|FATAL|avc: denied.*(bluetooth|cabin)' \
      | tail -400
    echo; echo "===== CABIN LOGS"
    tail -n 60 "$DATA"/*.log 2>/dev/null
  } > "$out" 2>&1
  echo "Saved $out. Send that file."
}

notify_app() {
  if [ "$MEDIA_SINK" = 1 ]; then ms=true; else ms=false; fi
  am broadcast -f 0x20 -n "$PKG/.ConfigReceiver" -a com.cabin.bridge.CONFIG \
    --es mode "$MODE" --es last_on "$LAST_ON" --es call_audio "$CALL_AUDIO" \
    --ez media_sink "$ms" --es hfp_ag "$HFP_AG" --es modem "$(modem_kind)" \
    --es hfp_pcm_dev_id "$(hfp_pcm_dev_id)" >/dev/null 2>&1
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
  set_disabled_profiles && [ "$1" != restart ] && restart_bt
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
    HFP_AG=auto|HFP_AG=keep|HFP_AG=off) HFP_AG=$2 ;;
    CALL_HANDLER=auto|CALL_HANDLER=system|CALL_HANDLER=cabin) CALL_HANDLER=$2 ;;
    *) return 1 ;;
  esac
}

# Keys whose change needs Bluetooth to restart.
needs_restart() {
  case "$1" in
    MODE|MEDIA_SINK|HFP_AG|CALL_HANDLER) return 0 ;;
  esac
  return 1
}

status() {
  echo "mode:        $MODE (last on: $LAST_ON)"
  echo "call audio:  $CALL_AUDIO"
  echo "media sink:  $MEDIA_SINK"
  echo "hfp ag:      $HFP_AG"
  echo "calls via:   $CALL_HANDLER ($(cabin_handles_calls && echo cabin || echo system))"
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
    patch_mixer_paths
    ;;
  boot)
    save_mixer_ctls
    was_on=$(settings get global bluetooth_on 2>/dev/null)
    set_components
    set_disabled_profiles && restart_bt
    # Switching a Bluetooth component makes Android kill Bluetooth mid-startup, and it
    # can come back saved as "off". Turn it back on if it was on before.
    if [ "$was_on" = 1 ]; then
      sleep 5
      [ "$(settings get global bluetooth_on 2>/dev/null)" = 1 ] || { log "bluetooth came back off; turning it on"; bt_power enable; }
    fi
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
      MODE|CALL_AUDIO|MEDIA_SINK|HFP_AG|CALL_HANDLER) ;;
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
  save-mixer)
    save_mixer_ctls
    ;;
  sap-report)
    sap_report
    ;;
  report)
    report
    ;;
  status|"")
    status
    ;;
  *)
    sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac
