# TelloFollow

Android follow-me app for the **standard DJI Tello** (not EDU). Streams the drone's H.264 video, tracks a person with **ML Kit Pose Detection** (body / torso; face fallback), and steers with a simple PID on `rc` axes. Photos and MP4 video (optional phone mic) are saved to MediaStore.

**Package:** `com.kenny.tellofollow`  
**Stack:** Kotlin, Jetpack Compose, minSdk 26, targetSdk / compileSdk 35

| Branch / tag | Version | Notes |
|--------------|---------|-------|
| `main` / tag `v1.0.0` | 1.0.0 | Face follow, silent video record |
| `v2` (current) | 2.0.0-dev | Body/pose follow + mic mux on Record |

---

## v2-dev features

- **Body follow:** ML Kit Pose Detection (`pose-detection:18.0.0-beta5`, STREAM_MODE). Follow target = torso center (shoulder+hip midpoint); bbox from landmark extents. Face Detection remains as fallback when pose misses, or via the **Body/Face** mode chip (default **Body**). Overlay draws skeleton + body box.
- **Mic on Record:** `RECORD_AUDIO` requested when you tap Record. AAC from the phone mic is muxed into the MP4 alongside re-encoded H.264. If permission is denied or audio setup fails, recording continues **video-only** with a status message. Tello's stream itself has no audio.

---

## Safety disclaimer

- Tello has **no GPS**. Wind, Wi-Fi dropouts, and vision errors can cause unexpected motion or falls.
- Fly outdoors or in a large clear indoor space, **away from people, pets, glass, and obstacles**.
- Keep visual line of sight. Be ready to hit **EMERGENCY** (motors off) or **Land**.
- Low battery auto-land and "lost subject -> hover" are helpers, **not** a guarantee of safety.
- **You assume all risk.** This is a hobby / research scaffold, not a certified product.

---

## Prerequisites

1. **Android Studio** (Ladybug / Koala or newer recommended) with Android SDK 35 and a device or emulator.
2. A charged **DJI Tello** (standard).
3. An Android phone (mid-range or better). Emulator **cannot** talk to a real Tello Wi-Fi AP usefully for flight — use a physical device.
4. Optional: JDK 21 for CLI builds (`JAVA_HOME` pointing at a JetBrains/Android Studio JBR 21 works).

---

## How to open and run

1. Clone or copy this folder to your machine.
2. Open **Android Studio -> Open ->** select the `tello-follow` directory (the one with `settings.gradle.kts`).
3. Check out branch **`v2`** for body follow + mic (`git checkout v2`). Use **`main`** / tag `v1.0.0` for the face-only release.
4. Let Gradle sync. If the wrapper JAR is missing, Studio will offer to use its embedded Gradle — accept, or run **File -> New -> Import** and let it generate the wrapper.
5. Connect a phone via USB (enable developer options + USB debugging), or use wireless debugging.
6. On the phone: connect Wi-Fi to the Tello SSID (`TELLO-xxxx`). The phone will lose normal internet; that is expected.
7. Run the **app** configuration. Landscape FPV UI should appear.
8. Tap **Connect** -> wait for "Connected + stream" -> **Takeoff** -> optionally **Follow ON** (Body mode by default). Tap **Record** and allow the mic if you want audio on the clip.

CLI (optional, JDK 21):

```text
set JAVA_HOME=C:\Users\apsis\.jdks\jbr-21.0.11
gradlew.bat assembleDebug
```

If sync fails on first open: **File -> Sync Project with Gradle Files**. Distribution is pinned to Gradle **8.9** in `gradle/wrapper/gradle-wrapper.properties`.

---

## Connecting to the Tello

| Channel | Address | Role |
|--------|---------|------|
| Commands | `192.168.10.1:8889` UDP | Text SDK (`command`, `takeoff`, `rc`, ...) |
| State | `*:8890` UDP | Telemetry (`bat`, `h`, `yaw`, ...) |
| Video | `*:11111` UDP | H.264 chunks (1460-byte packets; short packet = EOF frame) |

Keep-alive: the client periodically sends `rc 0 0 0 0` so the drone's ~15s idle timeout does not auto-land.

---

## Architecture

```
Wi-Fi AP (TELLO)
    |
    +-- TelloCommandClient   -> takeoff / land / rc / streamon
    +-- TelloStateListener   -> StateFlow<TelloTelemetry>
    +-- TelloVideoReceiver   -> frame assembly -> H264Decoder (Surface)
                                    |
                                    +-- PixelCopy Bitmap
                                          +-- PersonDetector (pose + face fallback)
                                              -> PidController -> rc
                                          +-- PhotoCapture / VideoRecorder (+ optional AAC mic)
FlightSafetyMonitor <-> battery, lost subject, disconnect
FlightViewModel     <-> wires UI + loops
FlightScreen        <-> Compose FPV chrome
```

### Follow control

- **Body (default):** torso center from shoulders/hips; relative size from landmark bbox (~target 18% of frame area).
- **Face:** largest face bbox (~target 8% of frame area), same as v1.
- PID: **yaw** <- x error, **throttle** <- y error (inverted), **pitch** <- size vs target.
- Outputs clamped to `-100..100` (UI soft-caps sticks at +/-50). Follow is gated by an explicit toggle.
- Manual joysticks: left = throttle/yaw, right = pitch/roll; stick deflection >15 overrides the matching auto axis.
- RC loop sends at **20 Hz** (`sendRc`); follow detection ~15 Hz.

### Media

- **Photo:** latest PixelCopy `Bitmap` -> JPEG in `Pictures/TelloFollow` (MediaStore).
- **Video:** re-encodes bitmaps to H.264 MP4 via MediaCodec + MediaMuxer (`Movies/TelloFollow`), optionally muxing phone mic AAC on a second track with aligned `presentationTimeUs`.

---

## Project layout

See the tree under `app/src/main/java/com/kenny/tellofollow/` — packages `tello`, `tracking`, `media`, `safety`, `viewmodel`, `ui`.

---

## Build notes / caveats

- Video decode is **Surface-only** H.264. Bitmaps for CV / photo / record come from **PixelCopy** on the live SurfaceView (not ImageReader).
- ML Kit Face + Pose models download on first run — needs a one-time network path (sync deps on normal Wi-Fi before joining Tello AP, or pre-install).
- Do not break: flight RC soft cap, 20 Hz sendRc, Land-when-connected, Connect/Wi-Fi binder.

---

## Credits / inspiration

- **TelloMe** — closed-source inspiration for the overall UX (follow + FPV); **no proprietary code was copied**.
- **TelloNO** and community StackOverflow discussions — UDP 11111 frame assembly (`len < 1460` end-of-frame).
- **DJI Tello SDK** documentation — text command set and ports.

---

## License

Scaffold for personal / educational use by Kenny. Fly responsibly.
