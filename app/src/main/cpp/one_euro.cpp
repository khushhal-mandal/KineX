#include "one_euro.h"

#include <cmath>

namespace kinex {
namespace {

constexpr float kPi = 3.14159265358979323846f;

// The One Euro smoothing factor: alpha = 1 / (1 + tau/dt), tau = 1/(2*pi*cutoff).
// Rising cutoff means rising alpha, which means the output follows the input harder.
float SmoothingFactor(float cutoff_hz, float dt_seconds) {
    const float tau = 1.0f / (2.0f * kPi * cutoff_hz);
    return 1.0f / (1.0f + tau / dt_seconds);
}

}  // namespace

LowPassFilter::LowPassFilter() : value_(0.0f), initialized_(false) {}

float LowPassFilter::Filter(float value, float alpha) {
    value_ = initialized_ ? alpha * value + (1.0f - alpha) * value_ : value;
    initialized_ = true;
    return value_;
}

void LowPassFilter::Reset() {
    value_ = 0.0f;
    initialized_ = false;
}

OneEuroFilter::OneEuroFilter() : value_filter_(), derivative_filter_(), previous_value_(0.0f) {}

float OneEuroFilter::Filter(float value, float dt_seconds) {
    // On the first sample there is no interval to difference across, so the speed the
    // cutoff keys off is taken as zero. That leaves the cutoff at kMinCutoffHz, and the
    // value filter adopts the sample outright regardless of what alpha comes out.
    const float derivative =
        value_filter_.initialized() ? (value - previous_value_) / dt_seconds : 0.0f;
    const float smoothed_derivative =
        derivative_filter_.Filter(derivative, SmoothingFactor(kDerivativeCutoffHz, dt_seconds));

    // The whole point of One Euro: a fast-moving signal raises its own cutoff, so lag
    // falls exactly where lag would cost the most.
    const float cutoff = kMinCutoffHz + kBeta * std::fabs(smoothed_derivative);

    previous_value_ = value;
    return value_filter_.Filter(value, SmoothingFactor(cutoff, dt_seconds));
}

void OneEuroFilter::Reset() {
    value_filter_.Reset();
    derivative_filter_.Reset();
    previous_value_ = 0.0f;
}

LandmarkFilter::LandmarkFilter()
    : channels_{}, previous_timestamp_ms_(0), has_previous_timestamp_(false) {}

void LandmarkFilter::Apply(const float* landmarks, int64_t timestamp_ms, float* out) {
    // The first frame has no interval to measure, so the floor stands in. Every channel
    // adopts its sample outright on that frame, so the substitute cannot affect output.
    const float measured = has_previous_timestamp_
                               ? static_cast<float>(timestamp_ms - previous_timestamp_ms_) * 0.001f
                               : 0.0f;
    const float dt_seconds = measured > kMinTimestepSeconds ? measured : kMinTimestepSeconds;
    previous_timestamp_ms_ = timestamp_ms;
    has_previous_timestamp_ = true;

    for (int i = 0; i < kLandmarkCount; ++i) {
        const int base = i * kLandmarkStride;
        out[base] = channels_[i * 2].Filter(landmarks[base], dt_seconds);
        out[base + 1] = channels_[i * 2 + 1].Filter(landmarks[base + 1], dt_seconds);
        out[base + 2] = landmarks[base + 2];  // z: weakly calibrated, nothing reads it
        out[base + 3] = landmarks[base + 3];  // visibility: a confidence, not a position
    }
}

void LandmarkFilter::Reset() {
    for (OneEuroFilter& channel : channels_) {
        channel.Reset();
    }
    previous_timestamp_ms_ = 0;
    has_previous_timestamp_ = false;
}

}  // namespace kinex
