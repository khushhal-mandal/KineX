#include <jni.h>

#include <android/log.h>

#include <new>

#include "engine.h"

namespace {

constexpr const char* kTag = "KineXEngine";

// Everything one session owns natively: the engine plus the two buffers the per-frame
// path reuses. Holding them here is what lets process() allocate nothing — the input
// buffer receives GetFloatArrayRegion, and result_array is handed back every frame
// instead of a fresh array.
struct Session {
    Session(int exercise_id, const float* calibration, int calibration_count)
        : engine(exercise_id, calibration, calibration_count),
          landmarks{},
          results{},
          result_array(nullptr) {}

    kinex::Engine engine;
    float landmarks[kinex::kLandmarkFloats];
    float results[kinex::kResultFloats];
    jfloatArray result_array;  // global ref, released in destroy()
};

Session* FromHandle(jlong handle) {
    return reinterpret_cast<Session*>(handle);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_kinex_pose_NativeEngine_create(JNIEnv* env,
                                        jobject /* thiz */,
                                        jint exercise_id,
                                        jfloatArray calibration_array) {
    float calibration[kinex::kMaxCalibrationFloats] = {};
    jsize count = 0;
    if (calibration_array != nullptr) {
        count = env->GetArrayLength(calibration_array);
        if (count > kinex::kMaxCalibrationFloats) {
            count = kinex::kMaxCalibrationFloats;
        }
        env->GetFloatArrayRegion(calibration_array, 0, count, calibration);
    }

    auto* session = new (std::nothrow) Session(exercise_id, calibration, count);
    if (session == nullptr) {
        return 0;
    }

    // A calibration the exercise cannot believe gets no handle. Kotlin sees the same null it
    // sees for a missing library, and that is unambiguous at the one call site that can
    // produce it: the calibrated open always follows a successful uncalibrated one for the
    // same exercise, so the library is demonstrably present and the only new way to fail here
    // is the span guard.
    if (!session->engine.calibration_accepted()) {
        __android_log_print(ANDROID_LOG_WARN, kTag,
                            "create refused exercise=%d start=%.1f: calibration too close to "
                            "the target",
                            exercise_id, count > 0 ? calibration[0] : 0.0f);
        delete session;
        return 0;
    }

    jfloatArray local = env->NewFloatArray(kinex::kResultFloats);
    if (local == nullptr) {
        delete session;
        return 0;
    }
    session->result_array = static_cast<jfloatArray>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    if (session->result_array == nullptr) {
        delete session;
        return 0;
    }

    __android_log_print(ANDROID_LOG_INFO, kTag, "create handle=%p exercise=%d calibration=%d",
                        static_cast<void*>(session), exercise_id, count);
    return reinterpret_cast<jlong>(session);
}

// The one crossing per frame. |landmarks| must hold exactly kLandmarkFloats floats —
// that is the contract, so nothing here re-checks it, and nothing here allocates.
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_kinex_pose_NativeEngine_process(JNIEnv* env,
                                         jobject /* thiz */,
                                         jlong handle,
                                         jfloatArray landmarks,
                                         jlong timestamp_ms) {
    Session* session = FromHandle(handle);
    env->GetFloatArrayRegion(landmarks, 0, kinex::kLandmarkFloats, session->landmarks);
    session->engine.Process(session->landmarks, timestamp_ms, session->results);
    env->SetFloatArrayRegion(session->result_array, 0, kinex::kResultFloats, session->results);
    return session->result_array;
}

extern "C" JNIEXPORT void JNICALL
Java_com_kinex_pose_NativeEngine_reset(JNIEnv* /* env */, jobject /* thiz */, jlong handle) {
    FromHandle(handle)->engine.Reset();
}

// Tolerates 0 because create() returns it on failure and teardown paths call destroy()
// unconditionally.
extern "C" JNIEXPORT void JNICALL
Java_com_kinex_pose_NativeEngine_destroy(JNIEnv* env, jobject /* thiz */, jlong handle) {
    Session* session = FromHandle(handle);
    if (session == nullptr) {
        return;
    }
    env->DeleteGlobalRef(session->result_array);
    __android_log_print(ANDROID_LOG_INFO, kTag, "destroy handle=%p", static_cast<void*>(session));
    delete session;
}
