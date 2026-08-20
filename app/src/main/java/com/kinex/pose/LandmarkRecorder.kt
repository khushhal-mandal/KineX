package com.kinex.pose

import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Records landmark frames to a JSONL file, one JSON object per line:
 *
 *     {"timestampMs":8421,"width":480,"height":640,"landmarks":[132 floats]}
 *
 * The 132 floats are 33 landmarks x (x, y, z, visibility) in MediaPipe order, so a
 * recording is already in the layout the native engine takes across JNI.
 *
 * Width and height go in each line as well, which the frame-only spec doesn't strictly
 * need: without them replay can't reproduce the image aspect ratio and the overlay draws
 * a distorted skeleton. Keeping them per line leaves each line self-contained.
 *
 * Writes run on their own thread so file I/O never stalls MediaPipe's result callback.
 */
class LandmarkRecorder(private val directory: File, private val onError: (String) -> Unit) {

    private val io = Executors.newSingleThreadExecutor()
    private var writer: BufferedWriter? = null

    /** Returns the file being written, which exists once the first frame lands. */
    fun start(): File {
        val file = File(directory, "landmarks-${FILE_TIMESTAMP.format(Date())}.jsonl")
        io.execute {
            directory.mkdirs()
            guarded { writer = file.bufferedWriter() }
        }
        return file
    }

    /**
     * Safe to call from MediaPipe's result thread. [frame] is queued by reference, so it
     * must not be mutated afterwards — the live pipeline allocates a fresh one per frame.
     */
    fun record(frame: PoseFrame) {
        io.execute { guarded { writer?.write(frame.toJsonLine()) } }
    }

    /**
     * I/O runs off the caller's thread, so a failure would otherwise surface nowhere and
     * leave the HUD counting frames into a file that was never written. Dropping the
     * writer on the first failure keeps one bad recording from reporting every frame.
     */
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (error: IOException) {
            writer = null
            onError("Recording failed: ${error.message}")
        }
    }

    fun stop() {
        io.execute {
            guarded { writer?.close() }
            writer = null
        }
    }

    fun close() {
        stop()
        io.shutdown()
    }

    private companion object {
        val FILE_TIMESTAMP = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    }
}

private fun PoseFrame.toJsonLine(): String = buildString {
    append("{\"timestampMs\":").append(timestampMs)
    append(",\"width\":").append(imageWidth)
    append(",\"height\":").append(imageHeight)
    append(",\"landmarks\":[")
    for (i in points.indices) {
        if (i > 0) append(',')
        append(points[i])
    }
    append("]}\n")
}
