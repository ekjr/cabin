#!/system/bin/sh
# Runs before Bluetooth starts: save the stock Bluetooth props, then apply the saved mode.
MODDIR=${0%/*}
sh "$MODDIR/cabin.sh" boot-props
