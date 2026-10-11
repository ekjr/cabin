#!/system/bin/sh
SKIPUNZIP=0
ui_print " "
ui_print "  BT SCO wideband sample-rate fix"
found=0
for f in /vendor/etc/mixer_paths*.xml; do
  grep -qE '<ctl name="Internal BTSCO SampleRate" value="(16000|8000)"' "$f" 2>/dev/null && found=1
done
if [ $found = 1 ]; then
  ui_print "  This device has the typo; it will be fixed after reboot."
else
  ui_print "  This device doesn't have the typo; nothing will change."
fi
ui_print " "
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
