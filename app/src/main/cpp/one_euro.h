#pragma once

#include <cstdint>

#include "landmark_layout.h"

namespace kinex {

// One Euro constants, per the design doc. They are tuned against normalized image
// coordinates (0..1) with the derivative expressed in units per second, so neither the
// input scale nor the time base may change without retuning both.
//
// Chosen from a sweep against squat_8rep: min_cutoff is the knob that responds here, and
// beta is a second-order trim on top of it. Beta is O(1) rather than the thousandths a
// pixel-unit implementation would use — knee speed in normalized coordinates is ~0.05
// units/s, so a beta in the thousandths cannot lift the cutoff measurably. See the design doc's
// tuning section for the arithmetic.
//
// Provisional: squat_8rep is 25 fps and the device runs at 30.
inline constexpr float kMinCutoffHz = 3.0f;
inline constexpr float kBeta = 2.0f;
inline constexpr float kDerivativeCutoffHz = 1.0f;

// Floor on the frame interval. MediaPipe timestamps are contractually monotonic, so a
// zero or negative step means an upstream bug rather than a case worth handling; the
// floor is here only so that bug cannot divide by zero. Clamping rather than branching
// keeps the per-frame path straight, and the effect is benign — a sub-millisecond step
// drives alpha near zero, so the filter simply holds its previous output for a frame.
inline constexpr float kMinTimestepSeconds = 0.001f;

// Exponential smoother over one scalar channel. Holds the previous output and nothing
// else: alpha is supplied per call, because One Euro recomputes it every frame.
class LowPassFilter {
public:
    LowPassFilter();

    // Returns the smoothed value. The first call after construction or Reset() adopts
    // |value| unchanged — there is no previous output to blend against.
    float Filter(float value, float alpha);

    // True once a sample has been seen. The One Euro derivative channel reads this to
    // tell a genuine zero from a filter that has never run.
    bool initialized() const { return initialized_; }

    void Reset();

private:
    float value_;
    bool initialized_;
};

// One Euro filter over a single scalar channel — a landmark's x, or its y.
//
// The cutoff rises with the measured speed of the signal, which is what buys the two
// properties the rep FSM needs at once: a limb holding still is smoothed hard, while a
// fast descent is barely smoothed at all, so the bottom of a rep stays where it
// actually happened instead of being rounded off.
class OneEuroFilter {
public:
    OneEuroFilter();

    // |dt_seconds| is the interval since this channel's previous sample, already floored
    // by the caller. Allocates nothing.
    float Filter(float value, float dt_seconds);

    void Reset();

private:
    LowPassFilter value_filter_;
    LowPassFilter derivative_filter_;
    float previous_value_;
};

// The per-frame entry point: one One Euro filter per landmark coordinate, 33 landmarks
// x (x, y), each independent of the others.
//
// z is deliberately untouched — the design doc forbids building logic on it — and visibility
// is a confidence rather than a position, so neither is filtered. Both are copied
// through so the filtered buffer keeps the 132-float input layout and everything
// downstream reads one array instead of two.
//
// No visibility gating: a landmark MediaPipe is unsure about is filtered exactly like
// any other. Whether a low-confidence frame should be held or dropped is the alignment
// gate's question, and it is answered in Phase 4, not here.
class LandmarkFilter {
public:
    LandmarkFilter();

    // Reads kLandmarkFloats floats from |landmarks| and writes kLandmarkFloats floats
    // into |out|; the two must not overlap. The timestep comes from |timestamp_ms|
    // against the previous call, so the filter follows the frame clock rather than
    // assuming a frame rate.
    //
    // Allocates nothing — this runs once per frame.
    void Apply(const float* landmarks, int64_t timestamp_ms, float* out);

    // Drops every channel's history and the frame clock. The next Apply() starts over,
    // adopting its input as the first sample.
    void Reset();

private:
    // Two channels per landmark, x then y, matching the first two slots of the input
    // stride.
    OneEuroFilter channels_[kLandmarkCount * 2];
    int64_t previous_timestamp_ms_;
    bool has_previous_timestamp_;
};

}  // namespace kinex
