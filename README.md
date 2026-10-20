# ACPCam – realtime phone camera for Assetto Corsa

Hold your phone like a real camera: **turn it to look, physically move it to move the camera in 3D** (ARCore tracking –
walk around the car, crouch, lean in) and, if you want, **watch the live game view on the phone itself**.
Nothing is drawn on the game screen.

#### **ACPC ONLY RUNS LOCALLY. DOESN'T COLLECT ANY DATA AND DOES NOT SUPPORT PIRACY NOR ANYTHING ILLEGAL.**
=======
# AC PhoneCam v1 – your Android phone as a VR-style camera for Assetto Corsa

Hold the phone like a real camera. **Turn it to look, physically move it to move the camera in 3D** (ARCore position
tracking – walk around the car, crouch, lean in). Nothing is drawn on the game screen. **NO REGISTRATION REQUIRED**

```
Android app (ARCore) --Wi-Fi--> Python server on PC --localhost--> PhoneCam Lua app in AC --> camera
        ^                          | presses your blinker / photo keys
        '------ live game view ----' (captures the AC window, streams JPEGs back)
```

## Requirements

* Windows PC: Assetto Corsa + **Content Manager** + **Custom Shaders Patch (CSP)** (recent; Lua apps + camera grabbing)
* Python 3.9+ on the PC
* Android 7+ phone **with ARCore support** + **Google Play Services for AR** (the app offers to install it), same Wi-Fi as the PC

## 1\. PC setup (once)

1. Double-click `pc\\\\run.bat` (installs `websockets`, `mss`, `pillow`; opens the PC app).
2. Press **Install / Update into Assetto Corsa** (re-do this after every update of this package).
Manual: copy `ac\\\_lua\\\\PhoneCam` to `...\\\\assettocorsa\\\\apps\\\\lua\\\\PhoneCam`.
3. Allow Python through Windows Firewall (private network) when asked – port 8765.

## 2\. Phone

Install `ACPCam.apk` (allow "Install unknown apps"; it updates the older AC PhoneCam). Allow the **camera**
permission – ARCore needs it to track your position (the picture never leaves the phone).

## 3\. Using it

1. Start the PC app, start AC, load a session (cockpit view F1, or a replay).
2. Phone: type the PC address shown in the PC window, **Connect** – the top-right box turns light blue.
3. Wait for **AR tracking** (move slowly, point at a textured, well-lit area).
4. Hold the phone the way you want "forward" and press **Recenter**.

## The phone app

**Main page** (scrolls; the IP box and Connect button are part of it)

|Element|Does|
|-|-|
|Status box (top-right of the screen)|Gray = not connected, light blue = connected|
|Status \& position|AR tracking state, anchor type, live X/Y/Z of the phone|
|Camera ON/OFF|Gives the camera back to AC instantly|
|Turn signals|Left / Hazards (all) / Right (toggles)|
|**Realtime cam**|Dark until the phone is connected, then tap to open the live game view (see below)|
|Recenter / Photo|Recenter: "forward" = car/replay-camera forward, camera returns to AC's camera position. Photo: presses Steam's screenshot key (F12) on the PC|
|**HOLD to reposition** (bottom bar)|Camera freezes while held: walk back to a comfortable spot, release, and continue from where the camera was. Can be tap-to-toggle|
|☰ (bottom right)|Opens / closes Settings|

**Move the elements around:** Settings > *Main page layout*. For each element pick a zone (**Top / Middle / Bottom** – Middle
is vertically centred), reorder with ▲ ▼, and switch it on or off. The Hold button can be hidden too. *Reset layout* restores the default.

**Settings**

* **Anchor:** Car / World. *Car* attaches the camera to the car's body (works from any AC camera incl. free camera, starts where AC's camera is). *World* keeps it fixed in the world – use it in replays.
* **Realtime cam:** *Fill screen* (crops the edges) or *Fit whole image* (black bars) – never stretched; *Sharpness* (25–100 % of your phone's screen resolution), *Max frame rate* (10–60), double-tap-to-exit.
* **Phone camera preview:** show/hide the phone's own camera picture (tracking keeps running).
* **Main page layout** (above), **3D movement** (tracking on/off, master scale, per-axis X/Y/Z – 0 disables an axis), **Smoothing** (rotation, position, prediction),
**Look \& lens** (sensitivity, lock horizon, FOV), **Car \& anchor** (follow smoothing, ignore pitch/roll, anchor to AC camera), **Photo** (self-timer),
**Reposition button** (hold/toggle), **Invert** options, *Reset all settings*.

## Realtime cam (live game view)

The PC app captures the Assetto Corsa window and streams it to the phone, full screen, no GUI.

* Open it with the **Realtime cam** button. Exit with the phone's **Back** gesture/button or **double-tap**.
* In the live view the hardware **volume keys** work: **Volume Up = hold to reposition**, **Volume Down = recenter** (there is no GUI to tap).
* The PC app has *Realtime cam captures:* **Assetto Corsa window** (default) or the whole primary monitor.
* **Run AC in windowed or borderless mode.** Exclusive fullscreen can't be captured, and the AC window must stay visible (not minimised or covered).
* In-game apps/HUD appear in the stream (hide them in AC if you don't want them).
* A 16:9 game picture is much wider than a portrait phone: *Fill* shows a narrow centre slice (and can't be sharper than the game window's resolution), *Fit* shows everything.
* If Wi-Fi can't keep up, frames are dropped automatically; lower *Sharpness* or *Max frame rate* for less delay.

## Blinkers and photos (PC keys)

The phone sends commands to the PC app, which presses a key; **AC / Steam must have that key bound**.
Defaults: Left = Numpad 4, Hazards = Numpad 5, Right = Numpad 6, Photo = F12 (Steam's screenshot key).

1. In AC: Options > Controls (CSP adds turn-signal bindings) – bind those keys, **or** pick the keys you already use in the PC app dropdowns (saved automatically).
2. Use the PC app's **Test** buttons (3-second delay – click, then switch to AC) to check them.
3. Photos need AC launched through Steam with the overlay on. The PC app has an **Open Steam screenshots folder** button.

## Replays / world anchor

AC's own replay camera is the starting point. In **World** anchor "forward" is the direction that camera was looking, and the
camera stays fixed in the world while the replay plays, pauses or rewinds. In **Car** anchor the camera follows the focused car and re-centers if you switch cars.

## Limits / honest notes

* ARCore tracks the phone's camera: keep it uncovered, avoid dark/blank rooms and fast shaking. If tracking is lost the camera holds its last position.
* Movement is 1:1 real-world metres. Use *Master movement scale* or *HOLD to reposition* for larger distances.
* No ARCore on the phone? The app falls back to a weaker accelerometer-only estimate.
* The APK bundles ARCore SDK 1.25 (2021). If Google Play Services for AR ever refuses it the phone shows "ARCore unavailable (...SdkTooOld...)".
* Phone stays in portrait.

## Troubleshooting

* Phone can't connect: same Wi-Fi? Firewall? correct IP (guest Wi-Fi often isolates devices).
* Phone connected but AC isn't: CSP installed? app copied to `apps/lua/PhoneCam`? Open **Phone Camera** from AC's app bar once.
* Camera doesn't move: see `Documents\\\\Assetto Corsa\\\\logs\\\\custom\\\_shaders\\\_patch.log`.
* Realtime cam black / error toast: `pip install mss pillow`, AC windowed/borderless, AC window visible.
* Something feels backwards: Settings > Invert.
* Rebuild the APK: `android/build.sh` (Debian/Ubuntu, packages listed inside).

