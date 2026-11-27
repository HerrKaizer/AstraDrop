# AstraDrop

Send photos, videos, files and text between an Android phone and an iPhone, with nothing installed on the iPhone. Hold the iPhone against the back of the Android phone and Safari opens a page that the phone serves itself. No cloud, no account and no website. Everything moves directly over local Wi-Fi or the phone's own hotspot, at full quality.

## Features
- **Tap or scan.** The phone acts as an NFC tag that holds the link. You can also scan a QR code.
- **Both directions.** The iPhone can save what you share, and send its own photos, videos, files and text back.
- **Saving on the iPhone.**
  - One tap saves everything to Photos or Files.
  - ♥ marks favourites, and you can save only those.
  - Big ZIP downloads resume if they get interrupted.
- **Photos.**
  - Optional compression that keeps the date and location.
  - RAW (DNG) photos are converted to JPEG.
- **Network.**
  - Works on a shared Wi-Fi network.
  - Without one, it can start its own network or turn on your hotspot, and shows a Wi-Fi QR code for the iPhone.
- **One-time links.** Each tap or QR code lets in one device. You can require approval before a device joins, see what each device has downloaded, and remove a device.
- **History.** A list of everything the iPhone sent you, with Open, Share and Clear.
- **Automatic NFC (optional).** A small built-in helper, paired once over wireless debugging, turns NFC on while you share and off afterwards.
- **Other.** Light and dark themes, and 9 languages (English, Russian, German, Spanish, French, Italian, Portuguese, Polish, Chinese). The iPhone page can follow the iPhone's own language.

## Using it
1. Install the APK and open AstraDrop. Allow notifications, which keep sharing running in the background.
2. Add files in the app, or from any app use **Share → AstraDrop**.
3. Make sure both phones are on the same network, or let AstraDrop start one and scan its Wi-Fi QR code with the iPhone.
4. With AstraDrop open, hold the top of the iPhone against the back of the Android phone near the camera, then tap the banner. If the banner doesn't show, use the link QR code.

## Notes
- Both screens must be on. For background NFC reading, the iPhone's Camera and Wallet apps must be closed.
- Public or hotel Wi-Fi often blocks devices from seeing each other. Use your own network there.
- Saving straight to Photos needs the iPhone to be online once: the page switches to HTTPS using a public `local-ip.sh` certificate.

## Building
This is plain Gradle (AGP 8.7, Kotlin 2.0, no AndroidX). Its dependencies are ZXing core, libadb-android and Conscrypt.
```
gradle testDebugUnitTest assembleDebug
```
The output is `app/build/outputs/apk/debug/app-debug.apk`.

The font is Google Sans Flex, under the SIL Open Font License (`app/src/main/assets/fonts/GoogleSansFlex-OFL.txt`).
