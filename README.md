# Kindle Remote (Paco)

An Android app that turns the phone into a Bluetooth page turner for a stock (not jailbroken) Kindle. Volume down goes to the next page and volume up goes back.

## How it works

Kindle firmware ignores ordinary Bluetooth keyboards and page turners. It only accepts HID input from devices that identify as Amazon's own "Kindle Remote" accessory: BLE HID over GATT with vendor ID `0x0171` and product ID `0x0443`. The app runs a BLE GATT server that presents that identity:

- Advertising includes the HID service `0x1812` and manufacturer data `71 01 04 43`, which the Kindle's scan filter looks for.
- The Device Information service reports PnP ID `02 71 01 43 04 00 01`.
- The HID service exposes a standard keyboard report. Page Down (`0x4E`) means next and Page Up (`0x4B`) means previous.

The protocol was reverse-engineered by [kindle-curie](https://github.com/tsuuraifluens/kindle-curie), which verified it on an ESP32 talking to a Paperwhite 12th gen on firmware 5.20.1.

## Install

Each push builds a debug APK in GitHub Actions. Open the latest "Build APK" run, download the `kindle-remote-apk` artifact and unzip it. Copy `app-debug.apk` to the phone and open it to install (allow installs from unknown sources when asked).

You can also build it yourself with `./gradlew assembleDebug`, which needs the Android SDK.

## Use

1. Open the app and tap **Start**. Grant the Bluetooth and notification permissions.
2. On the Kindle, go to **Settings → Bluetooth** (on some firmware it is under **Bluetooth & Wireless**) and tap **Pair new device**. Pick the phone from the list. It shows up under the phone's Bluetooth name.
3. Accept the pairing prompt on the phone if one appears.
4. Use the volume buttons, or the on-screen buttons, to turn pages.

The app keeps running in the background with a notification, and it has a Stop action. While the app is on screen it keeps the display awake, and the volume keys always work. When it is in the background or the phone is locked, it catches the volume keys through a media session. That is best effort, and it may not work with the screen off.
