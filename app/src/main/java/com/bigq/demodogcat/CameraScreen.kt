package com.bigq.demodogcat

import android.graphics.Picture
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.util.Log
import android.view.Surface
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.draw
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bigq.demodogcat.Function.copyRawToCache
import com.bigq.demodogcat.Function.mergeVideoAndAudio
import com.bigq.demodogcat.opengl.CameraGLSurfaceView
import com.bigq.demodogcat.saytheword.BoxWordAnimation
import kotlinx.coroutines.delay
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current

    var glSurfaceView by remember { mutableStateOf<CameraGLSurfaceView?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingTime by remember { mutableStateOf("00:00") }
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    val overlayPicture = remember { Picture() }
    var overlaySize by remember { mutableStateOf(IntSize.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    val mediaPlayer = remember {
        MediaPlayer.create(context, R.raw.song)
    }

    // Update current time every 100ms
    fun captureOverlayNow() {
        if (overlaySize.width > 0 && overlaySize.height > 0) {

            val bitmap = createBitmap(overlaySize.width, overlaySize.height)
            val canvas = android.graphics.Canvas(bitmap)
            overlayPicture.draw(canvas)

            glSurfaceView?.setCustomOverlayBitmap(bitmap)

            if (containerSize.width > 0 && containerSize.height > 0) {

                val normalizedHeight =
                    overlaySize.height.toFloat() / containerSize.height

                glSurfaceView?.setOverlayPosition(
                    0f,
                    0f,
                    1f,
                    normalizedHeight
                )
            }
        }
    }
    // Setup camera when SurfaceTexture is ready
    fun setupCamera(surfaceTexture: SurfaceTexture) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                // Preview
                val preview = Preview.Builder().build()

                // Set preview surface
                preview.setSurfaceProvider { request ->
                    val width = request.resolution.width
                    val height = request.resolution.height
                    surfaceTexture.setDefaultBufferSize(width, height)

                    val surface = Surface(surfaceTexture)
                    request.provideSurface(surface, ContextCompat.getMainExecutor(context)) { }
                }

                // ImageCapture
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                // Camera selector
                val selector = CameraSelector.Builder()
                    .requireLensFacing(lensFacing)
                    .build()

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview,
                    capture
                )

                Log.d("CameraScreenOpenGL", "Camera bound successfully")

            } catch (e: Exception) {
                Log.e("CameraScreenOpenGL", "Camera bind error: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }
    LaunchedEffect(isRecording) {

        if (!isRecording) return@LaunchedEffect

        // capture intro frame
        captureOverlayNow()

        delay(5000)

        while (isRecording) {

            withFrameNanos { }

            captureOverlayNow()

            delay(17) // khớp nhạc
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onGloballyPositioned{
                containerSize = it.size
            }
    ) {
        // GLSurfaceView for camera preview
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                CameraGLSurfaceView(ctx).apply {
                    glSurfaceView = this
                    // Set camera facing
                    setFrontCamera(lensFacing == CameraSelector.LENS_FACING_FRONT)

                    // Callback when SurfaceTexture is ready
                    onSurfaceTextureReady = { surfaceTexture ->
                        setupCamera(surfaceTexture)
                    }

                    // Recording state callback
                    onRecordingStateChanged = { recording, path ->
                        isRecording = recording

                        if (!recording && path != null) {

                            val audioPath = copyRawToCache(context, R.raw.song)

                            val outputPath = path.replace(".mp4", "_final.mp4")

                            mergeVideoAndAudio(
                                videoPath = path,
                                audioPath = audioPath,
                                outputPath = outputPath
                            )

                            Toast.makeText(context, "Video đã có nhạc!", Toast.LENGTH_LONG).show()
                        }
                    }

                    // Time update callback
                    onTimeUpdate = { time ->
                        recordingTime = time
                    }
                }
            },
            update = { view ->
                view.setFrontCamera(lensFacing == CameraSelector.LENS_FACING_FRONT)
            }
        )

        // === COMPOSABLE OVERLAY ===
        // Overlay này hiển thị trên UI và được capture gửi đến OpenGL để vẽ lên video
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .onGloballyPositioned {
                    overlaySize = it.size
                }
                .drawWithCache {
                    val width = this.size.width.toInt()
                    val height = this.size.height.toInt()
                    onDrawWithContent {
                        // Capture vào Picture để gửi đến OpenGL
                        val nativeCanvas = overlayPicture.beginRecording(width, height)
                        val composeCanvas = Canvas(nativeCanvas)
                        draw(this, this.layoutDirection, composeCanvas, this.size) {
                            this@onDrawWithContent.drawContent()
                        }
                        overlayPicture.endRecording()

                        // Vẽ lên màn hình
                        drawContent()
                    }
                }
                .align(Alignment.TopCenter)
        ) {
            BoxWordAnimation(
                isRecording = isRecording,
            )
        }
        // === KẾT THÚC COMPOSABLE OVERLAY ===

        // Recording indicator at top-center
        if (isRecording) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp)
                    .background(Color.Red.copy(alpha = 0.85f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Blinking dot
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .background(Color.White, CircleShape)
                )
                Text(
                    text = "⏺ REC  $recordingTime",
                    color = Color.White,
                    fontSize = 18.sp
                )
            }
        }

        // Control buttons at bottom
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(32.dp)
                .navigationBarsPadding(),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Switch camera button
            Button(
                enabled = !isRecording,
                onClick = {
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                        CameraSelector.LENS_FACING_FRONT
                    } else {
                        CameraSelector.LENS_FACING_BACK
                    }

                    // Re-setup camera
                    glSurfaceView?.getSurfaceTexture()?.let { st ->
                        setupCamera(st)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
            ) {
                Text("🔄", fontSize = 20.sp)
            }

            // Record button
            Button(
                modifier = Modifier.size(80.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) Color.Red else Color.White
                ),
                onClick = {
                    if (isRecording) {
                        mediaPlayer.pause()
                        mediaPlayer.seekTo(0)
                        glSurfaceView?.stopRecording(context)
                    } else {
                        mediaPlayer.start()
                        glSurfaceView?.startRecording(context)
                    }
                }
            ) {
                if (isRecording) {
                    // Stop icon (square)
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(Color.White, RoundedCornerShape(4.dp))
                    )
                } else {
                    // Record icon (red circle)
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(Color.Red, CircleShape)
                    )
                }
            }

            // Placeholder for symmetry
            Button(
                enabled = false,
                onClick = {},
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent
                )
            ) {
                Text("  ", fontSize = 20.sp)
            }
        }
    }

    // Re-setup camera when lensFacing changes
    LaunchedEffect(lensFacing) {
        glSurfaceView?.getSurfaceTexture()?.let { st ->
            setupCamera(st)
        }
    }

    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            glSurfaceView?.release()
        }
    }
}