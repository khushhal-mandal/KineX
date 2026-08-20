#!/usr/bin/env python3
"""Turn a video file into a landmark fixture the host-side C++ tests can replay.

Output is byte-compatible with what LandmarkRecorder writes on the device — one JSON
object per line:

    {"timestampMs":8421,"width":480,"height":640,"landmarks":[132 floats]}

The 132 floats are 33 landmarks x (x, y, z, visibility) in MediaPipe order, which is the
layout the native engine takes across JNI. Alongside it this writes a *starter* segments
sidecar: the still window and the descent ranges, detected from the hip-y trace, for a
human to check by hand before the tests are allowed to believe them.

Why this exists: recording a fixture on the device means holding a phone, doing ten clean
reps, and pulling a file. Recording one from a video means finding a video. The tests are
the same either way, and a fixture nobody can regenerate is a fixture that rots.

Usage:
    tools/extract_landmarks.py squat.mp4 \\
        -o app/src/main/cpp/test/fixtures/squat_10rep.jsonl

Dependencies (none of these are shipped — this is a dev tool, not app or backend code):
    pip install mediapipe opencv-python numpy
"""

import argparse
import json
import math
import sys
from pathlib import Path

# ---------------------------------------------------------------------------
# Layout, matching landmark_layout.h and the MediaPipe pose model
# ---------------------------------------------------------------------------

LANDMARK_COUNT = 33
LANDMARK_STRIDE = 4
LANDMARK_FLOATS = LANDMARK_COUNT * LANDMARK_STRIDE

LEFT_SHOULDER, RIGHT_SHOULDER = 11, 12
LEFT_HIP, RIGHT_HIP = 23, 24
LEFT_KNEE, RIGHT_KNEE = 25, 26
LEFT_ANKLE, RIGHT_ANKLE = 27, 28

# The model the app ships. Resolved relative to this file so the tool works from any cwd.
DEFAULT_MODEL = (
    Path(__file__).resolve().parent.parent
    / "app"
    / "src"
    / "main"
    / "assets"
    / "pose_landmarker_lite.task"
)

# A still window narrower than this is not a variance baseline. The design doc asks for at least
# 3 seconds of standing at the head of a fixture; falling short is a warning rather than an
# error, because the sidecar is a starting point a human is about to read anyway.
MIN_STILL_SECONDS = 3.0

# Detection shape constants. These decide only what the *starter* sidecar proposes, and a
# human overrides them by editing the file — they are not tuning constants for the engine,
# and nothing in app/ or backend/ reads them.
STILL_PROBE_SECONDS = 0.5      # width of the window slid across the head of the recording
STILL_RANGE_FRACTION = 0.08    # a probe is "quiet" if hip-y moves under this much of the
                               # recording's full hip travel
STILL_SEARCH_FRACTION = 0.5    # only look for the still window in the first half
LEAVE_BAND_FRACTION = 0.25     # hip-y this far past standing means a rep has begun
BOTTOM_BAND_FRACTION = 0.60    # ...and this far means it was a rep, not a shuffle
DEPTH_TARGET_SLACK_DEGREES = 3.0  # shallow-direction slack on the suggested depth target


def eprint(*args):
    print(*args, file=sys.stderr)


# ---------------------------------------------------------------------------
# Extraction
# ---------------------------------------------------------------------------


def extract_frames(video_path, model_path, fps_override):
    """Run the pose landmarker over every frame. Returns (frames, fps).

    A frame is (timestamp_ms, width, height, [132 floats]). Frames where the model found
    no pose are dropped, which is what the device recorder does — it is only called with a
    result. Every index in the sidecar therefore counts *lines in the output*, not frames
    in the source video.
    """
    try:
        import cv2
        import mediapipe as mp
        from mediapipe.tasks import python as mp_python
        from mediapipe.tasks.python import vision
    except ImportError as error:
        raise SystemExit(
            f"missing dependency: {error.name}\n"
            "This is a dev tool and its dependencies are not part of the app or the "
            "backend:\n"
            "    pip install mediapipe opencv-python numpy"
        )

    if not model_path.is_file():
        raise SystemExit(
            f"no pose model at {model_path}\n"
            "This must be the same asset the app loads, not a MediaPipe default — a "
            "fixture extracted with a different model is measuring a different detector "
            "than the one the engine will see. Pass --model to point elsewhere."
        )

    capture = cv2.VideoCapture(str(video_path))
    if not capture.isOpened():
        raise SystemExit(f"cannot open video {video_path}")

    fps = fps_override or capture.get(cv2.CAP_PROP_FPS)
    if not fps or math.isnan(fps) or fps <= 0:
        raise SystemExit(
            f"the container reports no usable frame rate ({fps!r}); pass --fps explicitly"
        )

    options = vision.PoseLandmarkerOptions(
        base_options=mp_python.BaseOptions(model_asset_path=str(model_path)),
        # VIDEO, not LIVE_STREAM. The app uses LIVE_STREAM because it must drop frames to
        # keep up with a camera; a file has no such deadline, and VIDEO runs every frame in
        # order, so extracting the same video twice gives the same fixture. That
        # determinism is the whole point of a fixture.
        running_mode=vision.RunningMode.VIDEO,
        num_poses=1,
    )

    frames = []
    dropped = 0
    index = 0
    previous_timestamp = -1

    with vision.PoseLandmarker.create_from_options(options) as landmarker:
        while True:
            ok, bgr = capture.read()
            if not ok:
                break

            # Timestamps are synthesized from the frame rate rather than read per frame:
            # the engine only needs a monotonic clock in the same units the device
            # produces, and a synthesized one cannot inherit a container's jitter.
            timestamp_ms = int(round(index * 1000.0 / fps))
            if timestamp_ms <= previous_timestamp:
                # detect_for_video rejects a non-increasing clock. Only reachable above
                # ~1000 fps, or from a rounding collision at very high frame rates.
                timestamp_ms = previous_timestamp + 1
            previous_timestamp = timestamp_ms
            index += 1

            rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
            image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb)
            result = landmarker.detect_for_video(image, timestamp_ms)

            if not result.pose_landmarks:
                dropped += 1
                continue

            landmarks = result.pose_landmarks[0]
            if len(landmarks) != LANDMARK_COUNT:
                dropped += 1
                continue

            points = []
            for landmark in landmarks:
                visibility = getattr(landmark, "visibility", None)
                points.extend(
                    (
                        landmark.x,
                        landmark.y,
                        landmark.z,
                        0.0 if visibility is None else visibility,
                    )
                )

            height, width = bgr.shape[:2]
            frames.append((timestamp_ms, width, height, points))

    capture.release()

    if not frames:
        raise SystemExit(
            f"{video_path} produced no pose detections at all — wrong video, or the "
            "athlete is not in frame"
        )
    if dropped:
        eprint(f"note: {dropped} source frame(s) held no pose and were dropped")
    return frames, fps


def format_line(frame):
    """One JSONL line, in LandmarkRecorder's exact shape.

    Hand-built rather than json.dumps so the float formatting is ours: %.7g round-trips the
    float32 the device actually recorded without printing the seventeen digits a Python
    double would.
    """
    timestamp_ms, width, height, points = frame
    values = ",".join(f"{value:.7g}" for value in points)
    return (
        f'{{"timestampMs":{timestamp_ms},"width":{width},'
        f'"height":{height},"landmarks":[{values}]}}\n'
    )


# ---------------------------------------------------------------------------
# Starter sidecar: still window and descents, off the hip-y trace
# ---------------------------------------------------------------------------


def landmark_value(points, index, component):
    return points[index * LANDMARK_STRIDE + component]


def hip_y_trace(frames):
    """Hip height per frame, averaged across both hips.

    y grows downward in MediaPipe's normalized frame, so this trace *rises* as the athlete
    descends. Both hips rather than the camera-side one: at the bottom of a squat the far
    hip is occluded and its visibility collapses, but its y is still roughly right, and
    averaging is steadier than switching sides mid-rep.
    """
    trace = []
    for _, _, _, points in frames:
        left = landmark_value(points, LEFT_HIP, 1)
        right = landmark_value(points, RIGHT_HIP, 1)
        trace.append((left + right) / 2.0)
    return trace


def joint_angle_degrees(points, a, vertex, b):
    """The angle at |vertex|, in the image plane. Mirrors geometry.cpp's JointAngleDegrees.

    Duplicated rather than shared because this is a Python dev tool and that is C++ on the
    per-frame path; the two will not drift, because a fixture whose angles disagreed with
    the engine's would fail the very tests it exists to feed.
    """
    ax = landmark_value(points, a, 0) - landmark_value(points, vertex, 0)
    ay = landmark_value(points, a, 1) - landmark_value(points, vertex, 1)
    bx = landmark_value(points, b, 0) - landmark_value(points, vertex, 0)
    by = landmark_value(points, b, 1) - landmark_value(points, vertex, 1)
    cross = ax * by - ay * bx
    dot = ax * bx + ay * by
    return math.degrees(abs(math.atan2(cross, dot)))


def find_still(trace, fps, amplitude):
    """The quietest run near the head of the recording, as ([begin, end), was_quiet).

    Quiet is measured as peak-to-peak hip travel inside a short probe window, scaled to the
    recording's own full hip travel — an absolute threshold in normalized units would mean
    something different for every camera distance.

    Always returns a window. Stock footage routinely starts mid-movement and has no still
    segment at all; the design doc's rule for that case is to use what stillness exists and
    record the shortfall, not to widen a tolerance until the problem disappears. The second
    element says which happened, so the caller can say so out loud.
    """
    probe = max(2, int(round(STILL_PROBE_SECONDS * fps)))
    limit = max(probe + 1, int(len(trace) * STILL_SEARCH_FRACTION))
    ceiling = amplitude * STILL_RANGE_FRACTION

    best = (0, 0)
    run_start = None
    for start in range(0, limit - probe + 1):
        window = trace[start : start + probe]
        if (max(window) - min(window)) <= ceiling:
            if run_start is None:
                run_start = start
            end = start + probe
            if end - run_start > best[1] - best[0]:
                best = (run_start, end)
        else:
            run_start = None

    if best[1] - best[0] >= 2:
        return best, True

    # Nothing settled. Fall back to the least-bad probe window so the fixture still has a
    # variance baseline, and let the caller report how bad it is.
    quietest = min(
        range(0, max(1, limit - probe + 1)),
        key=lambda start: max(trace[start : start + probe])
        - min(trace[start : start + probe]),
    )
    return (quietest, quietest + probe), False


def find_descents(trace, still, amplitude):
    """One [begin, end) per rep: leaving the standing band, through to the bottom.

    A rep is only recorded if it went deep enough to be one — crossing the leave band and
    coming back is a shuffle, and marking those as reps would hand the FSM tests a rep count
    no human agreed to.
    """
    standing = sorted(trace[still[0] : still[1]])[(still[1] - still[0]) // 2]
    leave = standing + amplitude * LEAVE_BAND_FRACTION
    deep = standing + amplitude * BOTTOM_BAND_FRACTION

    descents = []
    begin = None
    bottom_index = None
    reached_depth = False

    for index, value in enumerate(trace):
        if begin is None:
            if value > leave:
                begin = index
                bottom_index = index
                reached_depth = value > deep
            continue

        if value > trace[bottom_index]:
            bottom_index = index
        if value > deep:
            reached_depth = True

        if value <= leave:
            if reached_depth and bottom_index > begin:
                descents.append((begin, bottom_index + 1))
            begin = None
            bottom_index = None
            reached_depth = False

    # A recording that ends at the bottom of a rep.
    if begin is not None and reached_depth and bottom_index > begin:
        descents.append((begin, bottom_index + 1))

    return descents


def rep_peak_angles(frames, descents):
    """The deepest knee angle each rep reached."""
    peaks = []
    for begin, end in descents:
        angles = []
        for index in range(begin, min(end, len(frames))):
            points = frames[index][3]
            for hip, knee, ankle in (
                (LEFT_HIP, LEFT_KNEE, LEFT_ANKLE),
                (RIGHT_HIP, RIGHT_KNEE, RIGHT_ANKLE),
            ):
                angles.append(joint_angle_degrees(points, hip, knee, ankle))
        if angles:
            peaks.append(min(angles))
    return peaks


def suggest_depth_target(peaks):
    """A starting value for depthTargetDeg, per the design doc: the median of the per-rep peak
    angles plus a few degrees of slack.

    Median rather than the shallowest rep, so one bad rep cannot drag the target up and
    quietly excuse itself. Slack in the shallow direction — a larger knee angle is a
    shallower squat — so a rep landing on the median still clears it.

    Still a suggestion. A real depth target comes from calibration on the device, not from
    the recording being judged against it.
    """
    if not peaks:
        return None
    median = sorted(peaks)[len(peaks) // 2]
    return round(median + DEPTH_TARGET_SLACK_DEGREES, 1)


# ---------------------------------------------------------------------------


def main():
    parser = argparse.ArgumentParser(
        description="Extract a landmark fixture and a starter segments sidecar from a video."
    )
    parser.add_argument("video", type=Path, help="source video file")
    parser.add_argument(
        "-o",
        "--output",
        type=Path,
        help="output .jsonl (default: alongside the video, same stem)",
    )
    parser.add_argument(
        "--segments",
        type=Path,
        help="output sidecar (default: <output stem>.segments.json)",
    )
    parser.add_argument(
        "--model",
        type=Path,
        default=DEFAULT_MODEL,
        help="pose_landmarker_lite.task (default: the app's own asset)",
    )
    parser.add_argument(
        "--fps",
        type=float,
        help="override the container's frame rate when synthesizing timestamps",
    )
    args = parser.parse_args()

    if not args.video.is_file():
        raise SystemExit(f"no such video: {args.video}")

    output = args.output or args.video.with_suffix(".jsonl")
    segments_path = args.segments or output.with_suffix("").with_suffix(".segments.json")

    eprint(f"model:  {args.model}")
    eprint(f"video:  {args.video}")
    frames, fps = extract_frames(args.video, args.model, args.fps)
    eprint(f"frames: {len(frames)} written at {fps:.3f} fps")

    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w") as handle:
        for frame in frames:
            handle.write(format_line(frame))

    trace = hip_y_trace(frames)
    amplitude = max(trace) - min(trace)
    if amplitude <= 0:
        raise SystemExit("the hip never moved — this is not a recording of reps")

    still, settled = find_still(trace, fps, amplitude)
    still_seconds = (frames[still[1] - 1][0] - frames[still[0]][0]) / 1000.0
    descents = find_descents(trace, still, amplitude)
    peaks = rep_peak_angles(frames, descents)
    depth_target = suggest_depth_target(peaks)

    # The standing knee angle, for the human to sanity-check the depth target against.
    standing = None
    still_angles = []
    for index in range(still[0], still[1]):
        points = frames[index][3]
        for hip, knee, ankle in (
            (LEFT_HIP, LEFT_KNEE, LEFT_ANKLE),
            (RIGHT_HIP, RIGHT_KNEE, RIGHT_ANKLE),
        ):
            still_angles.append(joint_angle_degrees(points, hip, knee, ankle))
    if still_angles:
        standing = round(sorted(still_angles)[len(still_angles) // 2], 1)

    sidecar = {
        "frames": len(frames),
        "still": list(still),
        "descent": [list(pair) for pair in descents],
    }
    if depth_target is not None:
        sidecar["depthTargetDeg"] = depth_target

    segments_path.parent.mkdir(parents=True, exist_ok=True)
    with segments_path.open("w") as handle:
        json.dump(sidecar, handle, indent=2)
        handle.write("\n")

    eprint("")
    eprint(f"wrote {output}")
    eprint(f"wrote {segments_path}")
    eprint("")
    eprint(f"still:   frames [{still[0]}, {still[1]}) = {still_seconds:.1f}s")
    if not settled:
        eprint(
            "  SHORTFALL: nothing in this clip is actually still — the window above is "
            "merely\n  the quietest stretch found. Common with stock footage that starts "
            "mid-movement.\n  Record this in the design doc rather than widening a tolerance to "
            "hide it."
        )
    elif still_seconds < MIN_STILL_SECONDS:
        eprint(
            f"  SHORTFALL: {still_seconds:.1f}s is under the {MIN_STILL_SECONDS:.0f}s "
            "the design doc asks for. The variance\n  baseline will be noisy. Record it rather "
            "than widening a tolerance to hide it."
        )
    eprint(f"descents: {len(descents)} detected")
    for number, (begin, end) in enumerate(descents):
        peak = f"{peaks[number]:.1f}deg" if number < len(peaks) else "?"
        eprint(f"  rep {number:2d}: [{begin}, {end})  peak {peak}")
    if standing is not None:
        eprint(f"standing knee angle (median over still): {standing} deg")
    if depth_target is not None:
        eprint(
            f"depthTargetDeg: {depth_target} (median per-rep peak + "
            f"{DEPTH_TARGET_SLACK_DEGREES:.0f}deg slack)"
        )
        if standing is not None and standing - depth_target < 30.0:
            eprint(
                "  WARNING: that sits close to the standing angle, so it may not be beyond "
                "the\n  advance trigger — which would make the depth violation unreachable."
            )
    eprint("")
    eprint(
        "These are a STARTING POINT. Check them against the video before any test trusts "
        "them:\n"
        "  - does the descent count match the reps actually performed?\n"
        "  - is the still window really standing still?\n"
        "  - is depthTargetDeg the depth you want reps judged against, or just the depth\n"
        "    this athlete happened to reach?"
    )


if __name__ == "__main__":
    main()
