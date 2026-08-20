#pragma once

// Host-side stand-in for the NDK's logging header, so jni_bridge.cpp compiles off
// device. The bridge logs from create() and destroy(); the soak asserts on
// allocation behaviour, not on log output.
//
// Values match the NDK's android/log.h so a level is never silently the wrong one if this
// stub and the real header ever meet in the same build.

enum {
    ANDROID_LOG_INFO = 4,
    // Added when create() grew a refusal path for an implausible calibration.
    ANDROID_LOG_WARN = 5,
};

inline int __android_log_print(int, const char*, ...) {
    return 0;
}
