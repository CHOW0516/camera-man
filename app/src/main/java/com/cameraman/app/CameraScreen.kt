package com.cameraman.app

import android.content.ContentValues
import android.os.SystemClock
import androidx.camera.core.Camera
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Brush
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.launch
import kotlin.math.hypot
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.concurrent.thread
import kotlinx.coroutines.delay
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.text.SimpleDateFormat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Locale

/** Wait after switching to a recipe's manual shutter, so the sensor has changed before the photo is taken. */
private const val MANUAL_SETTLE_MILLIS = 300L

/** Focus and metering move to the face at most this often. */
private const val FACE_FOCUS_EVERY_MILLIS = 2000L

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var useFrontCamera by remember { mutableStateOf(false) }
    var showGrid by remember { mutableStateOf(true) }
    var frameReading by remember { mutableStateOf<FrameReading?>(null) }
    var scoutSession by remember { mutableStateOf<ScoutSession?>(null) }
    var selectedView by remember { mutableStateOf<ScoutView?>(null) }
    var aiPick by remember { mutableStateOf<ScoutView?>(null) }
    var askingAi by remember { mutableStateOf(false) }
    var viewJudge by remember { mutableStateOf<ViewJudge?>(null) }
    var lastJudgement by remember { mutableStateOf<ViewJudgement?>(null) }
    var liveScore by remember { mutableStateOf<Int?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var poseMode by remember { mutableStateOf(false) }
    var poseIndex by remember { mutableIntStateOf(0) }
    var sceneAnalyzer by remember { mutableStateOf<SceneAnalyzer?>(null) }
    var photoFilter by remember { mutableStateOf(PhotoFilter.Original) }
    var showFilters by remember { mutableStateOf(false) }
    // Scene recipe: auto-detected, picked by the user, or off (picked = null).
    var autoScene by remember { mutableStateOf(true) }
    var pickedScene by remember { mutableStateOf<Scene?>(null) }
    var detectedScene by remember { mutableStateOf(Scene.DaytimePortrait) }
    var showScenes by remember { mutableStateOf(false) }
    val activeScene = if (autoScene) detectedScene else pickedScene
    val latestScene = rememberUpdatedState(activeScene)
    val lightMeter = remember { LightMeter() }
    var meterReading by remember { mutableStateOf<MeterReading?>(null) }
    val sceneDetector = remember { SceneDetector() }
    // The place around the person (street, sea, café...), ignoring the person, to suggest poses.
    val placeDetector = remember { SceneDetector() }
    var detectedPlace by remember { mutableStateOf<Scene?>(null) }
    var lastFaceFocusMillis by remember { mutableLongStateOf(0L) }
    val coroutineScope = rememberCoroutineScope()
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    val heading = rememberHeadingDegrees(enabled = scoutSession != null)
    val phoneTilt = rememberPhoneTilt()
    val latestTilt = rememberUpdatedState(phoneTilt)

    // A scene the user picked by hand is also the place; in auto mode the place comes from what is around the person.
    val posePlace = if (autoScene) detectedPlace else pickedScene
    val poseChoices = remember(posePlace, activeScene) { posesFor(posePlace, activeScene) }
    // A new place or scene starts again from its best pose.
    LaunchedEffect(poseChoices.first()) { poseIndex = 0 }

    val zoomState = camera?.cameraInfo?.zoomState?.value
    val zoom = zoomState?.let { ZoomInfo(zoomRatio, it.minZoomRatio, it.maxZoomRatio) }
    fun setZoom(ratio: Float) {
        val currentCamera = camera ?: return
        val range = currentCamera.cameraInfo.zoomState.value ?: return
        zoomRatio = ratio.coerceIn(range.minZoomRatio, range.maxZoomRatio)
        currentCamera.cameraControl.setZoomRatio(zoomRatio)
    }

    fun stopScout() {
        scoutSession = null
        selectedView = null
        aiPick = null
        askingAi = false
    }

    /** Asks Gemini which of the kept views is best (only when the user set a key). */
    fun askAi(session: ScoutSession) {
        val apiKey = loadGeminiKey(context) ?: return
        val views = session.bestViews
        if (views.size < 2) return
        askingAi = true
        thread {
            val choice = askGeminiForBestView(apiKey, views, activeScene?.title ?: "photo")
            mainExecutor.execute {
                // Ignore the answer if the user closed or restarted scouting meanwhile.
                if (scoutSession !== session) return@execute
                askingAi = false
                aiPick = choice?.let { views.getOrNull(it) }
                if (aiPick == null) Toast.makeText(context, "Gemini did not answer", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // TextureView mode (COMPATIBLE) is needed so the filter paint below reaches the camera image.
    val previewView = remember {
        PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
    }
    val imageAnalysis = remember {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    // Frame analysis runs on its own thread; results are posted back to the main thread.
    DisposableEffect(Unit) {
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val faceDetector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build()
        )
        val poseDetector = PoseDetection.getClient(
            PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                .build()
        )
        val imageLabeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
        val analyzer = SceneAnalyzer(faceDetector, poseDetector, imageLabeler) { reading ->
            mainExecutor.execute {
                frameReading = reading
                meterReading = lightMeter.latest
                val scout = scoutSession
                if (reading.labels != null) {
                    val hasPerson = poseMode || reading.faceBox != null || reading.bodyPoints != null
                    detectedScene = sceneDetector.update(guessScene(reading.labels, hasPerson, lightMeter.latest?.ev100))
                    detectedPlace = placeDetector.update(guessScene(reading.labels, hasPerson = false, lightMeter.latest?.ev100))
                }
                val currentCamera = camera
                val now = SystemClock.elapsedRealtime()
                if (reading.faceBox != null && scout == null && latestScene.value != null && currentCamera != null &&
                    now - lastFaceFocusMillis > FACE_FOCUS_EVERY_MILLIS && previewView.height > 0
                ) {
                    lastFaceFocusMillis = now
                    val viewAspectRatio = previewView.width.toFloat() / previewView.height
                    focusOnFace(
                        currentCamera, previewView,
                        toScreenCoordinates(reading.faceBox, reading.imageAspectRatio, viewAspectRatio, useFrontCamera)
                    )
                }
                val judgement = reading.judgement
                if (judgement != null) {
                    lastJudgement = judgement
                    val tooDark = latestScene.value?.let { scene -> lightMeter.latest?.let { scene.isTooDark(it) } } ?: false
                    val score = spotScore(judgement, reading, tooDark)
                    liveScore = score
                    if (scout != null && reading.viewBitmap != null) {
                        scout.offer(ScoutView(reading.viewBitmap, score, judgement.embedding, heading.value, latestTilt.value?.pitchDegrees))
                    }
                }
            }
        }
        imageAnalysis.setAnalyzer(analysisExecutor, analyzer)
        sceneAnalyzer = analyzer
        // The AI models take a moment to load; the camera works without them meanwhile.
        val isDisposed = AtomicBoolean(false)
        thread {
            val judge = try {
                ViewJudge(context).also { it.loadReferences(context) }
            } catch (exception: Exception) {
                mainExecutor.execute {
                    Toast.makeText(context, "AI view score not available: ${exception.message}", Toast.LENGTH_LONG).show()
                }
                null
            }
            if (judge != null && isDisposed.get()) {
                judge.close()
            } else if (judge != null) {
                analyzer.viewJudge = judge
                mainExecutor.execute { viewJudge = judge }
            }
        }
        onDispose {
            isDisposed.set(true)
            imageAnalysis.clearAnalyzer()
            // Closed on the analysis thread, after the frame it may be judging.
            analysisExecutor.execute { analyzer.viewJudge?.close() }
            faceDetector.close()
            poseDetector.close()
            imageLabeler.close()
            analysisExecutor.shutdown()
        }
    }

    LaunchedEffect(photoFilter, activeScene) {
        val paint = combinedPaint(photoFilter, activeScene?.look)
        previewView.setLayerType(if (paint == null) View.LAYER_TYPE_NONE else View.LAYER_TYPE_HARDWARE, paint)
    }

    LaunchedEffect(sceneAnalyzer, scoutSession, activeScene) {
        sceneAnalyzer?.scouting = scoutSession != null
        sceneAnalyzer?.judgeScene = activeScene
    }

    // Applies the scene recipe's exposure compensation and white balance.
    LaunchedEffect(camera, activeScene) {
        val currentCamera = camera ?: return@LaunchedEffect
        setEvCompensation(currentCamera, activeScene?.evCompensation ?: 0f)
        applyCameraOptions(currentCamera, activeScene?.whiteBalanceMode(), manual = null)
    }

    /** Takes a photo with the scene's look; uses the recipe's shutter when auto would pick a slower one. */
    fun shoot() {
        val scene = activeScene
        val look = scene?.look
        val currentCamera = camera
        val meter = lightMeter.latest
        val manual = if (scene != null && currentCamera != null && meter != null) {
            manualExposureFor(currentCamera, scene, meter)
        } else {
            null
        }
        if (currentCamera == null || scene == null || manual == null) {
            takePhoto(context, imageCapture, photoFilter, look, onDone = {})
            return
        }
        val whiteBalance = scene.whiteBalanceMode()
        applyCameraOptions(currentCamera, whiteBalance, manual = manual)
        coroutineScope.launch {
            delay(MANUAL_SETTLE_MILLIS)
            takePhoto(context, imageCapture, photoFilter, look, onDone = {
                applyCameraOptions(currentCamera, whiteBalance, manual = null)
            })
        }
    }

    LaunchedEffect(sceneAnalyzer, poseMode) {
        sceneAnalyzer?.usePoseDetection = poseMode
        frameReading = null
    }

    // Rebind the camera whenever the user switches between front and back.
    DisposableEffect(useFrontCamera) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val cameraProvider = providerFuture.get()
            val preview = previewWithMeter(lightMeter).also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val cameraSelector = if (useFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            frameReading = null
            zoomRatio = 1f
            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageCapture, imageAnalysis)
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            if (providerFuture.isDone) providerFuture.get().unbindAll()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                // Pinch to zoom anywhere on the preview.
                detectTransformGestures { _, _, zoomChange, _ -> setZoom(zoomRatio * zoomChange) }
            }
    ) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        val vignette = activeScene?.look?.vignette ?: 0f
        if (vignette > 0f) {
            // Preview of the look's darker corners (the saved photo gets the same).
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    Brush.radialGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = vignette * 0.5f)),
                        center = center,
                        radius = hypot(size.width, size.height) / 2
                    )
                )
            }
        }

        if (showGrid) GridOverlay(modifier = Modifier.fillMaxSize())
        LevelIndicator(tilt = phoneTilt, modifier = Modifier.fillMaxSize())
        val scout = scoutSession
        val tooDarkForScene = activeScene?.let { scene -> meterReading?.let { scene.isTooDark(it) } }
        if (poseMode) {
            PoseOverlay(
                reading = frameReading,
                isFrontCamera = useFrontCamera,
                targetPose = poseChoices[poseIndex],
                poseNumber = poseIndex + 1,
                poseCount = poseChoices.size,
                isSuggested = poseIndex < SUGGESTED_POSE_COUNT && isSuggested(poseChoices[poseIndex], posePlace, activeScene),
                onNextPose = { poseIndex = (poseIndex + 1) % poseChoices.size },
                onPreviousPose = { poseIndex = (poseIndex - 1 + poseChoices.size) % poseChoices.size },
                zoom = zoom,
                pitchDegrees = phoneTilt?.pitchDegrees,
                tooDarkForScene = tooDarkForScene,
                onZoom = ::setZoom,
                modifier = Modifier.fillMaxSize()
            )
        } else if (scout == null) {
            CoachOverlay(
                reading = frameReading,
                isFrontCamera = useFrontCamera,
                zoom = zoom,
                pitchDegrees = phoneTilt?.pitchDegrees,
                scene = activeScene,
                tooDarkForScene = tooDarkForScene,
                judgement = lastJudgement,
                onZoom = ::setZoom,
                modifier = Modifier.fillMaxSize()
            )
            ScoreRing(
                liveScore,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(16.dp)
            )
        } else {
            ScoutOverlay(
                session = scout,
                liveScore = liveScore,
                selected = selectedView,
                heading = heading.value,
                pitchDegrees = phoneTilt?.pitchDegrees,
                currentEmbedding = lastJudgement?.embedding,
                aiPick = aiPick,
                askingAi = askingAi,
                canAskAi = loadGeminiKey(context) != null && scout.bestViews.size >= 2,
                onAskAi = { askAi(scout) },
                onSelect = { selectedView = it },
                onClose = ::stopScout,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }

        LegendButton(
            onAddReferences = { uris ->
                thread {
                    val saved = saveReferencePhotos(context, uris)
                    try {
                        viewJudge?.loadReferences(context)
                    } catch (exception: Exception) {
                        // Keeps the old references; the new photos are used next time the app starts.
                    }
                    mainExecutor.execute {
                        Toast.makeText(context, "Added $saved photos to compare with", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(16.dp)
        )
        SceneChip(
            scene = activeScene,
            isAuto = autoScene,
            meter = meterReading,
            onClick = { showScenes = !showScenes },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(16.dp)
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (showScenes) {
                ScenePicker(
                    isAuto = autoScene,
                    picked = pickedScene,
                    onAuto = { autoScene = true },
                    onPick = {
                        autoScene = false
                        pickedScene = it
                    }
                )
            }
            if (showFilters) {
                FilterPicker(selected = photoFilter, onSelect = { photoFilter = it })
            }
            if (zoom != null && zoom.max > zoom.min) {
                // Shows the zoom; tap to jump between 1x and 2x.
                TextButton(
                    onClick = { setZoom(if (zoomRatio < 1.5f) 2f else 1f) },
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.35f), CircleShape)
                ) {
                    Text(String.format(Locale.US, "%.1fx", zoomRatio), color = Color.White)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ControlButton(
                    ControlIcon.Filter,
                    active = showFilters || photoFilter != PhotoFilter.Original,
                    onClick = { showFilters = !showFilters }
                )
                ControlButton(ControlIcon.Grid, active = showGrid, onClick = { showGrid = !showGrid })
                ControlButton(ControlIcon.Scan, active = scoutSession != null, onClick = {
                    if (scoutSession != null) {
                        stopScout()
                    } else {
                        // Scouting looks at the scene, so it always uses the back camera.
                        useFrontCamera = false
                        poseMode = false
                        scoutSession = ScoutSession()
                    }
                })
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(8.dp)
                        .background(Color.White, CircleShape)
                        .clickable { shoot() }
                )
                ControlButton(ControlIcon.Pose, active = poseMode, onClick = {
                    poseMode = !poseMode
                    stopScout()
                })
                ControlButton(ControlIcon.Flip, active = useFrontCamera, onClick = { useFrontCamera = !useFrontCamera })
            }
        }
    }
}

/** Saves a photo; [onDone] runs on the main thread once the camera has finished, whether or not it worked. */
private fun takePhoto(context: Context, imageCapture: ImageCapture, filter: PhotoFilter, look: PhotoLook?, onDone: () -> Unit) {
    val fileName = "CameraMan_" +
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
    if (filter != PhotoFilter.Original || look != null) {
        takeFilteredPhoto(context, imageCapture, filter, look, fileName, onDone)
        return
    }
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraMan")
        }
    }
    val outputOptions = ImageCapture.OutputFileOptions.Builder(
        context.contentResolver,
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        contentValues
    ).build()

    imageCapture.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                onDone()
                Toast.makeText(context, "Saved to Pictures/CameraMan", Toast.LENGTH_SHORT).show()
            }

            override fun onError(exception: ImageCaptureException) {
                onDone()
                Toast.makeText(context, "Could not save photo: ${exception.message}", Toast.LENGTH_LONG).show()
            }
        }
    )
}

/** Captures into memory, applies the look and filter, then saves. Runs off the main thread. */
private fun takeFilteredPhoto(
    context: Context,
    imageCapture: ImageCapture,
    filter: PhotoFilter,
    look: PhotoLook?,
    fileName: String,
    onDone: () -> Unit
) {
    val mainExecutor = ContextCompat.getMainExecutor(context)
    imageCapture.takePicture(
        photoExecutor,
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                // The camera is free again; editing and saving can take a moment.
                mainExecutor.execute { onDone() }
                val saved = try {
                    val bitmap = image.use { filteredBitmap(it, filter, look) }
                    saveJpeg(context, bitmap, fileName).also { bitmap.recycle() }
                } catch (exception: Exception) {
                    false
                }
                mainExecutor.execute {
                    val message = if (saved) "Saved to Pictures/CameraMan" else "Could not save photo"
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                mainExecutor.execute {
                    onDone()
                    Toast.makeText(context, "Could not save photo: ${exception.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    )
}

private val photoExecutor = Executors.newSingleThreadExecutor()
