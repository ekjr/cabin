# BT SCO wideband sample-rate fix

A tiny Magisk/KernelSU module for Qualcomm devices whose vendor `mixer_paths*.xml` set
`Internal BTSCO SampleRate` to `16000` (or `8000`). That control is an enum
(`BTSCO_RATE_8KHZ` / `BTSCO_RATE_16KHZ`), so the value is rejected and Bluetooth HD-voice
(mSBC) audio can run at the wrong rate: garbled, chipmunk-sounding or silent. Seen on an
Android 11 msm8937 phone in the Wi-Fi-calling, recording and "switch to HD" Bluetooth routes.

At boot it writes corrected copies of only the affected files and Magisk mounts them over the
stock ones; nothing on `/vendor` is modified, and removing the module undoes it. It only
changes files that already use the enum names elsewhere, so the fix is known to match.

It only matters if HD voice actually connects; if your Bluetooth chip can't do mSBC, calls stay
at standard quality either way.

**Other modules that replace the same files:** Magisk can't mount one file from two modules, so
for any `mixer_paths` file another enabled module already replaces, this module leaves that file
alone (that module's copy is used, without this fix).
