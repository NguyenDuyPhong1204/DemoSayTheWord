package com.bigq.demodogcat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import com.bigq.demodogcat.opengl.CameraGLSurfaceView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun CaptureableOverlay(
    glSurfaceView: CameraGLSurfaceView?,
    isRecording: Boolean,
    overlayHeightFraction: Float = 0.25f,
    content: @Composable () -> Unit
){
    val graphicsLayer = rememberGraphicsLayer()
    val coroutineScope = rememberCoroutineScope()

    // Hiển thị overlay trên UI, đồng thời capture vào graphicsLayer
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .drawWithContent {
                graphicsLayer.record { this@drawWithContent.drawContent() }
                drawLayer(graphicsLayer)
            }
    ) {
        content()
    }

    // Setup vị trí overlay trong GL (toàn chiều rộng, phần trên)
    LaunchedEffect(Unit) {
        glSurfaceView?.setOverlayPosition(
            x = 0f,
            y = 0f,
            width = 1f,
            height = overlayHeightFraction
        )
    }

    // Capture bitmap mỗi frame và đẩy vào GL texture
    LaunchedEffect(isRecording) {
        if (isRecording) {
            while (isActive) {
                try {
                    val bitmap = graphicsLayer.toImageBitmap().asAndroidBitmap()
                    glSurfaceView?.setCustomOverlayBitmap(bitmap)
                } catch (_: Exception) {}
                delay(33L) // ~30fps
            }
        } else {
            glSurfaceView?.setCustomOverlayBitmap(null)
        }
    }
}