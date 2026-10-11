#!/system/bin/sh
# Some Qualcomm vendor mixer_paths files set "Internal BTSCO SampleRate" to "16000" (or
# "8000"), but that control is an enum (BTSCO_RATE_8KHZ / BTSCO_RATE_16KHZ). The value is
# rejected, so HD-voice (mSBC) Bluetooth audio can run at the wrong rate: garbled, chipmunk
# or silent. Write corrected copies of affected files for Magisk to mount over the stock
# ones. Runs before modules are mounted, so it always reads the stock files.
MODDIR=${0%/*}
out=$MODDIR/system/vendor/etc
rm -f "$out"/mixer_paths*.xml

# True when another enabled module also overlays this file: Magisk can't mount one file
# from two modules, so leave it to that module rather than fight over it.
taken_by_other() {
  for m in /data/adb/modules/*/; do
    m=${m%/}
    [ "$m" = "$MODDIR" ] && continue
    [ -f "$m/disable" ] || [ -f "$m/remove" ] && continue
    [ -f "$m/system/vendor/etc/$1" ] && return 0
  done
  return 1
}

for f in /vendor/etc/mixer_paths*.xml; do
  [ -f "$f" ] || continue
  taken_by_other "${f##*/}" && continue
  # Only when the file itself shows the enum names are what this control takes.
  grep -q '<ctl name="Internal BTSCO SampleRate" value="BTSCO_RATE_' "$f" || continue
  grep -qE '<ctl name="Internal BTSCO SampleRate" value="(16000|8000)"' "$f" || continue
  mkdir -p "$out"
  sed -e 's|<ctl name="Internal BTSCO SampleRate" value="16000"|<ctl name="Internal BTSCO SampleRate" value="BTSCO_RATE_16KHZ"|g' \
      -e 's|<ctl name="Internal BTSCO SampleRate" value="8000"|<ctl name="Internal BTSCO SampleRate" value="BTSCO_RATE_8KHZ"|g' \
      "$f" > "$out/${f##*/}"
  chmod 0644 "$out/${f##*/}"
  ctx=$(ls -Z "$f" 2>/dev/null | awk '{print $1}')
  [ -n "$ctx" ] && chcon "$ctx" "$out/${f##*/}" 2>/dev/null
done
