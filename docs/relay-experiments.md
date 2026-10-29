# Relay experiments: lab notebook

Goal: two-way call audio through a Bluetooth headset connected to the Cabin device, while the
phone's call link is also open (the caller's voice in the headset, the headset's mic to the caller).

**Status: not working.** Every experiment so far ends the same way: the caller hears clicking and
the headset wearer hears nothing, their own voice, or (sometimes) the caller. This file records what
was tried, what the logs showed, and what is left, so the next attempt starts from here.

## Test setup

| | |
|---|---|
| Device | Cat S22 Flip (`S22FLIP`), Android 11 (`LTE_S02113.11`), Qualcomm msm8952 / QM215, 32-bit |
| Bluetooth chip | Qualcomm "pronto" (WCN36xx), `persist.vendor.qcom.bluetooth.soc=pronto`, stack `libbluetooth_qti.so` |
| Sound card | `msm8952-snd-card-mtp`; one internal Bluetooth call port (`INTERNAL_BT_SCO_RX/TX`) |
| Phone | Model not recorded; HFP audio gateway that prefers mSBC but ends up on narrowband CVSD |
| Headset | AirPods Pro (HFP, offers CVSD and mSBC) |
| Cabin | module 2.3.0 + `route_bt` build (`0b89cbb`), root via Magisk, LSPosed installed |

## How to run an experiment

```
su -c cabin set RELAY call
su -c cabin set EXPERIMENTS hs_connect,route_bt
su -c cabin restart-bt            # reconnect cleanly; skip this if you applied the runtime patch below
# call the tablet from the phone, answer on the tablet, talk both ways, hang up
su -c cabin relay-diag            # saves Download/cabin-relay-diag.tgz
su -c cabin set EXPERIMENTS none
```

Each run also writes mixer/Bluetooth/log snapshots to `/data/adb/cabin/relay-diag/` when the phone's
call link opens (`sco-up`), a few seconds later (`snap`) and when it closes (`sco-down`).
Turn on Developer options → Bluetooth HCI snoop log first.

### Reading the packet log (this device)

The snoop log is **not** `btsnoop_hci.log` (16 bytes, empty). The Qualcomm logger writes
`/data/misc/bluetooth/logs/hci_snoop<timestamp>.cfa`, which is plain btsnoop format:

```
btmon -t -r hci_snoop20261028221510.cfa
```

Useful things to look for: `Setup Synchronous` / `Accept Synchronous` (air coding: CVSD or
Transparent Data = mSBC), `Synchronous Connect Complete` (status), `AT+BAC`, `+BCS`, `AT+BCS`.
The phone's ACL handle and the headset's differ (e.g. 2 and 4); match them by address.

### Hands-free sweep (optional)

Runs one experiment set after another from a Linux PC with the tablet on `adb`; waits for you to take
each call and saves the diagnostics. Untested at scale; the call-ended detection relies on the
`relay snapshot sco-down` lines in `/data/adb/cabin/cabin.log`, which need the root helper running
(see "Pitfalls").

```bash
OUT=./relay-sweep; mkdir -p "$OUT"
rs() { adb shell "su -c \"$*\"" | tr -d '\r'; }
count() { local n; n=$(rs "grep -c $1 /data/adb/cabin/cabin.log" 2>/dev/null); echo "${n:-0}"; }
rs "cabin set RELAY call"
for e in pin8k pin16k hs_connect,route_bt; do
  rs "rm -f /data/adb/cabin/relay-diag/*"; rs "cabin set EXPERIMENTS $e"; rs "cabin restart-bt"
  read -rp "Reconnect phone + headset, then Enter. "
  down=$(count sco-down); echo "Take the call; press Enter after hanging up."
  while [ "$(count sco-down)" -le "$down" ]; do read -t 3 -r && break; done
  sleep 6; rs "cabin relay-diag" >/dev/null
  adb pull /sdcard/Download/cabin-relay-diag.tgz "$OUT/${e//,/+}.tgz" >/dev/null
  read -rp "How did it sound? " r; echo "$e: $r" >> "$OUT/summary.txt"
done
rs "cabin set EXPERIMENTS none"
```

## Experiment catalog

`EXPERIMENTS` is a comma-separated list; names are validated against `EXP_NAMES` in
`module/cabin.sh` and `Experiments.NAMES` in the app. They only act while `RELAY` is not `off`.

| Name | What it does | Tested | Result |
|---|---|---|---|
| `cvsd` | `BluetoothHeadset.disableWBS()` to ask the headset link for narrowband | yes | **Does nothing on Android 11**: the method does not exist (`NoSuchMethodException`); the framework has no WBS control at all. Same dead end as the reverted PR #3. |
| `pin8k` | Force 8 kHz: app `hfp_set_sampling_rate=8000` + mixer `Internal BTSCO SampleRate` | yes | Heard self (1st call, helper down); then heard caller, caller heard clicks. No fix. The mixer control is write-only on this kernel (reads `(null)`), so the write cannot be verified. |
| `pin16k` | Same at 16 kHz | yes | Heard self; caller clicks. |
| `hs_connect` | App calls `BluetoothHeadset.connectAudio()` 1.5 s after the phone's link | yes | Ran (`accepted=true`; the headset link opened after the phone's). Without route selection: heard nothing; caller clicks. |
| `route_bt` | Select the Bluetooth route (`setAudioRoute`) 1.5 s after the headset link | yes | With `hs_connect`: Telecom held the Bluetooth route ~26 s; heard nothing; caller clicks. |
| `hfp_reassert` | Re-send `hfp_pcm_dev_id`, rate and `hfp_enable=true` after the headset link | yes | With `hs_connect,route_bt,mixer_reapply`: no change (2 calls). |
| `mixer_reapply` | Root re-sets the `hfp-sco` mixer controls after the headset link | yes | Same run: no change. |
| `sw_down` | Root `tinycap` of the phone link's audio; the app plays it into the headset's call-link output | yes | Ran (`playing to S22 FLIP`, ~40 s). Heard self instead of the caller. |
| `sw_up` | App records the headset mic; root `tinyplay`s it into the phone link's TX via a free MultiMedia front end | yes | **Failed to start**: `AudioRecord` "uninitialized" (so nothing was injected). Caller still clicks. |
| `mute_local` | Mute the tablet's own mic and earpiece loopback volume | yes | With the bridge run: mic muted / volume 0 applied; no change. |
| `hs_vvc` | Open the headset link with `startScoUsingVirtualVoiceCall()` | no | |
| `hs_audiomgr` | Open it with `AudioManager.startBluetoothSco()` + `BT_SCO=on` | no | |
| `early` / `late` | Open the headset link after 0.3 s / 5 s instead of 1.5 s | no | Order phone→headset already works with `hs_connect`. |
| `a2dp_keep` | Keep un-pausing headset media (`A2dpSuspended=false`) | no | Only meaningful for `RELAY=media`, which the stack blocks during calls. |
| `hfp_cycle` | `hfp_enable=false` then `true` after the headset link | no | |
| `nudge` | Re-run Cabin's route nudge after the headset link | no | |
| `mode_comm` | `AudioManager.setMode(MODE_IN_COMMUNICATION)` for the call | no | |
| `voip_mode` | Mark the call as VoIP (`setAudioModeIsVoip(true)`) so Telecom itself uses `MODE_IN_COMMUNICATION` | see call log | `mode_comm` cannot work: Telecom overrides the mode ~70 ms later. |
| `hs_commdev` | `setCommunicationDevice()` to the headset | no | Android 12+ only; no-op on this Android 11 tablet. |

Also: `RELAY=call` alone (let Telecom route it; user picks Bluetooth by hand) and `RELAY=media` (documented
in the README, older work) fail as described there.

## Call log

All with `RELAY=call`. "Caller" = the person on the phone.

| # | Experiments | Headset wearer heard | Caller heard |
|---|---|---|---|
| 1 | `pin8k` (root helper down, so no snapshots) | own voice | not reported |
| 2 | `pin8k` | the caller | clicks |
| 3 | `pin16k` | own voice | not reported |
| 4 | `hs_connect` | nothing | clicks |
| 5 | `hs_connect,route_bt` | nothing | clicks |
| 6-7 | `hs_connect,route_bt,hfp_reassert,mixer_reapply` | same as 5 | same as 5 |
| 8 | `sw_down,sw_up,mute_local,hs_connect,route_bt` | own voice | clicks |
| 9 | `hs_connect,route_bt` + **runtime CVSD patch** (below) | nothing | clicks |

Results vary between calls with the same settings, which fits a shared audio path rather than a
single wrong setting.

## What the logs showed

**Hardware.** One internal Bluetooth call port. The sound card lists a single `INT_HFP_BT Hostless`
(pcm 17) and `AUXPCM Hostless`; the phone link is looped to the codec by the HAL's `hfp-sco` mixer
path (`INTERNAL_BT_SCO_RX Audio Mixer MultiMedia6`, `PRI_MI2S_RX Port Mixer INTERNAL_BT_SCO_TX`,
`HFP_*_UL_HL Switch`). These stay on after hang-up. There is no second SCO port in the mixer.

**Codecs (btsnoop).** The phone link is CVSD (air coding CVSD, `+BCS: 1`). The AirPods link is mSBC
(`Setup Synchronous`, air coding "Transparent Data", 60-byte packets, `+BCS: 2`). `AT+BRSF`/`+BRSF`
show codec negotiation supported on all three ends; the phone offers mSBC first (`+BCS: 2`) and falls
back to CVSD (`+BCS: 1`).

**Why the phone ends up CVSD.** The tablet answers the phone's mSBC request with
`Accept Synchronous` (air coding Transparent Data, max latency 8 ms, packet types `0x03c8`); the chip
returns `0x0e` "Connection Rejected due to Security Reasons" (while the AirPods mSBC link is up) or
`0x1f` "Unspecified Error" (even when it is not). The AirPods mSBC link, which the tablet *initiates*
with max latency 13 ms and packet types `0x0300`, succeeds on the same chip. Hypothesis (unverified): the
8 ms parameter set is the problem.

**Ordering.** The phone's link must come up first; with the AirPods link up first, the phone's link
fails (`0x0e`/`0x1f`) until the AirPods link is dropped. Telecom starts the AirPods link at ringing/answer
(before the phone's); Cabin vetoes the Bluetooth route until the phone's link is up
(`CabinConnectionService.relayToHeadset`), which drops that early link, then the phone link succeeds.
Phone first, AirPods second works and both links then stay up (13-40 s observed).

**Codec match does not fix it (test 9).** With the runtime patch below, the AirPods negotiated CVSD
(`AT+BCS=1`, `Setup Synchronous` air coding CVSD, max latency 12), so both links were CVSD. The caller
still heard clicks. The codec mismatch was real but is not the cause.

## Pitfalls we hit

- **App vs module version.** The module can say 2.3.0 while an older app APK installed in `/data/app`
  (e.g. 2.2.10) is what actually runs; it then ignores `EXPERIMENTS` entirely. Check
  `dumpsys package com.cabin.bridge | grep versionName`. Update with
  `pm install -r <CabinBridge.apk>` (same signing key as CI builds).
- **Root helper not running.** After a reboot a stale pid in `/data/adb/cabin/cabind.pid` could belong
  to an unrelated process, so `cabind.sh` was never started and every app→root request (relay snapshots,
  `sw_up`, diagnostics) sat unprocessed in `files/cabin-queue`. Fixed in `service.sh`, `cabin status` and
  `uninstall.sh` (commit `0b89cbb`): the pid is only trusted if `/proc/<pid>/cmdline` contains `cabind.sh`.
- **`Internal BTSCO SampleRate` reads `(null)`** in every `tinymix` dump even after a successful write.
- **`hfp_enable=true is unsupported`** in the HAL log: a repeat while the loopback runs is ignored, so
  `hfp_reassert` cannot restart it; `hfp_cycle` (false then true) is the way to try.
- **`sw_up` `AudioRecord` fails to initialize** on the headset SCO input during a call here.
- **Device shell arithmetic is 32-bit**: compute addresses on the PC and pass decimals.
- The MTP/gvfs path to the tablet is not visible to the CLI; use `adb pull`.

## Runtime patch: make the tablet pick CVSD for headsets (device-specific)

The stack in use is **`/system/system_ext/lib/libbluetooth_qti.so`**, not `/system/lib/libbluetooth.so`
(`ro.bluetooth.library_name`). In the AG code that handles the headset's `AT+BAC` (its codec list), the
reference AOSP source (`btif_hf`, `BTA_AG_AT_BAC_EVT`) is:

```c
if (btif_conf_hf_force_wbs == TRUE && (peer_codecs & BTA_AG_CODEC_MSBC))  BTA_AgSetCodec(handle, MSBC);
else                                                                       BTA_AgSetCodec(handle, CVSD);
```

On this build the flag is folded to true, so the test is a single branch: `lsls r1, r1, #0x1e; bmi.w`
at **vaddr `0x137b30`** (file offset `0x136b30`), bytes `00 f1 3d 81`. Replacing it with `NOP.W`
(`af f3 00 80`) makes the tablet always choose CVSD for headsets; the phone link (HF client) is
unaffected. Written into the running Bluetooth process only (nothing on disk); `cabin restart-bt`
or a reboot removes it. **Works on this exact build only** (verify the bytes first):

```bash
PB=$(adb shell 'su -c "P=\$(pidof com.android.bluetooth); echo \$P \$(grep libbluetooth_qti.so /proc/\$P/maps | head -1 | cut -d- -f1)"' | tr -d '\r')
set -- $PB; P=$1; BASE=$2; A=$(python3 -c "print(0x$BASE+0x137b30)")
adb shell 'su -c "dd if=/proc/'$P'/mem bs=1 skip='$A' count=4 2>/dev/null | od -An -tx1"'   # expect: 00 f1 3d 81
adb shell 'su -c "printf \"\\257\\363\\000\\200\" | dd of=/proc/'$P'/mem bs=1 seek='$A' conv=notrunc"'
# now reconnect the headset so it redoes its setup (do NOT restart Bluetooth)
# undo: write back \000\361\075\201, or restart Bluetooth
```

Confirmed from the snoop log that the AirPods then negotiate CVSD (call 9).

## Second goal: just the audio (caller to headset), using the tablet's own mic

The caller already hears the tablet's mic through the phone link, so only the caller's voice has to
reach the headset. That avoids a second call link: send it as music (A2DP) with `RELAY=media`
(root `tinycap` taps the phone link; the app plays it to the headset). Setup used:
`HFP_AG=off` (the headset can't open a call link and disturb the HAL), `RELAY=media`,
`EXPERIMENTS=voip_mode`.

What was established (all measured, mid-call):

- **A2DP is suspended by Android while the phone state is `IN_CALL`.** The headset's output thread
  showed `Suspend count: 1` / thousands of suspended frames, and the stream stayed `STANDBY` for the
  whole call. The app's `AudioManager.setParameters("A2dpSuspended=false")` cannot override it
  (that goes to the HAL; the suspend is applied by the audio policy).
- **`mode_comm` cannot fix it**: Telecom sets `IN_CALL` ~70 ms after the app sets
  `IN_COMMUNICATION`. **`voip_mode` does**: with `setAudioModeIsVoip(true)` the policy dump shows
  `AUDIO_MODE_IN_COMMUNICATION` during the call, suspend count 0, and the output thread writing.
- **The capture is good**: speech-shaped audio, peaks ~12000 of 32767.
- **The level was far too low**: ~30 dB below the 440 Hz test tone that was clearly audible
  (-12 dB at the headset output vs -25..-50 dB bursts for speech). Gain went 1x → 8x → 24x with a limit.
- **The AVDTP stream to the headset is started ~1 s after answering and stays up for the call**
  (btsnoop `AVDTP Start`/`Suspend`), yet the wearer heard nothing until after hang-up, then "something".
  Not resolved. Leading suspects: the stack/controller throttling A2DP while the phone's eSCO link is
  active (this device's snoop does not record outgoing A2DP media packets, so it could not be
  measured), or the headset itself. The next test queued for this was injecting the 440 Hz tone
  mid-call; it was not completed because the tablet's Bluetooth got stuck first.

Bugs found and fixed on the way (all in the media-relay path):

- The relay only stopped when the phone's audio link reported closed, which lags 20+ s after
  hang-up. It now also stops when the call terminates, and `relay_stop` kills the capture by name
  (`tinycap.*relay.pcm`), so a lost pid file can't leave it holding the port.
- The phone's audio link can report *connected* after the call is over; that restarted the relay with
  nothing left to stop it (orphaned `tinycap`). The app now ignores it when no call is active.
- Experiment snapshot jobs queued ahead of the relay's own start request in the root helper and
  delayed it 4-5 s; they are now only queued for experiments that use root.
- `mode_comm` restored a stale saved mode after the call, leaving the audio policy in `IN_CALL`.

### More pitfalls

- **An orphaned `tinycap` holds the call's audio port** and silences later calls. Check
  `ps -A | grep tinycap` after any media-relay run.
- **Stuck HF-client audio state.** After media-relay runs the Bluetooth app sometimes stays in
  `HeadsetClientStateMachine ... state=AudioOn` / `mAudioState: 2` with no call; new calls then never
  reach the tablet. `cabin restart-bt` clears it (`dumpsys bluetooth_manager | grep -A3 HeadsetClientService`).
- **`bta_hf_client_sco_create: Index 0x0000 already in use`** in logcat marks the same stuck state.
- **`cabin refresh-audio` restarts the audio HAL**; it also "fixed" a no-audio state once, which
  confounded an app-version test. Change one thing at a time.
- **The Gabeldorsche toggle** (Developer options) restarts Bluetooth; it was turned on by mistake once
  during testing. Leave it off.
- A headset connected as an audio-gateway client makes Android try to open a call link to it at the
  start of every call (even vetoed by Cabin), which can leave the HAL's loopback half-started. Use
  `HFP_AG=off` for music-only tests.
- The first call after any Bluetooth restart has been unreliable; do a throwaway call first.

## Ideas not yet tried

- **Make the phone link mSBC instead** (better audio, both links 16 kHz): change the parameters the stack
  uses when *accepting* the phone's mSBC link (8 ms / `0x03c8` → the 13 ms / `0x0300` set that the
  AirPods link uses). The parameter source was not found as a data table in `libbluetooth.so`; it is
  likely built in code (`bta_ag_sco_conn_rsp` / `btm_sco`). Needs reading ARM disassembly.
- **Per-link PCM slot / SCO routing** in the Qualcomm controller (vendor HCI commands). Other
  controllers (TI, Infineon, u-blox) assign a PCM slot per SCO link and have no mixer; nothing public
  found for pronto.
- **SCO over HCI for the headset link** (host gets raw frames and can transcode/route in software).
  Needs a per-link data path; the stock stack uses one global route.
- **USB Bluetooth dongle** for the headset (HCI-only, so call audio reaches software), with a
  userspace stack on a rooted Android; heavy but the one route that avoids the chip's shared PCM.
- **CVSD-only headset test:** isolates the chip limit from AirPods-specific behavior.
- **LE Audio** headset (separate path, not HFP SCO) on a newer Android.
- Finish the A2DP investigation: inject the 440 Hz test tone mid-call (stop `tinycap`, append the tone
  to `files/relay.pcm`) to separate "A2DP can't be heard during a call" from "the captured audio is
  too quiet"; try a second Bluetooth speaker/headphones to rule out the AirPods.
- Untested catalog entries above (`hs_vvc`, `hs_audiomgr`, `early`, `late`, `hfp_cycle`, `nudge`,
  `mode_comm`).
- Fix `sw_up` (find an `AudioRecord` configuration that initializes during a call) so the software
  uplink can be tested properly.

## Research notes (outside sources)

- AOSP `btm_sco.cc` (Android 11): one global SCO route; one AG SCO state machine.
- BTstack maintainer on relaying SCO between two links: "doesn't work in general with any Bluetooth
  Controller" (https://github.com/bluekitchen/btstack/issues/606).
- TI forum: no SCO mixer; each connection gets its own channel (PCM slot).
- Reference `btif_hf` used for the patch analysis:
  https://android.googlesource.com/platform/system/bt/+/e7d35076fc146a1213b9683e3b8ba57e04631fb9/btif/src/btif_hf.c
