#include "geometry.h"

#include <cmath>

namespace kinex {
namespace {

constexpr float kRadiansToDegrees = 57.29577951308232f;

struct Vector {
    float x;
    float y;
};

Vector Between(Point from, Point to) {
    return Vector{to.x - from.x, to.y - from.y};
}

bool TooShort(Vector v) {
    return v.x * v.x + v.y * v.y < kMinSegmentLength * kMinSegmentLength;
}

// atan2 of the cross and dot products, rather than acos of the normalized dot.
//
// acos divides by the two lengths first, and that quotient rounds to just outside [-1, 1]
// for a nearly straight limb — where acos returns NaN. A standing leg is nearly straight,
// so the naive form fails exactly at the pose the calibration baseline is measured in.
// atan2 has no domain to fall out of and needs no normalization to get there.
float AngleBetween(Vector a, Vector b) {
    const float cross = a.x * b.y - a.y * b.x;
    const float dot = a.x * b.x + a.y * b.y;
    return std::fabs(std::atan2(cross, dot)) * kRadiansToDegrees;
}

}  // namespace

float JointAngleDegrees(Point from, Point vertex, Point to) {
    const Vector first = Between(vertex, from);
    const Vector second = Between(vertex, to);
    if (TooShort(first) || TooShort(second)) {
        return kInvalidAngle;
    }
    return AngleBetween(first, second);
}

float TorsoLeanDegrees(Point shoulder, Point hip) {
    const Vector torso = Between(shoulder, hip);
    if (TooShort(torso)) {
        return kInvalidAngle;
    }
    // Straight down the image. y grows downward in MediaPipe's normalized frame, so an
    // upright torso runs along +y and reads as zero lean.
    return AngleBetween(torso, Vector{0.0f, 1.0f});
}

float ShoulderSeparationRatio(Point left_shoulder, Point right_shoulder, Point left_hip,
                              Point right_hip) {
    const Point shoulder_mid{(left_shoulder.x + right_shoulder.x) * 0.5f,
                             (left_shoulder.y + right_shoulder.y) * 0.5f};
    const Point hip_mid{(left_hip.x + right_hip.x) * 0.5f, (left_hip.y + right_hip.y) * 0.5f};

    const Vector torso = Between(shoulder_mid, hip_mid);
    if (TooShort(torso)) {
        return kInvalidRatio;
    }
    const float torso_height = std::sqrt(torso.x * torso.x + torso.y * torso.y);
    return std::fabs(left_shoulder.x - right_shoulder.x) / torso_height;
}

}  // namespace kinex
