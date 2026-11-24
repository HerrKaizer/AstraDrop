# TapDrop

Tap an iPhone on the back of your Android phone. Safari opens a page that the phone serves itself, and the iPhone can download your photos, videos and files. It can also send its own back to you. No app on the iPhone, no cloud and no website.

## How it works
- **Web server on the phone.** TapDrop runs a small HTTP server at `http://<your phone's local IP>:8765/<random code>/`. The random code changes when you press **New link**, which kills the old link.
- **The phone acts as an NFC tag.** TapDrop emulates a standard NFC Forum Type 4 tag (AID `D2760000850101`) holding a single URL record. iPhones read these in the background and show a banner, and tapping the banner opens Safari.
- **Files go directly** from phone to phone over local Wi-Fi or your hotspot, at full quality and full speed.

## Using it
1. Install `TapDrop.apk`. vivo will ask you to approve the install.
2. Open TapDrop and allow notifications, which keep it running in the background.
3. Add files in either of two ways: **Add photos/videos** in the app, or from Gallery use **Share → Send to iPhone (TapDrop)**.
4. Make sure the iPhone can reach your phone:
   - **Same Wi-Fi:** nothing to do.
   - **No shared Wi-Fi:** turn on your hotspot (the **Hotspot settings** button opens it). In the app, enter the hotspot name and password and tap **Show Wi-Fi QR**. The iPhone scans it with the Camera, once only. After that it rejoins your hotspot automatically.
   - Check **Network the iPhone uses** in the app. Pick *Hotspot* when using the hotspot, *Wi-Fi* otherwise.
5. With TapDrop **open on screen**, hold the top edge of the iPhone against the back of your phone. A banner appears on the iPhone. Tap it.
6. On the iPhone:
   - **Photo to Photos:** tap it, then touch and hold and choose *Save to Photos*.
   - **Video to Photos:** tap it, then Share and *Save Video*.
   - **Save** button: puts the file in Files → Downloads.
   - **Choose files:** sends iPhone photos and videos to you. They land in `Pictures/TapDrop` and `Movies/TapDrop`, and other files in `Download/TapDrop`.

## Notes / limits
- TapDrop has to be **open on screen** when you tap. That's how it takes priority over other apps that emulate the same kind of tag, such as Bada.
- Your phone's screen must be on. Some vivo builds also need it unlocked for NFC card emulation.
- The iPhone's screen must be on, and its Camera and Wallet apps must not be open, for background NFC reading.
- If the banner doesn't appear, use **Show link QR** and scan it with the iPhone Camera.
- Public or hotel Wi-Fi often blocks devices from seeing each other. Use your hotspot there.
- Anyone who has the link can download the shared files while sharing is on. Press **Stop sharing** or **New link** when you're done.

## Building
The project is plain Gradle (AGP 8.7, Kotlin 2.0) with one dependency, ZXing core for QR codes. Build with:
```
gradle assembleDebug
```
The output is `app/build/outputs/apk/debug/app-debug.apk`.
