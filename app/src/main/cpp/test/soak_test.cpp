// Host-side soak for the JNI bridge — runs without a device or emulator.
//
// The point is to exercise the allocating code, which is the bridge, not the engine:
// kinex::Engine holds fixed-size arrays and touches the heap only when it is itself
// new'd. Every allocation that can actually leak lives in jni_bridge.cpp — the Session,
// and the global ref backing the reused result array.
//
// To reach those from the host, the JNI entry points are called directly through a fake
// JNIEnv whose float arrays are heap objects with reference counts. A missing
// DeleteGlobalRef therefore leaks real memory that the platform leak checker sees, and
// the live-array counter catches it even where no leak checker exists at all.

#include <jni.h>

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "engine.h"

extern "C" {
JNIEXPORT jlong JNICALL Java_com_kinex_pose_NativeEngine_create(JNIEnv*, jobject, jint,
                                                                jfloatArray);
JNIEXPORT jfloatArray JNICALL Java_com_kinex_pose_NativeEngine_process(JNIEnv*, jobject, jlong,
                                                                       jfloatArray, jlong);
JNIEXPORT void JNICALL Java_com_kinex_pose_NativeEngine_reset(JNIEnv*, jobject, jlong);
JNIEXPORT void JNICALL Java_com_kinex_pose_NativeEngine_destroy(JNIEnv*, jobject, jlong);
}

namespace {

// Live heap-backed arrays. Ends at zero or the bridge dropped a reference.
long g_live_arrays = 0;

// Stand-in for a Java float[]. Reference counted so local and global refs behave the
// way the bridge expects them to.
struct FakeArray {
    std::vector<float> data;
    int refs;
};

FakeArray* Unwrap(jobject object) {
    return reinterpret_cast<FakeArray*>(object);
}

jfloatArray Wrap(FakeArray* array) {
    return reinterpret_cast<jfloatArray>(array);
}

void Release(jobject object) {
    FakeArray* array = Unwrap(object);
    if (--array->refs == 0) {
        --g_live_arrays;
        delete array;
    }
}

jsize FakeGetArrayLength(JNIEnv*, jarray array) {
    return static_cast<jsize>(Unwrap(array)->data.size());
}

jfloatArray FakeNewFloatArray(JNIEnv*, jsize length) {
    ++g_live_arrays;
    return Wrap(new FakeArray{std::vector<float>(static_cast<size_t>(length), 0.0f), 1});
}

void FakeGetFloatArrayRegion(JNIEnv*, jfloatArray array, jsize start, jsize length, jfloat* buffer) {
    std::memcpy(buffer, Unwrap(array)->data.data() + start,
                static_cast<size_t>(length) * sizeof(jfloat));
}

void FakeSetFloatArrayRegion(JNIEnv*, jfloatArray array, jsize start, jsize length,
                             const jfloat* buffer) {
    std::memcpy(Unwrap(array)->data.data() + start, buffer,
                static_cast<size_t>(length) * sizeof(jfloat));
}

jobject FakeNewGlobalRef(JNIEnv*, jobject object) {
    ++Unwrap(object)->refs;
    return object;
}

void FakeDeleteGlobalRef(JNIEnv*, jobject object) {
    Release(object);
}

void FakeDeleteLocalRef(JNIEnv*, jobject object) {
    Release(object);
}

JNINativeInterface FakeInterface() {
    JNINativeInterface table;
    std::memset(&table, 0, sizeof(table));
    table.GetArrayLength = FakeGetArrayLength;
    table.NewFloatArray = FakeNewFloatArray;
    table.GetFloatArrayRegion = FakeGetFloatArrayRegion;
    table.SetFloatArrayRegion = FakeSetFloatArrayRegion;
    table.NewGlobalRef = FakeNewGlobalRef;
    table.DeleteGlobalRef = FakeDeleteGlobalRef;
    table.DeleteLocalRef = FakeDeleteLocalRef;
    return table;
}

// Stack-owned inputs: the bridge reads them and never takes ownership, so they stay out
// of the live-array accounting.
FakeArray MakeLandmarks() {
    return FakeArray{std::vector<float>(kinex::kLandmarkFloats, 0.0f), 1};
}

// A calibration the exercise will accept.
//
// It has to be per-exercise now. A flat 170 used to serve every row, and then the calibration
// guard landed and cycle 3 — the shoulder press, whose target is also 170 — started being
// refused a handle, because a start angle sitting exactly on the target is precisely the span
// collapse the guard exists to reject. That was the guard working, not the engine breaking.
//
// The row's own nominal start is the obvious believable value, and reading it from the table
// means this cannot drift out of agreement with a target that moves later.
FakeArray MakeCalibration(int exercise_id) {
    return FakeArray{
        std::vector<float>{kinex::ConfigForExercise(exercise_id).nominal_start_degrees}, 1};
}

void VaryLandmarks(FakeArray* landmarks, long long frame) {
    const float base = static_cast<float>(frame % 997) * 0.001f;
    for (int i = 0; i < kinex::kLandmarkFloats; ++i) {
        landmarks->data[static_cast<size_t>(i)] = base + static_cast<float>(i) * 0.0001f;
    }
}

// The shape of the result, not its meaning. This used to pin the Phase 2 stub's fixed
// values; now that process() runs the real pipeline the numbers depend on the landmarks
// fed in, and asserting particular ones here would make the soak a second, worse copy of
// rep_fsm_test. What the soak is for is the JNI contract, so what it checks is that every
// slot Kotlin reads comes back inside the range Kotlin is promised.
bool CheckResultShape(const FakeArray* result, long long frame) {
    const std::vector<float>& values = result->data;
    if (values.size() != static_cast<size_t>(kinex::kResultFloats)) {
        std::printf("FAIL: result array is %zu floats, not %d, at frame %lld\n", values.size(),
                    kinex::kResultFloats, frame);
        return false;
    }
    const float state = values[kinex::kResultState];
    const float progress = values[kinex::kResultRepProgress];
    const int mask = static_cast<int>(values[kinex::kResultViolationMask]);
    if (state < 0.0f || state > 5.0f) {
        std::printf("FAIL: state %f is outside the contract's 0..5 at frame %lld\n", state,
                    frame);
        return false;
    }
    if (!(progress >= 0.0f) || progress > 1.0f) {
        std::printf("FAIL: repProgress %f is outside 0..1 at frame %lld\n", progress, frame);
        return false;
    }
    if ((mask & ~kinex::kViolationMaskAll) != 0) {
        std::printf("FAIL: violation mask %d holds a bit Kotlin cannot decode at frame %lld\n",
                    mask, frame);
        return false;
    }
    // The gate is closed now, so this is no longer a constant to pin — it is a real reading
    // off whatever pose the soak's synthetic landmarks describe, and the soak is not trying to
    // describe an aligned athlete. What still holds regardless of the pose is the range: slot
    // [5] is documented to Kotlin as 0..1, and a score outside that would be a contract break
    // whatever the body was doing.
    const float alignment = values[kinex::kResultAlignmentScore];
    if (!(alignment >= 0.0f) || alignment > 1.0f) {
        std::printf("FAIL: alignment score %f is outside 0..1 at frame %lld\n", alignment, frame);
        return false;
    }
    // Slot [6] carries no upper bound — a rep deeper than its target is real and the whole
    // point of leaving it unclamped. What it cannot be is a NaN: it is written straight into
    // a session record, and SQLite would take one without complaining.
    const float peak = values[kinex::kResultRepPeakProgress];
    if (peak != peak) {
        std::printf("FAIL: repPeakProgress is NaN at frame %lld\n", frame);
        return false;
    }
    return true;
}

// One engine, many frames. Catches per-frame allocation in process() — anything it
// allocated and dropped would accumulate ten million times over.
bool SoakProcess(JNIEnv* env, long long iterations) {
    FakeArray calibration = MakeCalibration(0);
    FakeArray landmarks = MakeLandmarks();

    const jlong handle =
        Java_com_kinex_pose_NativeEngine_create(env, nullptr, 0, Wrap(&calibration));
    if (handle == 0) {
        std::printf("FAIL: create returned a null handle\n");
        return false;
    }

    const jfloatArray first =
        Java_com_kinex_pose_NativeEngine_process(env, nullptr, handle, Wrap(&landmarks), 0);
    for (long long frame = 1; frame < iterations; ++frame) {
        VaryLandmarks(&landmarks, frame);
        const jfloatArray result = Java_com_kinex_pose_NativeEngine_process(
            env, nullptr, handle, Wrap(&landmarks), frame * 33);
        if (result != first) {
            std::printf("FAIL: process() returned a new array at frame %lld\n", frame);
            Java_com_kinex_pose_NativeEngine_destroy(env, nullptr, handle);
            return false;
        }
        if ((frame % 1000000) == 0 && !CheckResultShape(Unwrap(result), frame)) {
            Java_com_kinex_pose_NativeEngine_destroy(env, nullptr, handle);
            return false;
        }
    }

    Java_com_kinex_pose_NativeEngine_destroy(env, nullptr, handle);
    std::printf("process soak: %lld frames through one handle\n", iterations);
    return true;
}

// Many engines, few frames. Catches a Session or global ref that outlives destroy().
bool SoakCycles(JNIEnv* env, long long cycles) {
    FakeArray calibration = MakeCalibration(0);
    FakeArray landmarks = MakeLandmarks();

    for (long long cycle = 0; cycle < cycles; ++cycle) {
        const int exercise_id = static_cast<int>(cycle % 4);
        FakeArray calibration = MakeCalibration(exercise_id);
        const jlong handle = Java_com_kinex_pose_NativeEngine_create(
            env, nullptr, static_cast<jint>(exercise_id), Wrap(&calibration));
        if (handle == 0) {
            std::printf("FAIL: create returned a null handle at cycle %lld\n", cycle);
            return false;
        }
        VaryLandmarks(&landmarks, cycle);
        Java_com_kinex_pose_NativeEngine_process(env, nullptr, handle, Wrap(&landmarks), cycle);
        Java_com_kinex_pose_NativeEngine_reset(env, nullptr, handle);
        Java_com_kinex_pose_NativeEngine_destroy(env, nullptr, handle);
    }

    std::printf("cycle soak: %lld create/destroy cycles\n", cycles);
    return true;
}

}  // namespace

int main(int argc, char** argv) {
    const std::string mode = argc > 1 ? argv[1] : "all";

    JNINativeInterface table = FakeInterface();
    JNIEnv env{&table};

    bool ok = true;
    if (mode == "process" || mode == "all") {
        ok = SoakProcess(&env, 10000000) && ok;
    }
    if (mode == "cycles" || mode == "all") {
        ok = SoakCycles(&env, 100000) && ok;
    }

    if (g_live_arrays != 0) {
        std::printf("FAIL: %ld array reference(s) still live at exit\n", g_live_arrays);
        ok = false;
    }
    if (!ok) {
        return 1;
    }

    std::printf("%s: no live references at exit\n", mode.c_str());
    return 0;
}
