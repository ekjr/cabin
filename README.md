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
| `HFP_AG` | `auto` (default) / `keep` / `off` | The device's own headset-side role (its Bluetooth headsets, car). `auto` turns it off while Cabin is on for Android 12 and older, because those stacks can't run both roles, and keeps it on Android 13+. `off` forces it off; try that if the phone won't connect "for calls". |

## Install

1. Build it (below) or download `Cabin-v*.zip` from the GitHub Actions artifacts.
2. Magisk → Modules → Install from storage → pick the zip. At the prompt, Vol+ selects HFP and Vol- selects SAP (HFP after 10 s).
3. Reboot.
4. On the phone, go to Bluetooth, pair with the tablet, and allow **calls, contacts and messages** (on Android phones also check "Text messages" under the device's settings).
5. Open the Cabin app once and accept its permissions.

## Android Auto

There are two setups, and both work in HFP mode:

- **The tablet runs a head-unit app** (Headunit Reloaded and similar). The phone's HFP link to the
  tablet is the same link wireless Android Auto expects, and Cabin doesn't touch Wi-Fi or USB.
  Leave `MEDIA_SINK=0` so music stays in Android Auto.
- **The phone is also connected to a car's Android Auto.** The phone needs to allow two hands-free
  devices at once: Developer options → *Maximum connected Bluetooth audio devices* → 2 or more.
  Set `CALL_AUDIO=phone` so the car keeps call audio, while the tablet still shows and controls calls and texts.

## SAP mode, honestly

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

- **Call audio on the tablet depends on its audio HAL.** Some tablets connect the call but play no
  sound, because the vendor never wired up hands-free audio. If that happens, use `CALL_AUDIO=phone`
  and talk on the phone or car while you control the call from the tablet.
- The tablet needs a **dialer** and an **SMS app**. Wi-Fi tablets often ship without them; the AOSP
  Dialer and Messaging apps or QKSMS/Fossify work. Some SMS apps refuse to compose without a SIM.
- On Android 13+ the system's own hands-free calling account usually handles calls, and Cabin keeps
  it enabled. On older ROMs without it, Cabin registers its own calling account as a fallback.
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

## Build

Needs JDK 11+, Android build-tools (`aapt2`, `d8`, `zipalign`, `apksigner`) and an API 34 `android.jar`:

```sh
ANDROID_JAR=$ANDROID_HOME/platforms/android-34/android.jar ./build.sh
# -> build/Cabin-v2.0.0.zip
```

The first build creates `signing/cabin.jks`, which is git-ignored. Keep it, and for CI put it in the
`CABIN_KEYSTORE_B64` secret (base64), so upgrades are signed with the same key.
