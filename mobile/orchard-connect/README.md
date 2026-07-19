# Orchard Connect

Native Android/Kotlin companion for controlling Orchard from a phone on the
same LAN. It requires Android 7.0 (API 24) or newer and does not use Expo,
React Native, or Node.js.

## Build a Debug APK

Install JDK 17 and an Android SDK with API 36, then run:

```bash
cd android
./gradlew assembleDebug
```

The APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

## Pair

1. Open Orchard desktop.
2. Go to **Settings → Orchard Connect**.
3. Scan the QR code with the app, or enter the desktop address and pairing token manually.
4. Approve the phone in Orchard.

The app stores the approved device token locally and reconnects to the last
desktop address. Both devices must be able to reach each other on the LAN.

## Release Builds

Production builds require `ANDROID_KEYSTORE_FILE`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`:

```bash
cd android
./gradlew assembleRelease
```
