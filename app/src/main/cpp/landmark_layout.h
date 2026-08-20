#pragma once

namespace kinex {

// Input layout, per the JNI contract: 33 landmarks x (x, y, z, visibility) in
// MediaPipe order, 132 floats.
//
// Separate from engine.h because engine.h will include one_euro.h to hold a filter by
// value, and the filter needs this layout — leaving these constants in engine.h would
// make that include cycle.
inline constexpr int kLandmarkCount = 33;
inline constexpr int kLandmarkStride = 4;
inline constexpr int kLandmarkFloats = kLandmarkCount * kLandmarkStride;

}  // namespace kinex
