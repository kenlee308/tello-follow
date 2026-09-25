# TelloFollow

Android follow-me app for the **standard DJI Tello** (not EDU). Streams the drone’s H.264 video, detects the largest face with **ML Kit Face Detection** (fast / stream-oriented), and steers with a simple PID on `rc` axes. Photos and MP4 video are saved to MediaStore.

**Package:** `com.kenny.tellofollow`  
**Stack:** Kotlin, Jetpack Compose, minSdk 26, targetSdk / compileSdk 35

---

## Safety disclaimer

- Tello has **no GPS**. Wind, Wi‑Fi dropouts, and vision errors can cause unexpected motion or falls.
- Fly outdoors or in a large clear indoor space, **away from people, pets, glass, and obstacles**.
- Keep visual line of sight. Be ready to hit **EMERGENCY** (motors off) or **Land**.
- Low battery auto-land and “lost face → hover” are helpers, **not** a guarantee of safety.
- **You assume all risk.** This is a hobby / research scaffold, not a certified product.

---

## Prerequisites

1. **Android Studio** (Ladybug / Koala or newer recommended) with Android SDK 35 and a device or emulator.
2. A charged **DJI Tello** (standard).
3. An Android phone (mid-range or better). Emulator **cannot** talk to a real Tello Wi‑Fi AP usefully for flight — use a physical device.
4. Optional: reading glasses / prop guards if you value your furniture.

---

## How to open and run

1. Clone or copy this folder to your machine.
2. Open **Android Studio → Open →** select the `tello-follow` directory (the one with `settings.gradle.kts`).
3. Let Gradle sync. If the wrapper JAR is missing, Studio will offer to use its embedded Gradle — accept, or run **File → New → Import** and let it generate the wrapper.
4. Connect a phone via USB (enable developer options + USB debugging), or use wireless debugging.
5. On the phone: connect Wi‑Fi to the Tello SSID (`TELLO-xxxx`). The phone will lose normal internet; that is expected.
6. Run the **app** configuration. Landscape FPV UI should appear.
7. Tap **Connect** → wait for “Connected + stream” → **Takeoff** → optionally **Follow ON**.

If sync fails on first open: **File → Sync Project with Gradle Files**. Distribution is pinned to Gradle **8.9** in `gradle/wrapper/gradle-wrapper.properties`.

---

## Connecting to the Tello

| Channel | Address | Role |
|--------|---------|------|
| Commands | `192.168.10.1:8889` UDP | Text SDK (`command`, `takeoff`, `rc`, …) |
| State | `*:8890` UDP | Telemetry (`bat`, `h`, `yaw`, …) |
| Video | `*:11111` UDP | H.264 chunks (1460-byte packets; short packet = EOF frame) |

Keep-alive: the client periodically sends `rc 0 0 0 0` so the drone’s ~15s idle timeout does not auto-land.

---

## Architecture

```
Wi‑Fi AP (TELLO)
    │
    ├── TelloCommandClient   → takeoff / land / rc / streamon
    ├── TelloStateListener   → StateFlow<TelloTelemetry>
    └── TelloVideoReceiver   → frame assembly → H264Decoder
                                    │
                                    └── ImageReader → Bitmap
                                          ├── Compose Image preview
                                          ├── PersonDetector (ML Kit face)
                                              → PidController → rc
                                              → PhotoCapture / VideoRecorder
FlightSafetyMonitor ← battery, lost face, disconnect
FlightViewModel     ← wires UI + loops
FlightScreen        ← Compose FPV chrome
```

### Follow control

- Largest face bbox → normalized center `(cx, cy)` and relative size.
- PID: **yaw** ← x error, **throttle** ← y error (inverted), **pitch** ← size vs target (~8% of frame).
- Outputs clamped to `-100..100`. Follow is gated by an explicit toggle.
- Manual joysticks: left = throttle/yaw, right = pitch/roll; stick deflection >15 overrides the matching auto axis.

### Media

- **Photo:** latest decoded `Bitmap` → JPEG in `Pictures/TelloFollow` (MediaStore).
- **Video:** MVP **re-encodes** bitmaps to H.264 MP4 via MediaCodec + MediaMuxer (`Movies/TelloFollow`). Remuxing raw Tello NALs is lower-CPU but brittle across firmware; deferred.

---

## Project layout

See the tree under `app/src/main/java/com/kenny/tellofollow/` — packages `tello`, `tracking`, `media`, `safety`, `viewmodel`, `ui`.

---

## Build notes / caveats

- Video decode uses **ImageReader → Bitmap** so ML Kit and photo share frames. Compose shows that bitmap as FPV preview. Direct SurfaceView output is stubbed for a future GL dual-output path (MediaCodec allows only one output Surface).
- This repository may be developed on a Linux box **without** the Android SDK. Source is written to compile conceptually in Android Studio; do not expect `./gradlew assembleDebug` to succeed until the SDK and wrapper JAR are present.
- `gradle/wrapper/gradle-wrapper.properties` is included; the binary `gradle-wrapper.jar` is **not** (Studio regenerates it on sync).
- ML Kit Face Detection downloads a model on first run — needs a one-time network path (e.g. sync deps on wifi before joining Tello AP, or pre-install).
- YUV→Bitmap in the decoder is a pragmatic JPEG round-trip for MVP CV/photo quality, not broadcast color science.

---

## Credits / inspiration

- **TelloMe** — closed-source inspiration for the overall UX (follow + FPV); **no proprietary code was copied**.
- **TelloNO** and community StackOverflow discussions — UDP 11111 frame assembly (`len < 1460` end-of-frame).
- **DJI Tello SDK** documentation — text command set and ports.

---

## License

Scaffold for personal / educational use by Kenny. Fly responsibly.
