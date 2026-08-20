package com.kinex.pose

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Cap on the wait between frames, so a gap in a recording doesn't stall the replay. */
private const val MAX_FRAME_GAP_MS = 200L

/**
 * Replays a file written by [LandmarkRecorder], pacing frames by their recorded
 * timestamps and handing each one to [onFrame] — the same callback the live MediaPipe
 * result listener drives, so everything downstream of it cannot tell the two apart.
 *
 * Pacing is cosmetic: every downstream consumer reads time from [PoseFrame.timestampMs],
 * not the wall clock, so replay stays deterministic regardless of how it is paced.
 *
 * Cancelling the calling coroutine stops the replay and closes the file.
 */
suspend fun replayLandmarks(file: File, onFrame: (PoseFrame?) -> Unit) {
    withContext(Dispatchers.IO) {
        val reader = file.bufferedReader()
        var previousTimestampMs = -1L
        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val frame = parseFrame(line)
                if (previousTimestampMs >= 0) {
                    delay((frame.timestampMs - previousTimestampMs).coerceIn(0, MAX_FRAME_GAP_MS))
                }
                previousTimestampMs = frame.timestampMs
                onFrame(frame)
            }
        } finally {
            reader.close()
        }
    }
}

private fun parseFrame(line: String): PoseFrame {
    val json = JSONObject(line)
    val landmarks = json.getJSONArray("landmarks")
    val points = FloatArray(landmarks.length()) { landmarks.getDouble(it).toFloat() }
    return PoseFrame(
        points = points,
        imageWidth = json.getInt("width"),
        imageHeight = json.getInt("height"),
        timestampMs = json.getLong("timestampMs"),
    )
}
