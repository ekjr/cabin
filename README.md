# Cabin

A Magisk / KernelSU / APatch module that makes an Android tablet (or any Android device) act like a
**Bluetooth car kit** for your phone. Pair the phone and:

- incoming calls ring in the tablet's **own Phone app**: answer, decline, hang up and dial out from there
- texts land in the tablet's **own Messages app**, and replies go out through the phone
- contacts sync over so calls show names
- it can stay connected while the phone (or the tablet) runs **Android Auto**

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
| `CALL_AUDIO` | `tablet` (default) / `phone` | Where you hear the call. Use `phone` when the phone is also on a car's Android Auto, so call audio stays in the car. |
| `MEDIA_SINK` | `0` (default) / `1` | Also play the phone's music on the tablet (A2DP sink). Keep `0` with Android Auto. While on, Bluetooth headphones on the tablet won't work. Android 13+ only. |
| `MIC_BOOST` | `12` (default) / `0` / `6` / `18` / `24` | Extra mic gain in dB during car-kit calls (Qualcomm). They skip the voice processing that normally boosts the mic, so callers can hear you quietly. Applies immediately. |
| `HFP_MIC` | `single` (default) / `dual` | Earpiece car-kit calls: use only the main (bottom) mic, or the phone's dual-mic pair. Dual-mic needs the voice processor, which car-kit calls skip, so single usually sounds cleaner. Speakerphone keeps its own mic. |
| `CALL_HANDLER` | `auto` (default) / `system` / `cabin` | Which service puts calls into the Phone app. `auto` uses Cabin's on Android 12 and older. |
| `HFP_AG` | `auto` (default) / `keep` / `off` | The device's own role as a phone toward Bluetooth headsets and car head units (Android Auto needs it). `auto` and `keep` leave it on alongside Cabin; `off` turns it off while Cabin is on. Try `off` if your phone won't connect "for calls" or Bluetooth is unstable. |

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

## Android Auto

There are two setups, and both work in HFP mode:

- **The tablet runs a head-unit app** (Headunit Reloaded and similar). The phone's HFP link to the
  tablet is the same link wireless Android Auto expects, and Cabin doesn't touch Wi-Fi or USB.
  Leave `MEDIA_SINK=0` so music stays in Android Auto.
- **The phone is also connected to a car's Android Auto.** The phone needs to allow two hands-free
  devices at once: Developer options → *Maximum connected Bluetooth audio devices* → 2 or more.
  Set `CALL_AUDIO=phone` so the car keeps call audio, while the tablet still shows and controls calls and texts.

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
  patched copies. If a device still has no sound, use `CALL_AUDIO=phone` and send `cabin report`.
- **Calls go through Cabin's own call service on Android 12 and older** (`CALL_HANDLER=auto`): the
  Bluetooth app's built-in one ignored the Answer button there. `CALL_HANDLER=system` switches back.
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

## Extras

- **`btsco_wb_fix`** (`extras/btsco-wb-fix`): a separate, standalone module for a Qualcomm vendor
  typo that can break Bluetooth HD-voice audio in the phone's own headset routes. Not part of
  Cabin. Released as `btsco_wb_fix-v*.zip`; see its README.

## Build

Needs JDK 11+, Android build-tools (`aapt2`, `d8`, `zipalign`, `apksigner`) and an API 34 `android.jar`:

```sh
ANDROID_JAR=$ANDROID_HOME/platforms/android-34/android.jar ./build.sh
# -> build/Cabin-v2.0.0.zip
```

The first build creates `signing/cabin.jks`, which is git-ignored. Keep it, and for CI put it in the
`CABIN_KEYSTORE_B64` secret (base64), so upgrades are signed with the same key.
