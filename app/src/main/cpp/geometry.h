#pragma once

#include "landmark_layout.h"

namespace kinex {

// A landmark's position in the image plane: normalized MediaPipe coordinates, x to the
// right and y downward.
//
// z is absent on purpose. The design doc rules it out as weakly calibrated relative depth, and
// a type that cannot carry it is a rule nothing downstream can forget.
struct Point {
    float x;
    float y;
};

// Returned when an angle is not defined — see the degeneracy rule on each function.
//
// Real angles land in [0, 180], so this sits outside every honest answer. It is a plain
// float rather than a NaN because NaN spreads: every comparison against it is false, so a
// NaN angle would slip through a depth threshold silently and surface later as an FSM that
// simply stopped counting.
//
// Callers must check. Read as a knee angle, -1 is a squat bent past anything a leg can do,
// which is exactly how a sentinel becomes a phantom rep.
inline constexpr float kInvalidAngle = -1.0f;

inline bool IsValidAngle(float degrees) { return degrees >= 0.0f; }

// Shorter than this and a vector has no meaningful direction: the two landmarks are the
// same point plus rounding, and the angle between them would be float noise amplified into
// degrees. Normalized image units, so this is a small fraction of a pixel — it catches
// coincident points, not merely close ones. Landmarks MediaPipe is unsure about are the
// visibility gate's problem in Phase 4, not this file's.
inline constexpr float kMinSegmentLength = 1e-6f;

// Returned when the shoulder-separation ratio is not defined, for the same reason
// kInvalidAngle exists: a real ratio is never negative, so this cannot be mistaken for one.
inline constexpr float kInvalidRatio = -1.0f;

// Reads landmark |index|'s image-plane position out of a 132-float frame buffer, filtered
// or raw. Inline because the per-frame path calls it a handful of times per frame.
inline Point LandmarkPoint(const float* landmarks, int index) {
    const int base = index * kLandmarkStride;
    return Point{landmarks[base], landmarks[base + 1]};
}

// Reads landmark |index|'s visibility, MediaPipe's own confidence in 0..1.
//
// Safe to read off the filtered buffer as well as the raw one: One Euro smooths x and y and
// copies z and visibility through untouched, deliberately — a confidence is not a position
// and has no velocity to key a cutoff off.
inline float LandmarkVisibility(const float* landmarks, int index) {
    return landmarks[index * kLandmarkStride + kLandmarkStride - 1];
}

// Horizontal shoulder separation over torso height — the alignment gate's whole measurement.
//
// Dividing by torso height is what makes it a pose test rather than a distance test: both
// quantities scale with how far the athlete is from the camera, so the ratio does not. Side
// on, the shoulders stack and the ratio collapses toward zero; face on, they spread to a
// little over one.
//
// Reflection-invariant, like everything else the engine computes — |lShoulder.x - rShoulder.x|
// reads the same under the front camera's horizontal flip, which is why no camera-facing flag
// crosses JNI.
//
// Returns kInvalidRatio when the torso is shorter than kMinSegmentLength: the shoulder and hip
// midpoints have collapsed onto each other and there is no torso to measure against.
float ShoulderSeparationRatio(Point left_shoulder, Point right_shoulder, Point left_hip,
                              Point right_hip);

// The angle at |vertex|, between the vectors running out to |from| and |to|. Arguments
// read in anatomical order: hip, knee, ankle gives the squat's primary angle at the knee.
//
// Returns degrees in [0, 180] — 180 a straight limb, 90 a right angle. Unsigned, so it
// says nothing about which way the joint bends; in a profile view that is the alignment
// gate's question.
//
// Returns kInvalidAngle if either vector is shorter than kMinSegmentLength.
float JointAngleDegrees(Point from, Point vertex, Point to);

// How far the torso is off vertical: the angle between the shoulder -> hip vector and
// straight down the image.
//
// Returns degrees in [0, 180] — 0 upright, 90 horizontal. Unsigned, so leaning forward and
// leaning back read alike, which is what the design doc's violation rule wants: it trips past
// 55 degrees in either direction.
//
// Returns kInvalidAngle if the two points are closer than kMinSegmentLength.
float TorsoLeanDegrees(Point shoulder, Point hip);

}  // namespace kinex
