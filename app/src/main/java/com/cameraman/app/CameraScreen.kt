package com.cameraman.app

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import java.util.Locale

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var useFrontCamera by remember { mutableStateOf(false) }
    var showGrid by remember { mutableStateOf(true) }
    var frameReading by remember { mutableStateOf<FrameReading?>(null) }
    var scanSession by remember { mutableStateOf<ScanSession?>(null) }
    var bestHeading by remember { mutableStateOf<Float?>(null) }
    var poseMode by remember { mutableStateOf(false) }
    var poseIndex by remember { mutableIntStateOf(0) }
    var sceneAnalyzer by remember { mutableStateOf<SceneAnalyzer?>(null) }
    val heading = rememberHeadingDegrees(enabled = scanSession != null)

    val previewView = remember { PreviewView(context) }
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
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val analyzer = SceneAnalyzer(faceDetector, poseDetector) { reading ->
            mainExecutor.execute {
                frameReading = reading
                val session = scanSession
                val currentHeading = heading.value
                if (session != null && bestHeading == null && currentHeading != null) {
                    session.record(currentHeading, reading)
                    bestHeading = session.bestHeading()
                }
            }
        }
        imageAnalysis.setAnalyzer(analysisExecutor, analyzer)
        sceneAnalyzer = analyzer
        onDispose {
            imageAnalysis.clearAnalyzer()
            faceDetector.close()
            poseDetector.close()
            analysisExecutor.shutdown()
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
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val cameraSelector = if (useFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            frameReading = null
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageCapture, imageAnalysis)
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            if (providerFuture.isDone) providerFuture.get().unbindAll()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        if (showGrid) GridOverlay(modifier = Modifier.fillMaxSize())
        LevelIndicator(modifier = Modifier.fillMaxSize())
        val session = scanSession
        if (poseMode) {
            PoseOverlay(
                reading = frameReading,
                isFrontCamera = useFrontCamera,
                targetPose = poseLibrary[poseIndex],
                onNextPose = { poseIndex = (poseIndex + 1) % poseLibrary.size },
                modifier = Modifier.fillMaxSize()
            )
        } else if (session == null) {
            CoachOverlay(reading = frameReading, isFrontCamera = useFrontCamera, modifier = Modifier.fillMaxSize())
        } else {
            ScanOverlay(
                session = session,
                heading = heading.value,
                bestHeading = bestHeading,
                onClose = {
                    scanSession = null
                    bestHeading = null
                },
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }

        LegendButton(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(16.dp)
        )

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { showGrid = !showGrid }) {
                Text(if (showGrid) "Grid on" else "Grid off", color = Color.White)
            }
            TextButton(onClick = {
                // Scan looks at the scene, so it always uses the back camera.
                useFrontCamera = false
                poseMode = false
                bestHeading = null
                scanSession = ScanSession()
            }) {
                Text("Scan", color = Color.White)
            }
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(8.dp)
                    .background(Color.White, CircleShape)
                    .clickable { takePhoto(context, imageCapture) }
            )
            TextButton(onClick = {
                poseMode = !poseMode
                scanSession = null
                bestHeading = null
            }) {
                Text(if (poseMode) "Pose on" else "Pose", color = Color.White)
            }
            TextButton(onClick = { useFrontCamera = !useFrontCamera }) {
                Text(if (useFrontCamera) "Front" else "Back", color = Color.White)
            }
        }
    }
}

private fun takePhoto(context: Context, imageCapture: ImageCapture) {
    val fileName = "CameraMan_" +
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
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
                Toast.makeText(context, "Saved to Pictures/CameraMan", Toast.LENGTH_SHORT).show()
            }

            override fun onError(exception: ImageCaptureException) {
                Toast.makeText(context, "Could not save photo: ${exception.message}", Toast.LENGTH_LONG).show()
            }
        }
    )
}
