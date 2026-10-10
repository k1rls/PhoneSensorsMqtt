# Phone Sensors MQTT

Android app that publishes phone diagnostics to Home Assistant over MQTT. Minimum Android version: 4.0 (API 14); the Samsung Galaxy J1 SM-J120H on Android 5.1.1 is within the supported API range.

## App icon

The launcher icon is the custom phone-and-signal design in `PhoneSensorsMQTT-logo.png`; density-specific versions are bundled in the Android app.

## Languages and help

The app interface has English, Ukrainian, and Russian text. The Russian-language menu item is labeled `кацапська мова`, as requested. Choose a language in the selector at the top of the app. Use **Help / Довідка / Помощь** for setup steps. The device name field starts with the phone's detected manufacturer and model and remains editable.

## Sensors

- Battery percentage, temperature, voltage, health, charging state, power source, and chemistry
- Wi-Fi connection, SSID (when Android permits access), and signal strength
- Active network type, connection state, and metered status
- Device model, Android version, available and total RAM and internal storage, uptime, and screen state
- Built-in sensor inventory plus live readings from available accelerometer, gyroscope, magnetic field, gravity, linear acceleration, orientation, light, proximity, pressure, ambient temperature, and humidity sensors

The app publishes Home Assistant MQTT Discovery configuration and retained sensor state, reconnects after connection loss, and can resume after reboot when reporting was enabled. It does not access contacts, accounts, calls, SMS/MMS, camera, microphone, or personal profile data.

## Install the APK

Download [`PhoneSensorsMqtt-v2.0.apk`](releases/PhoneSensorsMqtt-v2.0.apk) from this repository. Copy it to the phone, allow installation from that file manager when Android asks, and install it. The APK is signed with a debug key and is suitable for personal installation; it is not a Play Store release.

## Configure

1. Open **Phone Sensors MQTT** and select a language.
2. Enter the MQTT broker host/IP (not the Home Assistant web address), port (1883 for plain MQTT or 8883 for TLS), and optional username/password.
3. The device name is filled from the phone model; edit it if you want another name.
4. Set the update interval and tap **Save & Start**. Open **Help** in the app for the same instructions.
5. Allow location access if prompted. Android 6+ requires it for Wi-Fi SSID visibility; signal and connection state can still be reported if SSID is hidden.
6. Ensure the Home Assistant MQTT integration uses the same broker. MQTT Discovery is enabled by default.

A foreground notification keeps reporting active in the background. On Samsung firmware, disable battery optimization for this app if Android stops it. TLS uses Android's built-in trusted certificates; custom or self-signed broker certificates are not supported in this version.

## Build from source

Open this folder in Android Studio with Android SDK Platform 36 installed and build the `app` debug APK. The project uses Android Gradle Plugin 8.13.2, minSdk 14, and targetSdk 28.

The supplied APK was assembled and signature-verified in the build environment. The v2.0 release notes report that it was tested on a Samsung Galaxy J1 SM-J120H.
