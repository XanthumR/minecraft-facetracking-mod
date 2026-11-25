"""Webcam face tracker -> Face Track Minecraft mod (UDP on localhost).

Run:  tracker/.venv/Scripts/python tracker/tracker.py      (--test runs the self-check)
Preview window keys: c = calibrate (look straight ahead, relaxed face), r = reset calibration, q = quit.
"""
import json
import math
import os
import socket
import sys
import time
import urllib.request

PORT = 39540
CAMERA = 0
MODEL_URL = "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task"
MODEL_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "face_landmarker.task")
CALIB_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "calibration.json")
# Packet (must match FaceTrack.VALUES in the mod):
# lidL lidR open pucker | yaw pitch roll | browL browR gazeX wide shift cornerL cornerR
# The first 4 are 0..1 amounts; the rest are signed offsets (see calibrate()).
VALUES = 14

# Calibration knobs. If your in-game head turns the wrong way, flip a sign here.
SIGN_YAW, SIGN_PITCH, SIGN_ROLL = 1.0, 1.0, -1.0
SWAP_EYES = False   # set True if winking your left eye closes the right eyelid in game (also flips mouth sides)
SMOOTHING = 0.5     # 0 = raw/jittery, 0.9 = smooth/laggy
BLINK_GAIN = 2.1    # MediaPipe rarely reports a full 1.0 blink; boost it
BROW_GAIN = 1.5     # MediaPipe brow values are small; raise if brows barely move, lower if they jump
GAZE_GAIN = 1.5     # raise if pupils barely move when you look sideways
SIGN_GAZE = 1.0     # flip to -1.0 if pupils look the opposite way to you
SQUINT_LID = 0.5    # how far lids drop when you squint/smile (0 = off)
LOOK_DOWN_LID = 0.4 # how far lids drop when you look down (0 = off)
CORNER_GAIN = 1.5   # how far mouth corners lift/drop for smile/frown
SHIFT_GAIN = 2.0    # how far the mouth slides when you move it sideways
MOUTH_GAIN = 2.5    # talking barely opens the jaw; boost it. Lower if the mouth gapes when barely open
MOUTH_SMOOTHING = 0.15  # the mouth gets less smoothing than the rest so fast syllables still show

# Voice lip-sync: the mouth opens with mic volume (combined with the webcam; whichever is more open wins).
# Watch the "mic" dB in the preview: set QUIET just above your silence level, LOUD to your normal talking level.
MIC_ENABLED = True
MIC_DEVICE = None   # None = Windows default input; or a device name/number (python -m sounddevice lists them)
MIC_QUIET_DB = -45  # at or below this the voice adds nothing (raise if background noise moves the mouth)
MIC_LOUD_DB = -20   # at or above this the mouth is fully open


SMOOTH_PER_VALUE = [MOUTH_SMOOTHING if i == 2 else SMOOTHING for i in range(VALUES)]  # index 2 = mouth open


def euler_yxz(r):
    """Yaw, pitch, roll (radians) from a 3x3 rotation matrix built as Ry(yaw) @ Rx(pitch) @ Rz(roll)."""
    yaw = math.atan2(r[0][2], r[2][2])
    pitch = math.asin(max(-1.0, min(1.0, -r[1][2])))
    roll = math.atan2(r[1][0], r[1][1])
    return yaw, pitch, roll


def brow(b, side):
    """-1 frown .. 1 raised, for side "Left" or "Right"."""
    up = (b["browInnerUp"] + b["browOuterUp" + side]) / 2
    return max(-1.0, min(1.0, BROW_GAIN * (up - b["browDown" + side])))


def lid(b, side):
    """0 open .. 1 closed: a blink, or partly closed when squinting or looking down."""
    return min(1.0, max(b["eyeBlink" + side] * BLINK_GAIN,
                        b["eyeSquint" + side] * SQUINT_LID,
                        b["eyeLookDown" + side] * LOOK_DOWN_LID))


def gaze_x(b):
    """-1 .. 1, positive = looking toward the subject's left. Averages both eyes."""
    left = b["eyeLookOutLeft"] - b["eyeLookInLeft"]
    right = b["eyeLookInRight"] - b["eyeLookOutRight"]
    return max(-1.0, min(1.0, SIGN_GAZE * GAZE_GAIN * (left + right) / 2))


def clamp1(v):
    return max(-1.0, min(1.0, v))


def mouth_open(b):
    """0 .. 1. Lips pressed shut while the jaw drops (mouthClose) counts as less open.
    mouthClose is only half-weighted: MediaPipe reports some of it during normal speech."""
    lower = (b["mouthLowerDownLeft"] + b["mouthLowerDownRight"]) / 2
    return max(0.0, min(1.0, MOUTH_GAIN * (b["jawOpen"] + 0.3 * lower - 0.5 * b["mouthClose"])))


def mouth_wide(b):
    """0 .. 1: smiling or stretching the lips sideways."""
    return clamp1((b["mouthSmileLeft"] + b["mouthSmileRight"] + b["mouthStretchLeft"] + b["mouthStretchRight"]) / 2)


def mouth_shift(b):
    """-1 .. 1, positive = mouth/jaw moved toward the subject's left."""
    return clamp1(SHIFT_GAIN * (b["mouthLeft"] - b["mouthRight"] + 0.5 * (b["jawLeft"] - b["jawRight"])))


def corner(b, side):
    """-1 frown .. 1 smile for one mouth corner."""
    return clamp1(CORNER_GAIN * (b["mouthSmile" + side] - b["mouthFrown" + side]))


def calibrate(raw, neutral):
    """Amounts (first 4) rescale so neutral -> 0 and 1 stays 1; the rest (angles, brows, mouth shape) subtract neutral."""
    out = [max(0.0, (r - n) / (1 - n)) if n < 0.95 else 0.0 for r, n in zip(raw[:4], neutral[:4])]
    return out + [r - n for r, n in zip(raw[4:], neutral[4:])]


def load_neutral():
    try:
        with open(CALIB_PATH) as f:
            neutral = json.load(f)
        if len(neutral) == VALUES:  # older calibration files (fewer values) are ignored
            print("Loaded calibration from", CALIB_PATH)
            return neutral
    except (OSError, ValueError):
        pass
    return [0.0] * VALUES


def voice_open(db):
    """0 .. 1 mouth opening for a mic level in dBFS."""
    return max(0.0, min(1.0, (db - MIC_QUIET_DB) / (MIC_LOUD_DB - MIC_QUIET_DB)))


def start_mic():
    """Starts a background mic stream. Returns a dict whose "db" key always holds the latest level, or None."""
    level = {"db": -100.0}
    if not MIC_ENABLED:
        return None
    try:
        import numpy as np
        import sounddevice as sd

        def on_audio(indata, frames, t, status):
            rms = float(np.sqrt(np.mean(indata[:, 0] ** 2)))
            level["db"] = 20 * math.log10(rms + 1e-10)

        # ~20ms blocks: fast enough to follow syllables.
        stream = sd.InputStream(device=MIC_DEVICE, channels=1, blocksize=1024, callback=on_audio)
        stream.start()
        level["stream"] = stream  # keep a reference so it isn't garbage collected
        print("Voice lip-sync on:", sd.query_devices(stream.device)["name"])
        return level
    except Exception as e:  # no mic / device busy: run webcam-only rather than crash
        print("Voice lip-sync off, could not open microphone:", e)
        return None


def main():
    import cv2
    import mediapipe as mp
    from mediapipe.tasks.python import BaseOptions, vision

    if not os.path.exists(MODEL_PATH):
        print("Downloading face model...")
        urllib.request.urlretrieve(MODEL_URL, MODEL_PATH)

    landmarker = vision.FaceLandmarker.create_from_options(vision.FaceLandmarkerOptions(
        base_options=BaseOptions(model_asset_path=MODEL_PATH),
        running_mode=vision.RunningMode.VIDEO,
        output_face_blendshapes=True,
        output_facial_transformation_matrixes=True,
    ))
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    cam = cv2.VideoCapture(CAMERA)
    if not cam.isOpened():
        sys.exit(f"Could not open camera {CAMERA}")

    mic = start_mic()
    smoothed = [0.0] * VALUES
    neutral = load_neutral()
    last_raw = None
    start = time.monotonic()
    print(f"Sending to 127.0.0.1:{PORT}. In the preview window: c = calibrate, r = reset, q = quit.")
    while True:
        ok, frame = cam.read()
        if not ok:
            break
        rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        result = landmarker.detect_for_video(
            mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb), int((time.monotonic() - start) * 1000))

        if result.face_blendshapes:
            b = {c.category_name: c.score for c in result.face_blendshapes[0]}
            blink_l, blink_r = lid(b, "Left"), lid(b, "Right")
            brow_l, brow_r = brow(b, "Left"), brow(b, "Right")
            corner_l, corner_r = corner(b, "Left"), corner(b, "Right")
            shift = mouth_shift(b)
            if SWAP_EYES:
                blink_l, blink_r = blink_r, blink_l
                brow_l, brow_r = brow_r, brow_l
                corner_l, corner_r = corner_r, corner_l
                shift = -shift
            pucker = max(b["mouthPucker"], b["mouthFunnel"])
            yaw, pitch, roll = euler_yxz(result.facial_transformation_matrixes[0][:3, :3])
            voice = voice_open(mic["db"]) if mic else 0.0
            raw = [blink_l, blink_r, max(mouth_open(b), voice), pucker, SIGN_YAW * yaw, SIGN_PITCH * pitch,
                   SIGN_ROLL * roll, brow_l, brow_r, gaze_x(b), mouth_wide(b), shift, corner_l, corner_r]
            last_raw = raw
            smoothed = [s * a + c * (1 - a) for s, c, a in zip(smoothed, calibrate(raw, neutral), SMOOTH_PER_VALUE)]
            sock.sendto(" ".join(f"{v:.4f}" for v in smoothed).encode("ascii"), ("127.0.0.1", PORT))
            s = smoothed
            lines = [
                "lid %.2f/%.2f brow %+.2f/%+.2f gaze %+.2f yaw %+.0f pitch %+.0f roll %+.0f" % (
                    s[0], s[1], s[7], s[8], s[9], *(math.degrees(a) for a in s[4:7])),
                "mouth open %.2f pucker %.2f wide %+.2f shift %+.2f corners %+.2f/%+.2f" % (
                    s[2], s[3], s[10], s[11], s[12], s[13]),
            ]
        else:
            lines = ["no face"]  # mod resets the face after 1s without packets
        if mic:
            lines.append("mic %.0f dB -> voice %.2f  (quiet %d, loud %d)" % (
                mic["db"], voice_open(mic["db"]), MIC_QUIET_DB, MIC_LOUD_DB))

        for i, text in enumerate(lines + ["c = calibrate (stay silent)  r = reset  q = quit"]):
            cv2.putText(frame, text, (10, 25 + 20 * i), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 0), 1)
        cv2.imshow("Face Track", frame)
        key = cv2.waitKey(1) & 0xFF
        if key == ord("q"):
            break
        if key == ord("c") and last_raw:
            neutral = last_raw
            with open(CALIB_PATH, "w") as f:
                json.dump(neutral, f)
            print("Calibrated: this is now your neutral face.")
        if key == ord("r"):
            neutral = [0.0] * VALUES
            if os.path.exists(CALIB_PATH):
                os.remove(CALIB_PATH)
            print("Calibration reset.")

    cam.release()
    cv2.destroyAllWindows()


def selftest():
    def rot(yaw, pitch, roll):
        cy, sy, cp, sp, cr, sr = math.cos(yaw), math.sin(yaw), math.cos(pitch), math.sin(pitch), math.cos(roll), math.sin(roll)
        ry = [[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]]
        rx = [[1, 0, 0], [0, cp, -sp], [0, sp, cp]]
        rz = [[cr, -sr, 0], [sr, cr, 0], [0, 0, 1]]
        mul = lambda a, b: [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]
        return mul(mul(ry, rx), rz)

    for angles in [(0, 0, 0), (0.5, 0, 0), (0, -0.4, 0), (0, 0, 0.3), (0.6, -0.3, 0.2)]:
        got = euler_yxz(rot(*angles))
        assert all(abs(g - a) < 1e-9 for g, a in zip(got, angles)), (angles, got)

    neutral = [0.2, 0.2, 0.1, 0.3, 0.1, -0.2, 0.05, 0.1, -0.1, 0.05, 0.3, 0.0, 0.1, 0.1]
    assert len(neutral) == VALUES
    assert calibrate(neutral, neutral) == [0.0] * VALUES  # neutral face -> all zero
    full = calibrate([1, 1, 1, 1] + neutral[4:], neutral)
    assert all(abs(v - 1) < 1e-9 for v in full[:4])  # full expression still reaches 1
    assert calibrate([0.0] * VALUES, neutral)[:4] == [0.0] * 4  # below neutral clamps to 0
    angled = [0.0] * VALUES
    angled[4], angled[8] = 0.6, 0.4
    got = calibrate(angled, neutral)
    assert abs(got[4] - 0.5) < 1e-9 and abs(got[8] - 0.5) < 1e-9  # angles and brows are offsets

    mouth = {k: 0.0 for k in ["jawOpen", "mouthClose", "mouthLowerDownLeft", "mouthLowerDownRight",
                              "mouthSmileLeft", "mouthSmileRight", "mouthStretchLeft", "mouthStretchRight",
                              "mouthFrownLeft", "mouthFrownRight", "mouthLeft", "mouthRight", "jawLeft", "jawRight"]}
    assert mouth_open(mouth) == 0.0
    talking = mouth_open({**mouth, "jawOpen": 0.15})
    assert talking > 0.3, talking  # a small talking jaw movement is clearly visible
    assert mouth_open({**mouth, "jawOpen": 0.6}) == 1.0  # wide open saturates
    assert mouth_open({**mouth, "jawOpen": 0.3, "mouthClose": 0.6}) == 0.0  # jaw down, lips shut -> closed
    assert mouth_open({**mouth, "jawOpen": 0.15, "mouthClose": 0.1}) < talking  # some closure reduces, not cancels
    assert mouth_wide({**mouth, "mouthSmileLeft": 1, "mouthSmileRight": 1}) == 1.0
    assert corner({**mouth, "mouthSmileLeft": 0.5}, "Left") > 0  # smile lifts that corner
    assert corner({**mouth, "mouthFrownLeft": 0.5}, "Left") < 0  # frown drops it
    assert corner({**mouth, "mouthSmileLeft": 0.5}, "Right") == 0  # other side unaffected
    assert mouth_shift({**mouth, "mouthLeft": 0.3}) > 0 > mouth_shift({**mouth, "mouthRight": 0.3})

    assert voice_open(-100) == 0.0  # silence
    assert voice_open(MIC_QUIET_DB) == 0.0
    assert voice_open(MIC_LOUD_DB) == 1.0 and voice_open(0) == 1.0  # loud saturates
    assert 0 < voice_open((MIC_QUIET_DB + MIC_LOUD_DB) / 2) < 1

    eyes = {f"{k}{s}": 0.0 for s in ("Left", "Right")
            for k in ("eyeBlink", "eyeSquint", "eyeLookDown", "eyeLookIn", "eyeLookOut")}
    assert lid(eyes, "Left") == 0.0
    assert lid({**eyes, "eyeBlinkLeft": 1.0}, "Left") == 1.0  # full blink closes
    assert 0 < lid({**eyes, "eyeSquintLeft": 1.0}, "Left") < 1  # squint half-closes
    assert gaze_x(eyes) == 0.0
    # Looking to the subject's left: left eye looks out, right eye looks in -> positive
    assert gaze_x({**eyes, "eyeLookOutLeft": 0.5, "eyeLookInRight": 0.5}) * SIGN_GAZE > 0

    zero = {k: 0.0 for k in ["browInnerUp", "browOuterUpLeft", "browDownLeft"]}
    assert brow(zero, "Left") == 0.0
    assert brow({**zero, "browDownLeft": 1.0}, "Left") == -1.0  # frown clamps at -1
    assert brow({**zero, "browInnerUp": 0.2, "browOuterUpLeft": 0.2}, "Left") > 0  # raised is positive
    print("selftest ok")


if __name__ == "__main__":
    selftest() if "--test" in sys.argv else main()
