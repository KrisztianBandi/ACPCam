# AC PhoneCam v1 – your Android phone as a VR-style camera for Assetto Corsa

Hold the phone like a real camera. **Turn it to look, physically move it to move the camera in 3D** (ARCore position
tracking – walk around the car, crouch, lean in). Nothing is drawn on the game screen. NO REGISTRATION REQUIRED

```
Android app (ARCore) --Wi-Fi--> Python server on PC --localhost--> PhoneCam Lua app in AC --> camera
                                   (also presses your blinker keys)
```

## Requirements
- Windows PC: Assetto Corsa + **Content Manager** + **Custom Shaders Patch (CSP)** (recent; Lua apps + camera grabbing)
- Python 3.9+ on the PC
- Android 7+ phone **that supports ARCore** (https://developers.google.com/ar/devices) with **Google Play Services for AR**
  installed (the app offers to install it), on the same Wi-Fi as the PC

## 1. PC setup (once)
1. Double-click `pc\run.bat` (installs `websockets`, opens the PC app).
2. Press **Install / Update into Assetto Corsa** (re-do this after every update of this package).
   Manual: copy `ac_lua\PhoneCam` to `...\assettocorsa\apps\lua\PhoneCam`.
3. Allow Python through Windows Firewall (private network) when asked – port 8765.

## 2. Phone
Install `AC-PhoneCam.apk` (allow "Install unknown apps"). Allow the **camera** permission – ARCore needs the camera to
track your position (the picture stays on the phone; it is never sent anywhere).

## 3. Using it
1. Start the PC app, start AC, load a session (start in cockpit view F1, or a replay).
2. Phone: enter the PC address shown in the PC window, **Connect**.
3. Wait until the phone says **"AR: tracking OK"** (move slowly, point at a textured, well-lit area – not a blank wall).
4. Hold the phone the way you want "forward" and press **Recenter**.

### Phone app
**Camera tab**
| Control | Does |
|---|---|
| Camera preview switch | Shows/hides the phone's camera picture. Tracking keeps running either way (the picture is only for you) |
| Anchor: Car / World | **Car**: camera is attached to the car's body and rides with it (leave through a window while driving). **World**: camera stays fixed in the world – use it in replays |
| Camera ON/OFF | Gives the camera back to AC instantly |
| Turn signals | Left / Hazards (all) / Right – toggles |
| Recenter | "Forward" = car/replay-camera forward; camera returns to AC's camera position; tracking origin reset |
| Photo | Presses Steam's screenshot key (F12) on the PC. Optional self-timer in Settings |
| HOLD to reposition | Camera freezes while held: walk back to a comfortable spot, release, and carry on from where the camera was (like grabbing the world in VR). Can be set to tap-to-toggle |

**Settings tab**
- **3D movement:** tracking on/off, *Master movement scale* (1.00 = real 1:1 metres), per-axis speed X / Y / Z (1.00 normal, **0 disables that axis**).
- **Smoothing:** *Rotation smoothing* and *Position smoothing* (0 = none … 1 = maximum, adaptive: rock steady when still, still responsive when you move fast), *Tracking prediction* (hides camera latency).
- **Look & lens:** *Look sensitivity* (e.g. 0.5 = precise slow turning), *Lock horizon* (no roll), *FOV* (low = Auto, follows AC).
- **Car & anchor:** *Car follow smoothing* (removes jitter at speed), *Ignore car pitch & roll* (heading only – steadier shots), *Anchor to AC's camera* (alternative anchor).
- **Photo:** self-timer 0–10 s.
- **Invert:** look left/right, up/down, roll, and each movement axis.
- *Reset all settings*.

### Car anchor
Car mode now attaches to the **car body** (not AC's camera), so it works from any AC camera – including free camera.
The camera starts exactly where AC's camera currently is, then rides with the car. A filter removes the jitter of the
car's position at speed (*Car follow smoothing*); jumps (replay seek, teleport) are followed instantly.

### Replays / world anchor
AC's own replay camera is the starting point. In **World** mode "forward" is the direction that camera was looking and
the camera stays fixed in the world while the replay plays, pauses or rewinds – walk around a frozen or moving car.
In **Car** mode the camera follows the focused car and re-centers if you switch cars.

### Blinkers and photos (PC keys)
The phone sends commands to the PC app, which presses a key; **AC / Steam must have that key bound**.
Defaults: Left = Numpad 4, Hazards = Numpad 5, Right = Numpad 6, Photo = F12 (Steam's screenshot key).
1. In AC: Options > Controls (CSP adds turn-signal bindings) – bind those keys, **or** pick the keys you already use in the
   PC app dropdowns (saved automatically).
2. Use the PC app's **Test** buttons (3-second delay – click, then switch to AC) to check them.
3. Photos need AC launched through Steam with the overlay on (Steam > Settings > In Game > screenshot shortcut).
   The PC app has an **Open Steam screenshots folder** button.
A tap toggles a signal on/off; the phone highlights what it *thinks* is on (it can't read the car's state).

## Limits / honest notes
- ARCore tracks the *phone's camera*: keep the camera uncovered, avoid dark/blank rooms and fast shaking. If it says
  tracking lost, the camera holds its last position until tracking recovers.
- Movement is 1:1 real-world metres (room-scale). Use *Movement scale* or *HOLD to reposition* for larger distances.
- No ARCore on the phone? The app falls back to a weaker accelerometer-only estimate (short leans only).
- The APK bundles ARCore SDK 1.25 (2021). If your Google Play Services for AR ever refuses it, the phone shows
  "ARCore unavailable (…SdkTooOld…)".
- Phone stays in portrait.

## Troubleshooting
- Phone can't connect: same Wi-Fi? Firewall? correct IP (guest Wi-Fi often isolates devices).
- Phone connected but AC isn't: CSP installed? app copied to `apps/lua/PhoneCam`? Open **Phone Camera** from AC's app bar once.
- Camera doesn't move: see `Documents\Assetto Corsa\logs\custom_shaders_patch.log`.
- Something feels backwards: Settings > Invert.
- Rebuild the APK: `android/build.sh` (Debian/Ubuntu, packages listed inside).
