#!/system/bin/sh
# Magisk / KernelSU / APatch "Action" button: turn Cabin on or off.
MODDIR=${0%/*}
sh "$MODDIR/cabin.sh" toggle
echo
sh "$MODDIR/cabin.sh" status | head -4
