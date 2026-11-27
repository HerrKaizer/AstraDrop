# AstraDrop UI plan, v2

Reference user: found AstraDrop on Reddit, knows only "moves files between Android and iPhone".
They have never heard of NFC, Wireless debugging or local hotspots, and shouldn't need to.

## What went wrong in 2.0.0 (from the screenshots of 27 Nov)
| Problem | Cause | Fix |
|---|---|---|
| First thing you see: a big "Turn on NFC" button | NFC was treated as a blocker. It isn't one: the QR code works without it | NFC becomes one of two ways to connect, never a gate |
| "Turn on NFC" opened an **empty** NFC sheet on vivo | vivo's Settings ships the NFC panel with no switch in it | Open the NFC settings page (it always has the switch) |
| Its "Settings" button went to Connection preferences | Same vivo panel | Same fix |
| "Okay / What code? / I did" in the pairing notification | Android's auto-generated smart replies (notification marked as a message) | Mark it as a plain notification and turn generated replies off |
| Text from the page shows through pop-ups | Pop-up glass is too clear when the system blur is weak | Pop-ups get a near-solid background |
| Two network ideas (Wi-Fi / "Create a network" / own hotspot) | Three ways to do one thing | One **Hotspot** option (below) |
| Networks that aren't on disappear | Chips were built from live addresses only | Wi-Fi and Hotspot are always shown, greyed out when off, and tappable |
| Pairing never finished, nothing said why | No feedback in the app while pairing | The setup screen shows each stage live |

## New first run: a short tour (like SolCube's, animations drawn in code)
1. **Send photos to any iPhone.** Photos fly from the Android phone to an iPhone. "No app on the iPhone, no cloud."
2. **Tap, or scan.** The iPhone touches the back of the phone, and a banner pops up. Or it scans a QR code.
   "Tapping uses NFC. Scanning always works."
3. **Same Wi-Fi, or your hotspot.** Both phones join one network, and files go straight across at full quality.
4. **It works both ways.** The iPhone sends photos back, and they land in your Gallery.
5. **Optional: Auto mode.** "AstraDrop can switch NFC and your hotspot on and off for you. One-time setup." Choices: **Set up** or **Maybe later**.
Then permissions, explained one at a time (notifications: "so sharing keeps running").

## Main screen
- **Status card**: what's going on, in plain words: "Ready. Tap or scan to connect."
  It has two "way" tiles side by side:
  - **Tap**: on, or "NFC is off, tap to turn on" (opens the NFC page, or turns NFC on by itself in Auto mode).
  - **Scan**: always works, opens the QR code.
  No big gate button. **New link** stays as a small pill.
- **Sharing on/off is removed.** Sharing runs while AstraDrop is open, and stops when you swipe it away (as before).
  "Stop" only ever meant "kill the link", and New link does that. The notification keeps its Stop action.
- **Network card** ("Both phones on…"): two chips, always visible.
  - **Wi-Fi** (named, e.g. "Wi-Fi · HomeNet" when allowed): greyed when not connected; tap → Wi-Fi settings.
  - **Hotspot**: greyed when off. Tap →
    - Auto mode: turns on **your own hotspot** (its name is your phone's name, e.g. "vivo X300 Ultra")
      and shows the Wi-Fi QR. The iPhone remembers it, so the QR is needed only once, ever.
    - No Auto mode: if your hotspot is already on, shows its QR. Otherwise it creates a quick private
      network (Android names it "AndroidShare_xxxx", a new name and password each time, so a fresh QR every time).
  - One line saying what the iPhone must do.
- **To the iPhone / From the iPhone**: unchanged.
- **Auto mode** card (replaces "Automatic NFC"): "NFC and the hotspot turn on when you open AstraDrop and off
  when you close it." Set up / On / Off.

## Auto mode setup (a full screen, one step at a time, like SolCube's setup)
1. Why ("Android only lets apps switch NFC and the hotspot through a developer feature. Nothing leaves your phone.").
2. Developer options: animation of tapping Build number 7×; ticks itself off when it detects them.
3. Wireless debugging: opens the page, ticks off when it's on (says "connect to Wi-Fi first" if needed).
4. Pairing code: animation shows where the code is and where the notification is. The status updates live
   ("waiting for the code screen", "type the code", "pairing", "done", or exactly what failed).
5. Done → back to the app, Auto mode on.

## Still to prove before building
- Turning on the user's own hotspot from the engine (shell has the permission; needs a prototype on the phone).
- Wi-Fi network name needs location permission on Android; without it the chip just says "Wi-Fi".
