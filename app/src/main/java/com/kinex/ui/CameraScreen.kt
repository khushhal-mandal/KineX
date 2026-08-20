package com.kinex.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kinex.BuildConfig
import com.kinex.camera.PoseAnalyzer
import com.kinex.data.AppSettings
import com.kinex.pose.Calibrator
import com.kinex.pose.Exercise
import com.kinex.pose.PoseLandmarkerHelper
import com.kinex.pose.RepState
import com.kinex.pose.replayLandmarks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.File

/**
 * Amber for a flagged rep — readable on the dark scrim without reading as an app error.
 * Shared with the history screen and the set summary so a flagged rep looks the same live and
 * in the record.
 *
 * This is a **status** colour in the sense the project's charting rules use: reserved, never
 * reused as decoration, and never the only thing carrying the meaning. Everywhere it appears
 * a word appears with it.
 */
internal val FlaggedColor = Color(0xFFFFC107)

/** How long a violation chip stays up after the rep that earned it. */
private const val VIOLATION_CHIP_MS = 2_600L

/**
 * The workout screen.
 *
 * [initialExercise] is the row Home's grid picked; the in-screen picker can still move off it.
 * [onSetSaved] fires with a new session's id when a set is written **by the idle timeout** —
 * the one ending that means "the athlete stopped", as opposed to backgrounding or teardown,
 * which also write a set but are not a moment to navigate anywhere.
 */
@Composable
fun CameraScreen(
    modifier: Modifier = Modifier,
    initialExercise: Exercise = Exercise.SQUAT,
    onOpenHistory: () -> Unit = {},
    onSetSaved: (Long) -> Unit = {},
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    LaunchedEffect(Unit) {
        if (!granted) requestPermission.launch(Manifest.permission.CAMERA)
    }

    if (granted) {
        PoseCamera(modifier, initialExercise, onOpenHistory, onSetSaved)
    } else {
        Box(modifier, contentAlignment = Alignment.Center) {
            Button(onClick = { requestPermission.launch(Manifest.permission.CAMERA) }) {
                Text("Grant camera access")
            }
        }
    }
}

@Composable
private fun PoseCamera(
    modifier: Modifier,
    initialExercise: Exercise,
    onOpenHistory: () -> Unit,
    onSetSaved: (Long) -> Unit,
) {
    val context = LocalContext.current

    // Scoped to the Workout navigation entry, so it survives rotation and is cleared — engine
    // destroyed — when the destination leaves the back stack. `remember` used to hold this,
    // which meant a rotation mid-set destroyed the engine and zeroed the count.
    val model: WorkoutViewModel = viewModel(
        factory = WorkoutViewModel.factory(initialExercise)
    )

    val recordingsDirectory = remember {
        File(context.getExternalFilesDir(null) ?: context.filesDir, "recordings")
    }
    var replayFile by remember { mutableStateOf<File?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    var showExercisePicker by remember { mutableStateOf(false) }
    // Read once per entry to this screen. Settings is not reachable without leaving the
    // workout, so there is nothing to observe: coming back re-reads it.
    val showEngineReadout = remember { AppSettings(context).showEngineReadout }

    // The set that just ended, handed up so navigation can show its summary. Read here rather
    // than called from the analysis thread: snapshot writes are thread-safe, LaunchedEffect
    // runs on main, and navigation has to happen on main. Cleared before the call so a
    // recomposition mid-navigation cannot fire it twice.
    val savedSessionId = model.savedSessionId
    LaunchedEffect(savedSessionId) {
        if (savedSessionId != null) {
            model.savedSessionId = null
            onSetSaved(savedSessionId)
        }
    }

    // A set in flight is written when the app goes to the background, not only when the
    // ViewModel is cleared. Pressing Home does neither, so without this the set would sit in
    // memory until the process was killed and then be gone — which is the failure Phase 6's
    // verify step is about.
    //
    // `isChangingConfigurations` is what stops this defeating the ViewModel. A rotation takes
    // the Activity through ON_STOP exactly like backgrounding does, so without the check
    // **rotating the phone mid-set ended the set**: the reps were written to history as a
    // finished set and the count went back to zero. Measured on a device, not theorised — the
    // count went 8 to 0 across a rotation while the calibration correctly survived, and an
    // 8-rep row appeared in history stamped at the moment of the rotation. The engine
    // surviving is worth nothing if the set on top of it does not.
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = context as? Activity
    DisposableEffect(lifecycleOwner, activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && activity?.isChangingConfigurations != true) {
                model.endSet()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Replaying leaves LiveCamera out of the composition entirely, which unbinds the
    // camera and disposes its PreviewView; returning to live builds a fresh one.
    val file = replayFile
    if (file != null) {
        LaunchedEffect(file) {
            try {
                replayLandmarks(file, model::onFrame)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                model.error = "Replay failed: ${error.message}"
            }
            replayFile = null
        }
    }

    Box(modifier) {
        if (file == null) {
            LiveCamera(model, Modifier.fillMaxSize())
        }
        // Neutral until an attempt is in flight, then the accent. The tint is the peripheral
        // signal that the FSM is actually counting; the ring below is the precise one.
        //
        // The accent is resolved here rather than inside the lambda: the lambda runs in the
        // draw scope, which is not a composable context and cannot read MaterialTheme.
        val activeTint = MaterialTheme.colorScheme.primary
        PoseOverlay(
            frame = { model.frame },
            modifier = Modifier.fillMaxSize(),
            tint = { skeletonTint(model.repState, activeTint) },
        )

        WorkoutTopBar(
            model = model,
            replayFile = file,
            showEngineReadout = showEngineReadout,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        // There is no set until there is a start angle, so the same slot holds the countdown
        // first and the ring afterwards.
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                // The pose comes first, and the countdown does not start until it is
                // acknowledged. This is the shoulder press fix: the sweep calibrated it in
                // the finish pose because the count was already running and nothing had said
                // what to hold.
                !model.poseConfirmed -> StartPoseCard(model)
                model.startAngleDegrees == null -> CalibrationReadout(model)
                else -> RepRing(model)
            }
            Spacer(Modifier.height(16.dp))
            // Alignment first: it explains a count that is not moving, which outranks a
            // verdict on a rep that already happened.
            if (model.startAngleDegrees != null && model.alignmentScore < 1f) {
                AlignmentPrompt(model, Modifier)
            } else {
                ViolationChip(model)
            }
        }

        if (showEngineReadout) {
            EngineReadout(
                model = model,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 168.dp),
            )
        }

        WorkoutControls(
            model = model,
            replayFile = file,
            onOpenHistory = onOpenHistory,
            onPickExercise = { showExercisePicker = true },
            onPickReplay = { if (file != null) replayFile = null else showPicker = true },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    if (showPicker) {
        ReplayPicker(
            directory = recordingsDirectory,
            onSelect = {
                showPicker = false
                replayFile = it
            },
            onDismiss = { showPicker = false },
        )
    }

    if (showExercisePicker) {
        ExercisePicker(
            current = model.exercise,
            onSelect = {
                showExercisePicker = false
                model.select(it)
            },
            onDismiss = { showExercisePicker = false },
        )
    }
}

/**
 * Neutral while the athlete is merely present, [active] while an attempt is in flight.
 *
 * The three "in motion" states are exactly the three the FSM considers an attempt, so this is
 * a read of the state graph rather than a guess at what looks active.
 *
 * Not `@Composable`: it is called from the overlay's draw scope, and the accent it needs is
 * passed in already resolved.
 */
private fun skeletonTint(state: RepState, active: Color): Color =
    if (state in ACTIVE_STATES) active else IdleSkeletonColor

/**
 * The rep count inside a ring of progress toward the current rep's target.
 *
 * The ring is slot [4] — clamped 0..1 by the engine precisely so a ring can be driven from it
 * without a ring that is 102% full. The unclamped number is slot [6] and goes to the record;
 * see the JNI contract. Nothing here derives either.
 *
 * The count is the largest thing on screen by a wide margin, which is the requirement: legible
 * from two metres, over a camera preview of an unknown room. It sits on a dark scrim for the
 * same reason it always did — unbacked white text disappears against a bright wall.
 */
@Composable
private fun RepRing(model: WorkoutViewModel) {
    // Smoothed only for the ring's own sake. The engine's value is already per-frame at 30 Hz;
    // this stops the arc stuttering when a detection drops and progress holds for a frame.
    val progress by animateFloatAsState(
        targetValue = model.repProgress,
        animationSpec = tween(durationMillis = 90),
        label = "repProgress",
    )
    val active = model.repState in ACTIVE_STATES
    val ringColor = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    }
    val trackColor = Color.White.copy(alpha = 0.16f)

    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(276.dp)) {
            val stroke = 14.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)

            drawArc(
                color = trackColor,
                startAngle = START_ANGLE,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (progress > 0f) {
                drawArc(
                    color = ringColor,
                    startAngle = START_ANGLE,
                    sweepAngle = 360f * progress.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = model.repCount.toString(),
                color = Color.White,
                fontSize = 116.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = if (model.repCount == 1) "REP" else "REPS",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The three states the FSM treats as an attempt in flight. */
private val ACTIVE_STATES = setOf(RepState.ADVANCING, RepState.PEAK, RepState.RETURNING)

/** Twelve o'clock, so the ring fills the way a clock does. */
private const val START_ANGLE = -90f

/**
 * The chip that appears when a counted rep was flagged, then fades.
 *
 * Keyed on the rep count rather than on the violation list: the engine holds slot [2] from one
 * counted rep until the next one counts, so the list alone does not change when two
 * consecutive reps earn the same fault, and the chip would never re-appear for the second.
 *
 * It carries the fault as a **word**, not only as amber. That is the rule for a status colour
 * anywhere in this app — a colour-blind athlete, or one glancing at a phone across a bright
 * gym, gets the same information.
 */
@Composable
private fun ViolationChip(model: WorkoutViewModel) {
    var visible by remember { mutableStateOf(false) }
    val violations = model.lastRepViolations

    LaunchedEffect(model.repCount) {
        if (model.repCount > 0 && violations.isNotEmpty()) {
            visible = true
            delay(VIOLATION_CHIP_MS)
            visible = false
        } else {
            visible = false
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(140)),
        exit = fadeOut(tween(650)),
    ) {
        Text(
            text = violations.joinToString("  ·  ") { it.label },
            color = Color.Black,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(FlaggedColor, RoundedCornerShape(50))
                .padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }
}

/**
 * The exercise name, and whatever the screen is currently doing that is not counting reps.
 *
 * Small and at the top on purpose: the athlete chose the exercise a moment ago on Home and
 * needs it confirmed, not announced. Everything competing with the rep count for attention
 * loses.
 */
@Composable
private fun WorkoutTopBar(
    model: WorkoutViewModel,
    replayFile: File?,
    showEngineReadout: Boolean,
    modifier: Modifier,
) {
    val recordingFile = model.recordingFile
    val status = when {
        replayFile != null -> "REPLAY  ${replayFile.name}"
        recordingFile != null -> "REC ${model.recordedFrames}"
        else -> model.error
    }

    Column(
        modifier = modifier.padding(top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = model.exercise.label.uppercase(),
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
                .padding(horizontal = 16.dp, vertical = 6.dp),
        )
        if (status != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = status,
                color = if (recordingFile != null) FlaggedColor else Color.White,
                fontSize = 12.sp,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        // The frame rate is an engine number, so it travels with the rest of them rather than
        // sitting permanently in the corner of a screenshot.
        if (showEngineReadout) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "%.1f FPS".format(model.fps),
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 12.sp,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * The engine's own numbers, behind the Settings toggle and off by default.
 *
 * This is what the HUD used to show everybody: FSM state, the joint angle, live progress, the
 * calibrated start angle, and what the last counted rep scored. All of it is diagnostic — the
 * start angle is on the end because every other number is measured from it, and a set that
 * counts nothing is usually a set calibrated on the wrong pose.
 */
@Composable
private fun EngineReadout(model: WorkoutViewModel, modifier: Modifier) {
    val violations = model.lastRepViolations
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "%s  ·  %.0f°  ·  prog %.2f  ·  start %.0f°  ·  align %.2f".format(
                model.repState.name,
                model.primaryAngleDegrees,
                model.repProgress,
                model.startAngleDegrees ?: 0f,
                model.alignmentScore,
            ),
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
        // Dashes until a rep has counted, rather than 0.00 — a zero here would read as a rep
        // that scored nothing instead of no rep yet.
        Text(
            text = if (model.repCount == 0) {
                "last rep: —"
            } else {
                "last rep: peak %.2f  ·  %s".format(
                    model.lastRepPeakProgress,
                    if (violations.isEmpty()) "clean" else violations.joinToString("+") { it.label },
                )
            },
            color = if (violations.isEmpty()) Color.White.copy(alpha = 0.8f) else FlaggedColor,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun WorkoutControls(
    model: WorkoutViewModel,
    replayFile: File?,
    onOpenHistory: () -> Unit,
    onPickExercise: () -> Unit,
    onPickReplay: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Disabled while recording for the same reason the camera toggle is: a fixture
            // whose exercise changes halfway through is one no replay can interpret.
            Button(onClick = onPickExercise, enabled = model.recordingFile == null) {
                Text(model.exercise.label)
            }
            Button(onClick = { model.recalibrate() }) {
                Text("Recalibrate")
            }
            // Ends the set on the way out rather than leaving it open behind the history
            // screen — the athlete has stopped repping to go and read about repping.
            Button(
                onClick = {
                    model.endSet()
                    onOpenHistory()
                },
                enabled = model.recordingFile == null,
            ) {
                Text("History")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Disabled while recording: the two cameras produce landmarks in opposite
            // handedness, and a fixture that switches halfway through is one no replay can
            // interpret. Disabled during replay because there is no camera bound to switch.
            Button(
                onClick = { model.switchCamera() },
                enabled = replayFile == null && model.recordingFile == null,
            ) {
                Text(
                    if (model.lensFacing == CameraSelector.LENS_FACING_BACK) "Front" else "Back"
                )
            }
            Button(onClick = { model.toggleRecording() }, enabled = replayFile == null) {
                Text(if (model.recordingFile != null) "Stop recording" else "Record")
            }
            if (BuildConfig.DEBUG) {
                Button(onClick = onPickReplay, enabled = model.recordingFile == null) {
                    Text(if (replayFile != null) "Stop replay" else "Replay")
                }
            }
        }
    }
}

/**
 * The CameraX binding and the preview surface — the two things that genuinely cannot live in
 * the ViewModel, because both hold an Activity and a Lifecycle.
 *
 * Keyed on the ViewModel's lens rather than a local, so a rotation rebinds to the lens the
 * workout is actually on. The MediaPipe detector comes from the ViewModel and is **not**
 * rebuilt here: it survives configuration changes, which is what stops a rotation reloading
 * the 5.5 MB model. It is still rebuilt on a lens change, inside [WorkoutViewModel.landmarkerFor],
 * because `mirrorX` is immutable.
 */
@Composable
private fun LiveCamera(model: WorkoutViewModel, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val lensFacing = model.lensFacing
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
    }

    DisposableEffect(lifecycleOwner, lensFacing) {
        // Both the detector and the thread it runs on belong to the ViewModel, so neither is
        // created or destroyed here. That is what lets the detector's close be ordered behind
        // an in-flight frame — see WorkoutViewModel.landmarkerFor.
        val helper: PoseLandmarkerHelper = model.landmarkerFor(lensFacing)
        var provider: ProcessCameraProvider? = null
        val future = ProcessCameraProvider.getInstance(context)

        future.addListener({
            // Both use cases share an aspect ratio on purpose. The overlay maps landmarks
            // into the preview's letterboxed rect, which only lines up if they agree.
            val resolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()
            val preview = Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .build()
                .apply { setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .apply { setAnalyzer(model.analysisExecutor, PoseAnalyzer(helper)) }

            provider = future.get().also {
                it.unbindAll()
                val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                // Not every device has both lenses, and an unsatisfiable selector throws
                // rather than returning null. unbindAll() has already run by here, so the
                // preview goes blank either way — this turns a crash into a message, it
                // does not keep the old camera alive. Switching back re-binds.
                try {
                    it.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                } catch (error: IllegalArgumentException) {
                    model.error = "No camera for that lens: ${error.message}"
                }
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            // Unbinding is the whole teardown. It stops CameraX handing frames to the
            // analyzer, which is what "this binding is over" means; the detector and the
            // analysis thread both belong to the ViewModel and outlive a rotation.
            provider?.unbindAll()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/**
 * The start pose, shown and acknowledged **before** the countdown runs.
 *
 * This screen exists because of one measured failure. On the 20 Aug sweep the shoulder press
 * was calibrated with the arms already straight overhead — the finish pose — and captured 174
 * degrees against a 170-degree target. Every degree of movement then read as a quarter of a
 * rep and one rep recorded a peak of 38.75. The pose text was on screen at the time, small,
 * underneath a countdown that had already started. Describing the pose while the athlete is
 * being timed is not the same as describing it first and waiting.
 *
 * Three things are on it, in the order they are needed: which exercise, what to hold, and
 * whether the model can see you. The live angle is the last of those — a number that moves
 * when you move is the only evidence available that the pose is being tracked at all, and on
 * the exercises the sweep could not calibrate it is the difference between waiting patiently
 * and giving up.
 */
@Composable
private fun StartPoseCard(model: WorkoutViewModel) {
    Column(
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(24.dp))
            .padding(horizontal = 28.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (model.calibrationRefused) {
            // The engine refused the captured pose. Say what went wrong and what to do, and
            // name the finish pose explicitly — it is the mistake this guard almost always
            // catches, because it is where the athlete's body already is.
            Text(
                text = "THAT LOOKED LIKE THE FINISH",
                color = FlaggedColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "The pose you held is too close to the end of the movement, so almost " +
                    "anything would count as a rep. Start from the beginning of the movement.",
                color = Color.White,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
        } else {
            Text(
                text = "START POSE",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
        }

        Text(
            text = model.exercise.startPose,
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "This is where the movement STARTS, not where it finishes.",
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        Text(
            text = "%.0f°".format(model.primaryAngleDegrees),
            color = if (model.primaryAngleDegrees > 0f) {
                MaterialTheme.colorScheme.primary
            } else {
                Color.White.copy(alpha = 0.4f)
            },
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = if (model.primaryAngleDegrees > 0f) "tracking" else "not tracking you yet",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(18.dp))
        Button(onClick = { model.confirmPose() }) {
            Text(if (model.calibrationRefused) "Try again" else "I'm in position")
        }
    }
}

/**
 * Why the count is not moving, when the reason is the alignment gate.
 *
 * The gate refuses to start or continue a set unless the athlete is presented the way the
 * exercise needs — shoulders stacked for a side view, spread for a front one — and unless
 * every landmark it depends on is actually visible. Before this existed the same situation
 * produced a screen that simply did not count, which is indistinguishable from a broken app.
 *
 * Only shown once calibration is done, because before that the pose card owns the middle of
 * the screen and the gate is not what is being waited on.
 */
// The direction comes from Exercise.startPose, which already opens with "Facing the camera" or
// "Left side to the camera". Mirroring the native View enum into Kotlin purely to word this
// would be a second copy of a config fact for no gain — the sentence the athlete was shown on
// the pose card is the same sentence that tells them how to get back into it.
@Composable
private fun AlignmentPrompt(model: WorkoutViewModel, modifier: Modifier) {
    Column(
        modifier = modifier
            .background(FlaggedColor.copy(alpha = 0.92f), RoundedCornerShape(16.dp))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "NOT IN VIEW — NOT COUNTING",
            color = Color.Black,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = model.exercise.startPose,
            color = Color.Black.copy(alpha = 0.85f),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The calibration countdown, in the slot the rep count will occupy: hold the start pose
 * still and the number falls to zero, move and it goes back to the top.
 *
 * The live angle underneath it is the one being captured, and it is there because the
 * countdown alone cannot distinguish "holding still" from "MediaPipe has lost you" — a frame
 * with no pose never reaches here at all, so the screen would simply freeze mid-count.
 */
@Composable
private fun CalibrationReadout(model: WorkoutViewModel) {
    // Rounded up, so a hold that has just started reads the full count and the number only
    // reaches 0 on the frame that captures.
    val remainingSeconds = ((Calibrator.HOLD_MS - model.calibrationHeldMs + 999L) / 1000L)
        .coerceIn(0L, Calibrator.HOLD_MS / 1000L)
    Column(
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(24.dp))
            .padding(horizontal = 32.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "HOLD STILL",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = remainingSeconds.toString(),
            color = Color.White,
            fontSize = 128.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = model.exercise.startPose,
            color = Color.White,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "%.0f°".format(model.primaryAngleDegrees),
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The exercise table, straight off the enum that mirrors the native config table. Picking a
 * row restarts calibration, because the start pose of a curl is not the start pose of a
 * squat and the captured angle belongs to the pose.
 */
@Composable
private fun ExercisePicker(
    current: Exercise,
    onSelect: (Exercise) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Exercise") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Exercise.entries.forEach { exercise ->
                    TextButton(onClick = { onSelect(exercise) }) {
                        Text(
                            text = if (exercise == current) {
                                "${exercise.label}  ·  current"
                            } else {
                                exercise.label
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ReplayPicker(directory: File, onSelect: (File) -> Unit, onDismiss: () -> Unit) {
    val recordings = remember {
        directory.listFiles()
            ?.filter { it.extension == "jsonl" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Replay recording") },
        text = {
            if (recordings.isEmpty()) {
                Text("No recordings in ${directory.path}")
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    recordings.forEach { recording ->
                        TextButton(onClick = { onSelect(recording) }) { Text(recording.name) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
