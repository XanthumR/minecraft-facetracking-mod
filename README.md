# Face Track

A Fabric mod for Minecraft **26.2** that puts your real face on your player. A webcam tracker drives your
player's eyelids, pupils, eyebrows, mouth and head rotation, and your microphone drives lip-sync.

## How it works

```
webcam + mic → tracker/tracker.py (MediaPipe) → UDP 127.0.0.1:39540 → Fabric mod → your player's face
```

- **Eyes:** blinks and winks, eyelids half-close when you squint or look down, and pupils follow your gaze.
- **Brows:** raise and frown, each side separately.
- **Mouth:** opens with your jaw or your voice, smile/frown corners per side, width, pucker, sideways
  shift, and teeth.
- **Tongue:** hold **J** (rebindable under Controls → Miscellaneous). Webcam trackers can't see tongues.
- **Head:** your real head's yaw, pitch and roll are added on top of where you're looking.

Press **F5** or open your inventory to see your own face.

## Multiplayer

If the server has the mod, nearby players with the mod see your face too.
- Your client sends your face 20 times a second while the tracker runs.
- The server checks the values, rate-limits each player, and forwards your face only to players who can see you.
- Their clients ease between updates so it looks smooth.

Who needs what:
- **Server:** the mod jar plus Fabric API.
- **Players:** the mod to see faces, and the tracker only to show their own. Players without the mod can still join.
- **Server without the mod:** only you see your face.

## Setup

Requirements: Java 25, Python 3.12 or 3.13, a webcam, and optionally a microphone.

The tracker downloads the MediaPipe face model on first run. To play outside the dev environment, put
the jar from [Releases](https://github.com/XanthumR/minecraft-facetracking-mod/releases), or `build/libs/` after
building, and Fabric API into a Fabric 26.2 `mods` folder, on both the client and the server.

### Windows

```powershell
# 1. Tracker
py -3.13 -m venv tracker\.venv
tracker\.venv\Scripts\python -m pip install -r tracker\requirements.txt
tracker\.venv\Scripts\python tracker\tracker.py

# 2. Game (second terminal)
.\gradlew runClient
```

### Linux

Supported on x86_64 and ARM64 (aarch64) with glibc 2.28 or newer: Ubuntu 20.04+, Debian 10+, Fedora,
Arch and so on.

On Arch, use the [Arch Linux](#arch-linux) section below instead.

**1. System packages** (Java 25, Python with venv, and PortAudio for the microphone):

```bash
# Debian / Ubuntu
sudo apt install openjdk-25-jdk python3 python3-venv libportaudio2

# Fedora
sudo dnf install java-25-openjdk-devel python3 portaudio
```

If your distro doesn't package Java 25 yet, install [Temurin 25](https://adoptium.net/) instead.
Check with `java -version`.

**2. Tracker:**

```bash
python3 -m venv tracker/.venv
tracker/.venv/bin/python -m pip install -r tracker/requirements.txt
tracker/.venv/bin/python tracker/tracker.py
```

**3. Game** (second terminal):

```bash
./gradlew runClient
```

**Linux troubleshooting:**
- **`Could not open camera 0`:**
  - Check that the webcam shows up: `ls /dev/video*`.
  - Make sure your user can use it: `sudo usermod -aG video $USER`, then log out and back in.
  - Some webcams appear as several devices; try `CAMERA = 1` or `2` at the top of `tracker/tracker.py`.
- **`Voice lip-sync off, could not open microphone` / `PortAudio library not found`:** install PortAudio
  (step 1). To pick a specific mic, run `tracker/.venv/bin/python -m sounddevice` and set `MIC_DEVICE`
  in `tracker.py` to its name or number. PipeWire and PulseAudio both work through the default device.
- **Preview window won't open on Wayland** (`Could not find the Qt platform plugin "wayland"`): run the
  tracker with `QT_QPA_PLATFORM=xcb tracker/.venv/bin/python tracker/tracker.py`.
- **No preview window at all** (headless or SSH): the tracker needs a desktop session to show its window.

### Arch Linux

For x86_64 Arch and Arch-based distros (EndeavourOS, Manjaro, CachyOS, ...). Arch's own Python 3.14 works,
because every tracker dependency has a prebuilt package for it, so you don't need pyenv or an older Python.

**1. Packages:**

```bash
sudo pacman -S --needed git jdk25-openjdk python portaudio v4l-utils
```

**2. Use Java 25.** Arch's default `jdk-openjdk` is a newer Java (27). The game runs on it, but this
project builds against Java 25, so make 25 the default:

```bash
archlinux-java status                    # lists installed Java versions
sudo archlinux-java set java-25-openjdk
java -version                            # should say 25
```

If you'd rather keep Java 27 as your default, prefix the Gradle commands with
`JAVA_HOME=/usr/lib/jvm/java-25-openjdk` instead.

**3. Get the code and start the tracker:**

```bash
git clone https://github.com/XanthumR/minecraft-facetracking-mod.git
cd minecraft-facetracking-mod
python -m venv tracker/.venv
tracker/.venv/bin/python -m pip install -r tracker/requirements.txt
tracker/.venv/bin/python tracker/tracker.py
```

Don't install the Python packages with `pacman` or a system-wide `pip`. Arch blocks system-wide `pip`
installs, which is why the tracker gets its own venv.

**4. Run the game.** Either:
- **Dev client:** `./gradlew runClient` in a second terminal.
- **Your normal setup:** install [Prism Launcher](https://prismlauncher.org/) (`sudo pacman -S prismlauncher`)
  and create a **26.2** instance with the **Fabric** loader. Add Fabric API and `facetrack-<version>.jar` from
  [Releases](https://github.com/XanthumR/minecraft-facetracking-mod/releases) under Mods. Prism manages its
  own Java, so step 2 only matters for `./gradlew`.

**Arch troubleshooting:**
- **Webcam:**
  - `v4l2-ctl --list-devices` shows which `/dev/video*` number is your camera. Set `CAMERA` in
    `tracker/tracker.py` to match; webcams often list two nodes, and the first one is the right one.
  - Arch gives the logged-in desktop user camera access automatically through systemd-logind, so you
    normally don't need the `video` group.
- **Microphone not found or silent:**
  - PortAudio talks to ALSA. On PipeWire (the Arch default), install `pipewire-alsa` so ALSA goes through
    PipeWire; on PulseAudio, install `pulseaudio-alsa`.
  - List devices with `tracker/.venv/bin/python -m sounddevice` and set `MIC_DEVICE` if the default is wrong.
- **Preview window on Wayland** (Hyprland, Sway, KDE or GNOME on Wayland): OpenCV's window needs XWayland.
  Run `QT_QPA_PLATFORM=xcb tracker/.venv/bin/python tracker/tracker.py`, and make sure XWayland is enabled
  in your compositor (it is by default in KDE and GNOME).
- **`./gradlew` fails with an "Unsupported class file major version" error:** Gradle is running on the
  wrong Java. Redo step 2.

## Calibrating

In the tracker's preview window:
- **c**: look straight at the camera with a relaxed face and stay silent, then press it. That pose
  becomes neutral, and it's saved to `tracker/calibration.json`.
- **r**: reset the calibration.
- **q**: quit.

Tuning knobs are at the top of `tracker/tracker.py`:
- `SIGN_*`, `SWAP_EYES` and `SIGN_GAZE`: flip one if something moves the wrong way.
- The `*_GAIN` values: raise one if a feature barely moves.
- `SMOOTHING`: raise it if the face jitters, lower it if it lags.
- `MIC_QUIET_DB` / `MIC_LOUD_DB`: set these from the mic level shown in the preview.

Run `tracker\.venv\Scripts\python tracker\tracker.py --test` for the tracker's self-check.

## Limitations

- Face parts are placed for the default 8×8 face layout (eyes on row 4, mouth on row 6). Skins with
  features elsewhere will look off.
- While tracking, the mod's eyes and mouth are drawn over the skin's own.
- The pupil colour is fixed.
