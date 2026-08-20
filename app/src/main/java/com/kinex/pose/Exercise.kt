package com.kinex.pose

/**
 * The exercise table, mirrored from `exercises/exercise_config.cpp`.
 *
 * [id] is the contract. It is what crosses JNI to [NativeEngine.create], it is what selects
 * the row the engine runs, and Phase 6 stores it in a session — so a row may be appended and
 * none may be renumbered. That is why the sit-up sits at 5 rather than next to the squat: it
 * was implemented before the four rows above it, and took the next free id rather than the
 * next implemented one. Ids 6-9 followed the same rule, which is why the lunge is not filed
 * beside the squat it most resembles.
 *
 * Nothing else about a row is duplicated here. The joint triple, the target angle and the
 * violation rules live natively and stay there, because a second copy of a threshold is a
 * second thing to disagree with the first. What Kotlin needs is a name for a button and a
 * sentence telling the athlete what to hold while the calibration countdown runs.
 *
 * The start-pose lines say "left side" for every side-view exercise on purpose: every config
 * names left-side landmarks, and nothing picks the camera-facing limb yet. Stand the other
 * way round and the angle is read off the occluded one.
 *
 * Only the squat has been validated against a fixture. Every other row's angles are
 * estimates — for ids 6-9 the target is an estimate too, not only the start — so the start
 * pose lines below are descriptions of a movement, not of a measurement.
 */
enum class Exercise(val id: Int, val label: String, val startPose: String) {
    SQUAT(0, "Squat", "Left side to the camera, standing tall"),
    PUSH_UP(1, "Push-up", "Left side to the camera, top of a push-up, arms straight"),
    BICEP_CURL(2, "Bicep curl", "Left side to the camera, left arm hanging straight"),
    SHOULDER_PRESS(3, "Shoulder press", "Facing the camera, elbows bent at shoulder height"),
    LATERAL_RAISE(4, "Lateral raise", "Facing the camera, arms down at your sides"),
    SIT_UP(5, "Sit-up", "Left side to the camera, lying back, knees bent"),
    LUNGE(6, "Lunge", "Left side to the camera, standing tall, left leg forward"),
    GLUTE_BRIDGE(7, "Glute bridge", "Left side to the camera, lying back, knees bent, hips down"),

    /**
     * Took id 8 from the tricep extension, which was removed rather than retuned.
     *
     * The tricep extension never once completed a calibration on a device: arms overhead with
     * the forearms folded back put the left elbow and wrist behind the head and the torso, and
     * the elbow angle swung between 32 and 179 degrees from sample to sample. No target fixes
     * a landmark the model cannot find.
     *
     * Reusing the id breaks this file's own append-only rule, and it is safe for one reason
     * only: the tricep extension never counted a rep, so no session was ever written under id
     * 8. See the note in `exercises/exercise_config.h`.
     */
    JUMPING_JACK(8, "Jumping jack", "Facing the camera, standing tall, arms down at your sides"),
    LEG_RAISE(9, "Leg raise", "Left side to the camera, lying flat, legs straight and down");

    companion object {
        /**
         * The row a stored session's `exerciseId` refers to, or null for an id this build
         * does not know. Null rather than a fallback: a history row is a record of what
         * happened, and labelling an unknown id "Squat" would be inventing the past.
         */
        fun from(id: Int): Exercise? = entries.firstOrNull { it.id == id }
    }
}
