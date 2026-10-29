# Cabin

A Magisk / KernelSU / APatch module that makes an Android tablet (or any Android device) act like a
**Bluetooth car kit** for your phone. Pair the phone and:

- incoming calls ring in the tablet's **own Phone app**: answer, decline, hang up and dial out from there
- texts land in the tablet's **own Messages app**, and replies go out through the phone
- contacts sync over so calls show names
- the device's own Bluetooth headsets and car stay usable; Cabin's calls stay on the device itself

Two ways to reach the phone:

| Mode | How it works | Needs |
|---|---|---|
| **Hands-free (HFP)**, default | The tablet becomes the HFP "hands-free" side (like a car). Calls go through the phone's SIM; texts use MAP; contacts use PBAP. | Any phone. Tablet needs a dialer app and an SMS app. |
| **SIM Access (SAP)**, experimental | The tablet borrows the phone's SIM over Bluetooth, and its own modem uses it. | A **cellular** tablet with a remote-SIM capable modem, plus a phone that shares its SIM over SAP (see below). |

## Turning it on and off

Pick whichever is handiest. Every switch below changes the same setting.

- **Magisk app → Modules → Cabin → Action** (the ▶ button) toggles on/off. The module description shows the current state.
- **Quick Settings tile "Cabin"**: add it from the QS editor. Tap to toggle; long-press opens the app.
- **Cabin app**: an on/off switch, the HFP/SAP choice, call-audio and media options.
- **Shell**: `su -c cabin on|off|toggle|hfp|sap|status`

Turning it off restores the stock Bluetooth properties and restarts Bluetooth, so no reboot is
needed. Bluetooth drops for about 5 seconds whenever you switch.

Other settings (`su -c cabin set KEY VALUE`):

| Key | Values | Meaning |
|---|---|---|
| `MEDIA_SINK` | `0` (default) / `1` | Also play the phone's music on this device (A2DP sink). While on, Bluetooth headphones on this device stop working. Android 13+ only. |
| `MIC_BOOST` | `12` (default) / `0` / `6` / `18` / `24` | Extra mic gain in dB during car-kit calls (Qualcomm). They skip the voice processing that normally boosts the mic, so callers can hear you quietly. Applies immediately. |
| `HFP_MIC` | `single` (default) / `dual` | Earpiece car-kit calls: use only the main (bottom) mic, or the phone's dual-mic pair. Dual-mic needs the voice processor, which car-kit calls skip, so single usually sounds cleaner. Speakerphone keeps its own mic. |
| `RELAY` | `off` (default) / `media` / `call` | **Experimental.** Bring the caller's voice to a Bluetooth headset connected to this device. See below; on the tested phone neither mode gives a usable two-way call. |
| `EXPERIMENTS` | `none` (default) / names, comma separated | **Experimental.** Relay experiments to try with `RELAY=call` (or `media`). See "Relay experiments" below. |
| `CALL_HANDLER` | `auto` (default) / `system` / `cabin` | Which service puts calls into the Phone app. `auto` uses Cabin's on Android 12 and older. |
| `HFP_AG` | `auto` (default) / `keep` / `off` | This device's own role as a phone toward Bluetooth headsets and cars. `auto`/`keep` leave it on alongside Cabin; `off` turns it off while Cabin is on. Try `off` if your phone won't connect "for calls". |

## Install

1. Build it (below) or download `Cabin-v*.zip` from the GitHub Actions artifacts.
2. Magisk → Modules → Install from storage → pick the zip. At the prompt, Vol+ selects HFP and Vol- selects SAP (HFP after 10 s).
3. Reboot.
4. On the phone, go to Bluetooth, pair with the tablet, and allow **calls, contacts and messages** (on Android phones also check "Text messages" under the device's settings).
5. Open the Cabin app once and accept its permissions.

## Texts on Android 11 and older (LSPosed)

Android 11's Bluetooth app hands texts received from the phone only to the default SMS app, which
ignores them, and SMS apps can't send without a SIM. Cabin fixes both with a small **LSPosed**
module (it is one; no extra install):

1. Install LSPosed (Zygisk) if you haven't.
2. LSPosed → Modules → **Cabin** → enable, and tick **Bluetooth** and your **Messages** app
   (`com.android.messaging` or Google Messages). Turn on "Show system apps" if they're hidden.
3. Reboot, or restart Bluetooth and the Messages app.

Then texts from your phone appear in the stock Messages app with a notification, and replies typed
there go out through your phone. Only texts are handed to Cabin, and only when the device has no
working SIM of its own.

## Bluetooth headsets and cars connected to this device

They keep working for this device's own audio. Cabin's calls can't use them: the call's audio
arrives from your phone over its own Bluetooth link, and these devices can't pass it on to a
second one, so Cabin keeps its calls on this device's earpiece. For call audio in a car, connect
your phone to the car.

**Experimental: `RELAY`.** Two ways to bring the call to a headset on this device anyway:

- `call`: once the phone's call link is up, Cabin lets the call move to the headset (pick it on
  the call screen). On the tested Qualcomm phone the Bluetooth chip then runs both call links:
  the headset hears the caller, but what this device sends back to the phone turns into clicking
  and static, so the caller can't hear you. The headset's link has to start second; the chip
  refuses the phone's link while the headset's is up.
- `media`: Cabin copies the caller's voice from the sound chip and plays it to the headset as
  music, while you talk into this device's mic (Qualcomm; needs root). On the tested phone the
  Bluetooth stack drops all headset music while a call link is open, so nothing is heard.

If calls start having static after trying these, tap **Reset Bluetooth** in the Cabin app.

### Relay experiments

Getting a headset on this device to carry the whole call (the caller's voice *and* your mic) is
unsolved on the tested phone: the phone's call link and the headset's call link share one Bluetooth
audio path. These switches try different ways around that. Turn on a few, make a call, and note which
(if any) gives a clean two-way call. They only run while `RELAY` is `call` or `media`, and none of
them is verified to work.

```
su -c cabin set RELAY call
su -c cabin set EXPERIMENTS cvsd,hs_connect     # or type the names into the Cabin app
su -c cabin set EXPERIMENTS none                # off again
```

| Name | What it tries |
|---|---|
| `cvsd` | Asks the headset's call link for narrowband (CVSD, 8 kHz) so both links use the same codec. |
| `pin8k` | Forces 8 kHz on the phone link's audio path (app parameter and `Internal BTSCO SampleRate`). |
| `pin16k` | Forces 16 kHz instead. Try `pin8k` *or* `pin16k`, not both. |
| `hs_connect` | Opens the headset's call link itself, 1.5 s after the phone's, instead of leaving it to Telecom. |
| `hs_vvc` | Same, using a "virtual voice call" (`startScoUsingVirtualVoiceCall`). |
| `hs_audiomgr` | Same, using Android's `startBluetoothSco`. |
| `late` | Waits 5 s instead of 1.5 s before the `hs_*` experiments open the headset's link. |
| `a2dp_keep` | Keeps un-pausing headset media during the call (helps `RELAY=media`). |
| `sw_up` | Records the headset's mic (or this device's mic if it has no call link) in the app and plays it into the phone link's transmit side as root (`tinyplay` into a free `MultiMedia` front end). Needs the microphone permission. |
| `sw_down` | The other half of a software bridge: root copies the phone link's audio (as `RELAY=media` does) and the app plays it into the headset's *call* link instead of its media link. Use with `sw_up` and an `hs_*` experiment. |
| `early` | Opens the headset's link after 0.3 s instead of 1.5 s. |
| `mute_local` | Mutes this device's own mic and earpiece loopback during the call, so a `sw_up` / `sw_down` bridge isn't doubled by the normal path. |
| `hs_commdev` | Picks the headset as Android's communication device (Android 12+ only). |
| `mode_comm` | Puts Android's audio in communication mode during the call. |
| `nudge` | Re-runs Cabin's audio route nudge once the headset's link is open. |
| `hfp_reassert` | Sends the phone link's HAL parameters (`hfp_enable`, rate, PCM device) again once the headset's link is open, in case the HAL dropped them. |
| `hfp_cycle` | Turns the phone link's HAL loopback off and on again at that point. |
| `mixer_reapply` | Root sets the `hfp-sco` mixer controls again at that point, in case the headset's link moved the Bluetooth audio port to another path. |

Each run saves the mixer, Bluetooth and log state when the phone's link opens, a few seconds later
and when it closes, in `/data/adb/cabin/relay-diag/`. To send them, tap **Save relay diagnostics**
in the app or run `su -c cabin relay-diag`; it writes `Download/cabin-relay-diag.tgz`. For the
Bluetooth packet log, turn on Developer options → Bluetooth HCI snoop log before the call.

## SAP mode, honestly

**Test SIM access** only checks that your phone will share its SIM: Cabin connects, reads the SIM's
ID, and hands it back (your phone shows "sharing SIM" and loses signal for those seconds). It does
not move your SIM onto this device.

Android has no built-in SAP *client*, so Cabin includes one (`SapClient.java`). It connects to the
phone's SIM Access server, reads the SIM's ATR and ICCID, then hands the SIM back. Use **Test SIM
access** in the app to check this. Two limits:

1. **The phone must share its SIM.** iPhones never do. Most Android phones ship with the SAP server
   off; on a rooted phone run `resetprop bluetooth.profile.sap.server.enabled true` and restart
   Bluetooth. While the tablet holds the SIM, the phone has no signal.
2. **The tablet's modem must accept a remote SIM.** That is a vendor interface (Qualcomm's is "UIM
   remote client"). The app and `su -c cabin sap-report` show what this device has, but handing the SIM to it is
   device-specific and **not implemented yet**. On a Wi-Fi-only tablet SAP can never work, so use HFP.

## Known limits (HFP)

Tested end to end on an Android 11 Qualcomm (msm8937) phone: calls ring in the native dialer;
answer, decline, hang up, earpiece and mute work.

- **Speakerphone depends on the kernel.** Cabin re-routes the call when you tap Speaker, but some
  kernels can only send Bluetooth call audio to the earpiece's port (on the tested phone the
  loudspeaker's `QUIN_MI2S_RX` port has no Bluetooth input). There the call stays on the earpiece.

- **Call audio depends on the audio HAL.** On Qualcomm HALs Cabin repairs what it can at boot:
  it adds the missing `voice-speaker-hfp` / `voice-speaker-mic-hfp` routes, swaps a car-kit route
  control the kernel doesn't have for one it does, adds the port's hostless switch, and tells the
  HAL which PCM device to open (`hfp_pcm_dev_id`). Stock files are never touched; Magisk mounts
  patched copies. If a device still has no sound, send `cabin report`.
- **Calls go through Cabin's own call service on Android 12 and older** (`CALL_HANDLER=auto`): the
  Bluetooth app's built-in one ignored the Answer button there. `CALL_HANDLER=system` switches back.
- **Static on calls, in both directions?** The Bluetooth link to the phone can get stuck after
  many failed call-audio setups. Tap **Reset Bluetooth** in the Cabin app (or
  `su -c cabin restart-bt`); it restarts Bluetooth in a few seconds and keeps pairings. If the
  static remains, also turn Bluetooth off and on on the phone.
- The device needs a **dialer**. Wi-Fi tablets often ship without one; the AOSP Dialer works.
- Android 12 and older: a static resource overlay (`CabinBtOverlay.apk`) makes the client profiles
  available, because those versions ignore the properties. Cabin turns them on and off by changing
  `bluetooth_disabled_profiles`, which the Bluetooth app reads at start.

## How it works

```
module/
  post-fs-data.sh  saves stock Bluetooth props, applies the saved mode before Bluetooth starts
  service.sh       grants app permissions, starts the root helper, enables the hands-free service
  cabin.sh         the control script (also /system/bin/cabin); config lives in /data/adb/cabin/config
  cabind.sh        root helper: writes texts into Messages and applies setting changes from the app/tile
  action.sh        Magisk Action button -> cabin toggle
app/               CabinBridge priv-app: link service, SMS bridge, fallback ConnectionService,
                   QS tile, SAP client, status screen
overlay/           Bluetooth config overlay for Android <= 12
```

Something not working? Run `su -c cabin report` and send `/sdcard/Download/cabin-report.txt`. It holds the device info, overlay and profile state, Bluetooth crashes and Cabin's logs.

## Related

- **[btsco-wb-fix](https://github.com/ekjr/btsco-wb-fix)**: a separate module for a Qualcomm
  vendor typo (`16000` instead of `BTSCO_RATE_16KHZ`) that can break Bluetooth HD-voice audio in
  a phone's own headset routes. Found while building Cabin; not part of it.

## Build

Needs JDK 11+, Android build-tools (`aapt2`, `d8`, `zipalign`, `apksigner`) and an API 34 `android.jar`:

```sh
ANDROID_JAR=$ANDROID_HOME/platforms/android-34/android.jar ./build.sh
# -> build/Cabin-v2.0.0.zip
```

The first build creates `signing/cabin.jks`, which is git-ignored. Keep it, and for CI put it in the
`CABIN_KEYSTORE_B64` secret (base64), so upgrades are signed with the same key.
