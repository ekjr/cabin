#!/system/bin/sh
# Magisk/KernelSU install script.
SKIPUNZIP=0
DATA=/data/adb/cabin

ui_print " "
ui_print "  Cabin - your phone's calls and texts on this device"
ui_print " "

if [ "$API" -lt 26 ]; then
  abort "  Android 8.0 or newer is required."
fi

# Android 13+ turns profiles on with system properties; the resource overlay is only for 12 and older.
if [ "$API" -ge 33 ]; then
  rm -rf "$MODPATH/system/product/overlay"
fi

choose() {
  # Vol+ returns 0, Vol- returns 1, nothing for 10s returns 2.
  start=$(date +%s)
  while true; do
    timeout 1 getevent -lqc 1 2>/dev/null > "$TMPDIR/events"
    if grep -q 'KEY_VOLUMEUP *DOWN' "$TMPDIR/events"; then return 0; fi
    if grep -q 'KEY_VOLUMEDOWN *DOWN' "$TMPDIR/events"; then return 1; fi
    [ $(( $(date +%s) - start )) -ge 10 ] && return 2
  done
}

mkdir -p "$DATA"
if [ -f "$DATA/config" ]; then
  # v2.0.0/2.0.1 defaulted HFP_AG to keep; auto is the safe default now.
  sed -i 's/^HFP_AG=keep$/HFP_AG=auto/' "$DATA/config"
  ui_print "  Keeping your current settings:"
  sed 's/^/    /' "$DATA/config" | while read -r line; do ui_print "$line"; done
else
  ui_print "  How should this device reach your phone?"
  ui_print "    Vol+  Hands-free (HFP) - recommended, works with any phone"
  ui_print "    Vol-  SIM Access (SAP) - needs a cellular tablet, experimental"
  ui_print "    (HFP is picked after 10 seconds)"
  mode=hfp
  choose
  [ $? -eq 1 ] && mode=sap
  ui_print "  -> $mode"
  printf 'MODE=%s\nLAST_ON=%s\nMEDIA_SINK=0\nHFP_AG=auto\nCALL_HANDLER=auto\n' "$mode" "$mode" > "$DATA/config"
fi

# Save the kernel's mixer controls now, while the system is up; the audio fixes at the
# next boot need them before the sound card is ready.
mkdir -p "$DATA"
CABIN_DATA=$DATA sh "$MODPATH/cabin.sh" save-mixer >/dev/null 2>&1

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/system/bin/cabin" 0 2000 0755
for f in cabin.sh cabind.sh action.sh service.sh post-fs-data.sh uninstall.sh; do
  set_perm "$MODPATH/$f" 0 0 0755
done

ui_print " "
ui_print "  After reboot:"
ui_print "   1. Open Bluetooth on your phone and pair with this device."
ui_print "   2. Allow calls, contacts and messages when the phone asks."
ui_print "   3. Turn on/off: the Action button here, the Cabin quick"
ui_print "      settings tile, the Cabin app, or 'su -c cabin off'."
ui_print " "
