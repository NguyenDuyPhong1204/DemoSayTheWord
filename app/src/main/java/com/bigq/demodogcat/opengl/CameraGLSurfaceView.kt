package com.bigq.demodogcat.opengl

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import com.bigq.demodogcat.saytheword.WordItem
import com.bigq.demodogcat.saytheword.wordList
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class CameraGLSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs), GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "CameraGLSurfaceView"
        private const val MIME_TYPE = "video/avc"
        private const val BIT_RATE = 8_000_000
        private const val FRAME_RATE = 30
        private const val I_FRAME_INTERVAL = 1
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }

    // Camera texture
    private var cameraTextureId = 0
    private var cameraSurfaceTexture: SurfaceTexture? = null
    private val stMatrix = FloatArray(16)
    // ===== GRID DATA =====
    private val columnCount = 4
    private val rowCount = 2
    private val totalItems = columnCount * rowCount
    // ===== ANIMATION =====
    private var overlayAnimStart = 0L
    // Overlay texture
    private var overlayTextureId = 0
    private var overlayBitmap: Bitmap? = null
    private var overlayText = ""
    private var needsOverlayUpdate = false

    // Custom overlay bitmap từ Composable
    private var customOverlayBitmap: Bitmap? = null
    private var useCustomOverlay = false

    // Overlay position (normalized 0-1)
    private var overlayNormalizedX = 0.02f
    private var overlayNormalizedY = 0.02f
    private var overlayNormalizedWidth = 0.5f
    private var overlayNormalizedHeight = 0.15f

    // Shader programs
    private var cameraProgram = 0
    private var overlayProgram = 0

    // Buffers
    private var vertexBuffer: FloatBuffer? = null
    private var cameraTexBuffer: FloatBuffer? = null
    private var overlayTexBuffer: FloatBuffer? = null

    // Dimensions
    private var viewWidth = 0
    private var viewHeight = 0

    // Camera resolution for aspect ratio calculation
    private var cameraWidth = 0
    private var cameraHeight = 0

    // Recording state
    private var isRecording = false
    private var recordingStartTime = 0L  // milliseconds for UI display
    private var recordingStartTimeNano = 0L  // nanoseconds for presentation time

    // Encoder
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var encoderSurface: Surface? = null
    private var encoderEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var videoTrackIndex = -1
    private var muxerStarted = false
    private var bufferInfo = MediaCodec.BufferInfo()
    private var outputFile: File? = null
    private var encoderWidth = 0
    private var encoderHeight = 0

    // EGL for encoding
    private var sharedEglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var encoderEglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglConfig: android.opengl.EGLConfig? = null

    private var overlayAnimStartTime = 0L
    private var overlayAnimDuration = 600_000_000L // 600ms (nano)
    private var overlayAnimProgress = 1f
    private var overlayAnimating = false

    private var overlayPosHandle = 0
    private var overlayTexHandle = 0
    private var overlayHighlightHandle = 0
    private var overlayBorderColorHandle = 0

    // Camera facing
    private var isFrontCamera = false

    // Callbacks
    var onSurfaceTextureReady: ((SurfaceTexture) -> Unit)? = null
    var onRecordingStateChanged: ((Boolean, String?) -> Unit)? = null
    var onTimeUpdate: ((String) -> Unit)? = null

    // Shaders
    private val cameraVertexShader = """
        uniform mat4 uMVPMatrix;
        uniform mat4 uSTMatrix;
        uniform float uProgress;
        attribute vec4 aPosition;
        attribute vec4 aTextureCoord;
        varying vec2 vTextureCoord;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
            gl_Position.y += (1.0 - uProgress) * 0.3;
            vTextureCoord = (uSTMatrix * aTextureCoord).xy;
        }
    """.trimIndent()

    private val cameraFragmentShader = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTextureCoord;
        uniform samplerExternalOES sTexture;
        void main() {
            gl_FragColor = texture2D(sTexture, vTextureCoord);
        }
    """.trimIndent()

    private val overlayVertexShader = """
attribute vec4 aPosition;
attribute vec2 aTexCoord;

uniform float uScale;
uniform float uAlpha;

varying vec2 vTexCoord;
varying float vAlpha;

void main() {
    vec4 pos = aPosition;
    pos.xy *= uScale;
    gl_Position = pos;

    vTexCoord = aTexCoord;
    vAlpha = uAlpha;
}
    """.trimIndent()

    private val overlayFragmentShader = """
precision mediump float;

varying vec2 vTexCoord;
varying float vAlpha;

uniform sampler2D uTexture;
uniform float uHighlight;

void main() {

    vec4 color = texture2D(uTexture, vTexCoord);

    float border = 0.04;

    if (uHighlight > 0.5 &&
        (vTexCoord.x < border || vTexCoord.x > 1.0 - border ||
         vTexCoord.y < border || vTexCoord.y > 1.0 - border)) {

        color = vec4(0.0,1.0,0.0,1.0);
    }

    color.a *= vAlpha;

    gl_FragColor = color;
}
""".trimIndent()

    init {
        setEGLContextClientVersion(2)

        // Custom EGLConfigChooser để chọn config hỗ trợ recording
        setEGLConfigChooser(RecordableEGLConfigChooser())

        preserveEGLContextOnPause = true
        setRenderer(this)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    /**
     * Custom EGLConfigChooser để chọn EGL config hỗ trợ EGL_RECORDABLE_ANDROID
     * Điều này cho phép sử dụng cùng context cho cả preview và encoder
     */
    private inner class RecordableEGLConfigChooser : EGLConfigChooser {
        override fun chooseConfig(
            egl: EGL10,
            display: javax.microedition.khronos.egl.EGLDisplay,
        ): EGLConfig {
            val configSpec = intArrayOf(
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_RENDERABLE_TYPE, 4, // EGL_OPENGL_ES2_BIT
                EGL_RECORDABLE_ANDROID, 1,
                EGL10.EGL_NONE
            )

            val numConfigs = IntArray(1)
            if (!egl.eglChooseConfig(display, configSpec, null, 0, numConfigs)) {
                Timber.tag(TAG).w("Failed to get recordable config count, falling back")
                return chooseConfigFallback(egl, display)
            }

            if (numConfigs[0] <= 0) {
                Timber.tag(TAG).w("No recordable configs found, falling back")
                return chooseConfigFallback(egl, display)
            }

            val configs = arrayOfNulls<EGLConfig>(numConfigs[0])
            if (!egl.eglChooseConfig(display, configSpec, configs, numConfigs[0], numConfigs)) {
                Timber.tag(TAG).w("Failed to get recordable configs, falling back")
                return chooseConfigFallback(egl, display)
            }

            Timber.tag(TAG).d("Found ${numConfigs[0]} recordable EGL configs")
            return configs[0] ?: chooseConfigFallback(egl, display)
        }

        private fun chooseConfigFallback(
            egl: EGL10,
            display: javax.microedition.khronos.egl.EGLDisplay,
        ): EGLConfig {
            // Fallback: config không có EGL_RECORDABLE_ANDROID
            val configSpec = intArrayOf(
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_RENDERABLE_TYPE, 4,
                EGL10.EGL_NONE
            )

            val numConfigs = IntArray(1)
            egl.eglChooseConfig(display, configSpec, null, 0, numConfigs)
            val configs = arrayOfNulls<EGLConfig>(numConfigs[0])
            egl.eglChooseConfig(display, configSpec, configs, numConfigs[0], numConfigs)

            Timber.tag(TAG).d("Using fallback EGL config (not recordable)")
            return configs[0]!!
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        Timber.tag(TAG).d("onSurfaceCreated")

        GLES20.glClearColor(0f, 0f, 0f, 1f)

        // Get shared EGL context
        eglDisplay = EGL14.eglGetCurrentDisplay()
        sharedEglContext = EGL14.eglGetCurrentContext()

        // Get EGL config
        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
        eglConfig = configs[0]

        // Init buffers
        initBuffers()

        // Create textures
        val textures = IntArray(2)
        GLES20.glGenTextures(2, textures, 0)

        // Camera texture (OES)
        cameraTextureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )

        // Overlay texture (2D)
        overlayTextureId = textures[1]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )

        // Create SurfaceTexture for camera
        cameraSurfaceTexture = SurfaceTexture(cameraTextureId)

        // Create shader programs
        cameraProgram = createProgram(cameraVertexShader, cameraFragmentShader)
        overlayProgram = createProgram(overlayVertexShader, overlayFragmentShader)
        overlayPosHandle = GLES20.glGetAttribLocation(overlayProgram, "aPosition")
        overlayTexHandle = GLES20.glGetAttribLocation(overlayProgram, "aTextureCoord")
        overlayHighlightHandle = GLES20.glGetUniformLocation(overlayProgram, "uHighlight")
        overlayBorderColorHandle = GLES20.glGetUniformLocation(overlayProgram, "uBorderColor")
        Matrix.setIdentityM(stMatrix, 0)

        // Notify callback
        post {
            cameraSurfaceTexture?.let { onSurfaceTextureReady?.invoke(it) }
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        Timber.tag(TAG).d("onSurfaceChanged: $width x $height")
        viewWidth = width
        viewHeight = height
        GLES20.glViewport(0, 0, width, height)
        updateCameraTexCoords()  // Cập nhật center-crop khi view size thay đổi
        needsOverlayUpdate = true
    }

    override fun onDrawFrame(gl: GL10?) {
        drawCamera()
        // Update camera texture
        val elapsed = System.nanoTime() - overlayAnimStartTime
        overlayAnimProgress = (elapsed / overlayAnimDuration.toFloat()).coerceAtMost(1f)
        try {
            cameraSurfaceTexture?.updateTexImage()
            cameraSurfaceTexture?.getTransformMatrix(stMatrix)
        } catch (e: Exception) {
            // Ignore
        }

        // Update overlay if needed
        if (useCustomOverlay) {
            // Custom overlay - cập nhật mỗi frame khi recording để thời gian chạy realtime
            // hoặc khi có bitmap mới
            if (customOverlayBitmap != null) {
                updateOverlayTexture()
            }
        } else if (needsOverlayUpdate || isRecording) {
            // Internal overlay
            updateOverlayTexture()
        }

        // Draw to screen
        GLES20.glViewport(0, 0, viewWidth, viewHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawCamera()
        if (overlayAnimating) {
            val elapsed = System.nanoTime() - overlayAnimStartTime
            overlayAnimProgress = (elapsed.toFloat() / overlayAnimDuration)
                .coerceAtMost(1f)

            if (overlayAnimProgress >= 1f) {
                overlayAnimating = false
            }
        }

        // If recording, also draw to encoder surface (luôn vẽ overlay vào video)
        if (isRecording && encoderEglSurface != EGL14.EGL_NO_SURFACE) {
            drawToEncoder()
        }
    }

    private fun initBuffers() {
        // Full-screen quad vertices
        val vertices = floatArrayOf(
            -1f, -1f, 0f,
            1f, -1f, 0f,
            -1f, 1f, 0f,
            1f, 1f, 0f
        )
        vertexBuffer = createBuffer(vertices)

        // Camera texture coords - sẽ được cập nhật khi biết camera resolution
        val cameraTex = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
        )
        cameraTexBuffer = createBuffer(cameraTex)

        // Overlay texture coords
        val overlayTex = floatArrayOf(
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f
        )
        overlayTexBuffer = createBuffer(overlayTex)
    }

    /**
     * Cập nhật texture coordinates cho center-crop
     * Tính toán phần nào của camera texture cần hiển thị để giữ đúng aspect ratio
     */
    private fun updateCameraTexCoords() {
        if (viewWidth == 0 || viewHeight == 0 || cameraWidth == 0 || cameraHeight == 0) {
            return
        }

        val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()

        // Camera resolution thường được báo cáo theo hướng của sensor (landscape)
        // Nhưng khi hiển thị trên điện thoại portrait, hình ảnh đã được xoay 90 độ
        // Nên cần swap width/height nếu view và camera có orientation khác nhau
        val viewIsPortrait = viewHeight > viewWidth
        val cameraIsLandscape = cameraWidth > cameraHeight

        val effectiveCameraWidth: Int
        val effectiveCameraHeight: Int

        if (viewIsPortrait && cameraIsLandscape) {
            // View portrait, camera landscape -> swap camera dimensions
            effectiveCameraWidth = cameraHeight
            effectiveCameraHeight = cameraWidth
        } else if (!viewIsPortrait && !cameraIsLandscape) {
            // View landscape, camera portrait -> swap camera dimensions
            effectiveCameraWidth = cameraHeight
            effectiveCameraHeight = cameraWidth
        } else {
            // Same orientation, no swap needed
            effectiveCameraWidth = cameraWidth
            effectiveCameraHeight = cameraHeight
        }

        val cameraAspect = effectiveCameraWidth.toFloat() / effectiveCameraHeight.toFloat()

        var texLeft = 0f
        var texRight = 1f
        var texTop = 0f
        var texBottom = 1f

        if (cameraAspect > viewAspect) {
            // Camera rộng hơn view -> crop bên trái/phải
            val scale = viewAspect / cameraAspect
            val offset = (1f - scale) / 2f
            texLeft = offset
            texRight = 1f - offset
        } else if (cameraAspect < viewAspect) {
            // Camera cao hơn view -> crop trên/dưới
            val scale = cameraAspect / viewAspect
            val offset = (1f - scale) / 2f
            texTop = offset
            texBottom = 1f - offset
        }
        // Nếu aspect ratio bằng nhau, không cần crop

        Timber.tag(TAG).d(
            "%snull", "Center-crop: viewAspect=$viewAspect, cameraAspect=$cameraAspect, " +
                    "effective=${effectiveCameraWidth}x${effectiveCameraHeight}, "
        )

        // Cập nhật texture coordinates
        val cameraTex = floatArrayOf(
            texLeft, texTop,      // bottom-left
            texRight, texTop,     // bottom-right
            texLeft, texBottom,   // top-left
            texRight, texBottom   // top-right
        )
        cameraTexBuffer = createBuffer(cameraTex)
    }

    private fun createBuffer(data: FloatArray): FloatBuffer {
        return ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(data)
            .apply { position(0) }
    }

    private fun drawCamera() {
        GLES20.glUseProgram(cameraProgram)

        val posHandle = GLES20.glGetAttribLocation(cameraProgram, "aPosition")
        val texHandle = GLES20.glGetAttribLocation(cameraProgram, "aTextureCoord")
        val mvpHandle = GLES20.glGetUniformLocation(cameraProgram, "uMVPMatrix")
        val stHandle = GLES20.glGetUniformLocation(cameraProgram, "uSTMatrix")
        val samplerHandle = GLES20.glGetUniformLocation(cameraProgram, "sTexture")

        // MVP matrix - không flip để hiển thị đúng như thực tế
        val mvp = FloatArray(16)
        Matrix.setIdentityM(mvp, 0)
        // Bỏ flip để camera trước hiển thị giống camera gốc (không bị gương)

        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glEnableVertexAttribArray(texHandle)

        vertexBuffer?.position(0)
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        cameraTexBuffer?.position(0)
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, cameraTexBuffer)

        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(stHandle, 1, false, stMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glUniform1i(samplerHandle, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(posHandle)
        GLES20.glDisableVertexAttribArray(texHandle)
    }

    private fun drawOverlay(targetWidth: Int, targetHeight: Int) {

        // Sử dụng custom overlay nếu có, không thì dùng internal overlay
        val bitmapToUse = if (useCustomOverlay && customOverlayBitmap != null) {
            customOverlayBitmap
        } else {
            overlayBitmap
        }

        if (bitmapToUse == null) return
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        GLES20.glUseProgram(overlayProgram)

        val alphaHandle = GLES20.glGetUniformLocation(overlayProgram, "uAlpha")
        GLES20.glUniform1f(alphaHandle, overlayAnimProgress)
        val posHandle = GLES20.glGetAttribLocation(overlayProgram, "aPosition")
        val texHandle = GLES20.glGetAttribLocation(overlayProgram, "aTextureCoord")
        val mvpHandle = GLES20.glGetUniformLocation(overlayProgram, "uMVPMatrix")
        val samplerHandle = GLES20.glGetUniformLocation(overlayProgram, "sTexture")

        // Tính toán vị trí overlay từ normalized coordinates
        // OpenGL coords: -1 to 1, với -1,-1 ở bottom-left
        val overlayWidthPx = overlayNormalizedWidth * targetWidth
        val overlayHeightPx = overlayNormalizedHeight * targetHeight
        val overlayXPx = overlayNormalizedX * targetWidth
        val overlayYPx = overlayNormalizedY * targetHeight
        val left = overlayXPx / targetWidth * 2f - 1f
        val right = (overlayXPx + overlayWidthPx) / targetWidth * 2f - 1f
        val top = 1f - overlayYPx / targetHeight * 2f
        val bottom = 1f - (overlayYPx + overlayHeightPx) / targetHeight * 2f

        val overlayVerts = floatArrayOf(
            left, bottom, 0f,
            right, bottom, 0f,
            left, top, 0f,
            right, top, 0f
        )
        val overlayVertBuffer = createBuffer(overlayVerts)

        val mvp = FloatArray(16)
        Matrix.setIdentityM(mvp, 0)

        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glEnableVertexAttribArray(texHandle)

        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, overlayVertBuffer)

        overlayTexBuffer?.position(0)
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, overlayTexBuffer)

        val progressHandle = GLES20.glGetUniformLocation(overlayProgram, "uProgress")
        GLES20.glUniform1f(progressHandle, overlayAnimProgress)
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvp, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        GLES20.glUniform1i(samplerHandle, 1)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(posHandle)
        GLES20.glDisableVertexAttribArray(texHandle)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun drawToEncoder() {
        // Lấy current context và surfaces của GLSurfaceView
        val currentDisplay = EGL14.eglGetCurrentDisplay()
        val currentContext = EGL14.eglGetCurrentContext()
        val savedDrawSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val savedReadSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)

        if (currentContext == EGL14.EGL_NO_CONTEXT) {
            Timber.tag(TAG).e("No current EGL context!")
            return
        }

        if (encoderEglSurface == EGL14.EGL_NO_SURFACE) {
            Timber.tag(TAG).e("Encoder surface not available!")
            return
        }

        try {
            // Switch sang encoder surface, giữ nguyên context của GLSurfaceView
            // Vì GLSurfaceView đã dùng recordable config nên không bị EGL_BAD_MATCH
            if (!EGL14.eglMakeCurrent(
                    currentDisplay,
                    encoderEglSurface,
                    encoderEglSurface,
                    encoderEglContext
                )
            ) {
                val error = EGL14.eglGetError()
                Timber.tag(TAG).e("Failed to make encoder surface current, error: $error")
                return
            }

            // Render camera và overlay vào encoder surface
            // Sử dụng encoderWidth/encoderHeight đã align để match với encoder surface
            val renderWidth = if (encoderWidth > 0) encoderWidth else viewWidth
            val renderHeight = if (encoderHeight > 0) encoderHeight else viewHeight
            GLES20.glViewport(0, 0, renderWidth, renderHeight)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            drawCamera()
            drawOverlay(renderWidth, renderHeight)

            // Set presentation time
            val pts = System.nanoTime() - recordingStartTimeNano
            EGLExt.eglPresentationTimeANDROID(currentDisplay, encoderEglSurface, pts)

            // Swap buffers để gửi frame đến encoder
            if (!EGL14.eglSwapBuffers(currentDisplay, encoderEglSurface)) {
                val error = EGL14.eglGetError()
                Timber.tag(TAG).e("eglSwapBuffers failed, error: $error")
            }

            // Drain encoder
            drainEncoder(false)

        } finally {
            // Phục hồi GLSurfaceView surface
            if (!EGL14.eglMakeCurrent(
                    currentDisplay,
                    savedDrawSurface,
                    savedReadSurface,
                    currentContext
                )
            ) {
                Timber.tag(TAG).e("Failed to restore GLSurfaceView surface")
            }
        }
    }

    private fun updateOverlayTexture() {
        // Nếu đang sử dụng custom overlay, upload bitmap đó lên GPU
        if (useCustomOverlay && customOverlayBitmap != null) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, customOverlayBitmap, 0)
            needsOverlayUpdate = false

            // Vẫn notify time update nếu đang recording
            if (isRecording) {
                val elapsed = System.currentTimeMillis() - recordingStartTime
                val elapsedStr = formatTime(elapsed)
                post { onTimeUpdate?.invoke(elapsedStr) }
            }
            return
        }

        // Fallback: tạo internal overlay
        val w = 500
        val h = 100

        overlayBitmap?.recycle()
        overlayBitmap = createBitmap(w, h)

        val canvas = Canvas(overlayBitmap!!)

        if (isRecording) {
            val elapsed = System.currentTimeMillis() - recordingStartTime
            val elapsedStr = formatTime(elapsed)

            // Notify UI
            post { onTimeUpdate?.invoke(elapsedStr) }
        }

        // Upload to GPU
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, overlayBitmap, 0)

        needsOverlayUpdate = false
    }

    @SuppressLint("DefaultLocale")
    private fun formatTime(ms: Long): String {
        val s = (ms / 1000) % 60
        val m = (ms / 1000 / 60) % 60
        val h = ms / 1000 / 60 / 60
        return if (h > 0) String.format("%02d:%02d:%02d", h, m, s)
        else String.format("%02d:%02d", m, s)
    }

    private fun createProgram(vertSrc: String, fragSrc: String): Int {
        val vert = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER)
        GLES20.glShaderSource(vert, vertSrc)
        GLES20.glCompileShader(vert)

        val frag = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER)
        GLES20.glShaderSource(frag, fragSrc)
        GLES20.glCompileShader(frag)

        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vert)
        GLES20.glAttachShader(prog, frag)
        GLES20.glLinkProgram(prog)

        return prog
    }

    // === Public API ===

    fun getSurfaceTexture(): SurfaceTexture? = cameraSurfaceTexture

    fun setFrontCamera(front: Boolean) {
        isFrontCamera = front
    }

    /**
     * Đặt camera resolution để tính toán center-crop aspect ratio
     * Gọi method này khi bind camera với SurfaceTexture
     */
    fun setCameraResolution(width: Int, height: Int) {
        Log.d(TAG, "setCameraResolution: $width x $height")
        cameraWidth = width
        cameraHeight = height
        queueEvent {
            updateCameraTexCoords()
        }
    }

    fun setOverlayText(text: String) {
        overlayText = text
        needsOverlayUpdate = true
    }

    /**
     * Đặt custom overlay bitmap từ Composable
     * Bitmap này sẽ được vẽ thay vì internal overlay
     */
    fun setCustomOverlayBitmap(bitmap: Bitmap?) {
        queueEvent {
            if (bitmap == null) {
                customOverlayBitmap = null
                return@queueEvent
            }

            // Thay vì gán trực tiếp, hãy tận dụng việc Bitmap không đổi vùng nhớ (Reuse)
            customOverlayBitmap = bitmap

            // Kích hoạt việc cập nhật texture trong luồng Render
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)

            // Không cần gọi bitmap.recycle() ở đây vì chúng ta đang tái sử dụng nó ở tầng UI
        }
    }

    /**
     * Đặt vị trí và kích thước overlay (normalized 0-1)
     * x, y: vị trí top-left corner
     * width, height: kích thước
     */
    fun setOverlayPosition(x: Float, y: Float, width: Float, height: Float) {
        overlayNormalizedX = x
        overlayNormalizedY = y
        overlayNormalizedWidth = width
        overlayNormalizedHeight = height
    }

    /**
     * Lấy thời gian đã quay
     */
    fun getRecordingDuration(): Long {
        return if (isRecording) {
            System.currentTimeMillis() - recordingStartTime
        } else 0L
    }

    /**
     * Làm tròn kích thước về bội số của alignment (mặc định 2)
     */
    private fun alignTo(value: Int, alignment: Int = 2): Int {
        return (value / alignment) * alignment
    }

    /**
     * Tính toán kích thước encoder phù hợp, giới hạn max 1080p
     * và query codec capabilities để đảm bảo tương thích (đặc biệt Samsung)
     */
    private fun calculateEncoderSize(srcWidth: Int, srcHeight: Int): Pair<Int, Int> {
        val maxDimension = 1080
        var w = srcWidth
        var h = srcHeight

        // Scale down nếu quá lớn, giữ nguyên aspect ratio
        if (w > maxDimension || h > maxDimension) {
            val scale = maxDimension.toFloat() / maxOf(w, h)
            w = (w * scale).toInt()
            h = (h * scale).toInt()
        }

        // Align về bội số 2 (yêu cầu tối thiểu của H.264)
        w = alignTo(w).coerceAtLeast(16)
        h = alignTo(h).coerceAtLeast(16)

        // Query codec capabilities để clamp vào range được hỗ trợ
        try {
            val codecInfo = MediaCodec.createEncoderByType(MIME_TYPE)
            val codecInfoObj = codecInfo.codecInfo
            val caps = codecInfoObj.getCapabilitiesForType(MIME_TYPE)
            val videoCaps = caps.videoCapabilities
            codecInfo.release()

            if (videoCaps != null) {
                val supportedWidths = videoCaps.supportedWidths
                val supportedHeights = videoCaps.supportedHeights

                w = w.coerceIn(supportedWidths.lower, supportedWidths.upper)
                h = h.coerceIn(supportedHeights.lower, supportedHeights.upper)

                // Kiểm tra width/height combination có hợp lệ không
                val widthsForH = videoCaps.getSupportedWidthsFor(h)
                if (widthsForH != null && !widthsForH.contains(w)) {
                    w = w.coerceIn(widthsForH.lower, widthsForH.upper)
                }

                // Align lại sau khi clamp
                w = alignTo(w).coerceAtLeast(16)
                h = alignTo(h).coerceAtLeast(16)

                Log.d(TAG, "Codec supports: width=${supportedWidths}, height=${supportedHeights}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not query codec capabilities: ${e.message}")
        }

        return w to h
    }

    fun startRecording(ctx: Context) {
        if (isRecording) return

        queueEvent {
            try {
                // Create output file
                outputFile = createOutputFile(ctx)

                // Tính kích thước encoder phù hợp với thiết bị
                val (calcW, calcH) = calculateEncoderSize(viewWidth, viewHeight)
                encoderWidth = calcW
                encoderHeight = calcH

                Log.d(
                    TAG,
                    "Original size: ${viewWidth}x${viewHeight}, Encoder size: ${encoderWidth}x${encoderHeight}"
                )

                // Setup MediaCodec với kích thước đã tính toán
                val format =
                    MediaFormat.createVideoFormat(MIME_TYPE, encoderWidth, encoderHeight).apply {
                        setInteger(
                            MediaFormat.KEY_COLOR_FORMAT,
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                        )
                        setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                        setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
                    }

                // Tạo và cấu hình MediaCodec
                mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE)
                mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoderSurface = mediaCodec?.createInputSurface()
                mediaCodec?.start()

                Timber.tag(TAG).d("MediaCodec started, creating EGL surface...")

                // Lấy EGL display từ GLSurfaceView context hiện tại
                val currentDisplay = EGL14.eglGetCurrentDisplay()
                val currentContext = EGL14.eglGetCurrentContext()

                // Query config từ current context - PHẢI dùng cùng config để tránh EGL_BAD_MATCH
                val configId = IntArray(1)
                EGL14.eglQueryContext(
                    currentDisplay,
                    currentContext,
                    EGL14.EGL_CONFIG_ID,
                    configId,
                    0
                )

                val configAttribs = intArrayOf(
                    EGL14.EGL_CONFIG_ID, configId[0],
                    EGL14.EGL_NONE
                )
                val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
                val numConfigs = IntArray(1)
                EGL14.eglChooseConfig(
                    currentDisplay,
                    configAttribs,
                    0,
                    configs,
                    0,
                    1,
                    numConfigs,
                    0
                )

                val currentConfig = configs[0]
                    ?: throw RuntimeException("Failed to get EGL config from context")

                Timber.tag(TAG).d("Using EGL config from context, configId: ${configId[0]}")

                // Tạo EGL context mới cho encoder, share với context hiện tại
                val contextAttribs = intArrayOf(
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                    EGL14.EGL_NONE
                )
                val encoderContext = EGL14.eglCreateContext(
                    currentDisplay,
                    currentConfig,
                    currentContext,  // Share với context của GLSurfaceView
                    contextAttribs,
                    0
                )

                if (encoderContext == EGL14.EGL_NO_CONTEXT) {
                    val error = EGL14.eglGetError()
                    Timber.tag(TAG).e("Failed to create encoder context, error: $error")
                    throw RuntimeException("Failed to create encoder context")
                }

                // Create EGL surface for encoder với cùng config
                val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
                encoderEglSurface = EGL14.eglCreateWindowSurface(
                    currentDisplay,
                    currentConfig,
                    encoderSurface,
                    surfaceAttribs,
                    0
                )

                if (encoderEglSurface == EGL14.EGL_NO_SURFACE) {
                    val error = EGL14.eglGetError()
                    Timber.tag(TAG).e("Failed to create encoder EGL surface, error: $error")
                    EGL14.eglDestroyContext(currentDisplay, encoderContext)
                    throw RuntimeException("Failed to create encoder EGL surface, error: $error")
                }

                // Lưu encoder context để sử dụng sau
                this.encoderEglContext = encoderContext

                Timber.tag(TAG).d("Encoder EGL surface created successfully")

                // Setup MediaMuxer
                mediaMuxer = MediaMuxer(
                    outputFile!!.absolutePath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )
                muxerStarted = false
                videoTrackIndex = -1

                recordingStartTime = System.currentTimeMillis()
                recordingStartTimeNano = System.nanoTime()  // Cho presentation time
                isRecording = true

                overlayAnimStartTime = System.nanoTime()
                overlayAnimProgress = 0f
                overlayAnimating = true

                post { onRecordingStateChanged?.invoke(true, null) }
                Timber.tag(TAG).d("Recording started")

            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Start recording failed: ${e.message}")

                // Cleanup nếu khởi tạo thất bại
                try {
                    mediaCodec?.release()
                } catch (ex: Exception) {
                    Timber.tag(TAG).w("Error releasing codec on failure: ${ex.message}")
                }
                try {
                    mediaMuxer?.release()
                } catch (ex: Exception) {
                    Timber.tag(TAG).w("Error releasing muxer on failure: ${ex.message}")
                }
                try {
                    val display = EGL14.eglGetCurrentDisplay()
                    if (encoderEglSurface != EGL14.EGL_NO_SURFACE) {
                        EGL14.eglDestroySurface(display, encoderEglSurface)
                    }
                    if (encoderEglContext != EGL14.EGL_NO_CONTEXT) {
                        EGL14.eglDestroyContext(display, encoderEglContext)
                    }
                    encoderSurface?.release()
                } catch (ex: Exception) {
                    Timber.tag(TAG).w("Error releasing EGL on failure: ${ex.message}")
                }

                mediaCodec = null
                mediaMuxer = null
                encoderSurface = null
                encoderEglSurface = EGL14.EGL_NO_SURFACE
                encoderEglContext = EGL14.EGL_NO_CONTEXT
                outputFile = null
                isRecording = false

                post { onRecordingStateChanged?.invoke(false, "Lỗi: ${e.message}") }
            }
        }
    }

    fun stopRecording(ctx: Context) {
        if (!isRecording) return

        isRecording = false

        // Lưu lại output file trước khi release
        val savedOutputFile = outputFile

        queueEvent {
            var codecStopped = false
            var muxerStopped = false

            try {
                Timber.tag(TAG).d("Stopping recording...")

                // Final drain - đảm bảo tất cả frames được ghi
                drainEncoder(true)

                Timber.tag(TAG).d("Drain complete, stopping codec...")
            } catch (e: Exception) {
                Timber.tag(TAG).w("Drain encoder error: ${e.message}")
            }

            // Stop và release codec - xử lý riêng để tránh ảnh hưởng các bước sau
            try {
                mediaCodec?.stop()
                codecStopped = true
            } catch (e: IllegalStateException) {
                Timber.tag(TAG).w("Codec already stopped or released: ${e.message}")
            }

            try {
                mediaCodec?.release()
            } catch (e: Exception) {
                Timber.tag(TAG).w("Error releasing codec: ${e.message}")
            }

            Timber.tag(TAG).d("Codec stopped=$codecStopped, muxerStarted=$muxerStarted")

            // Stop và release muxer
            try {
                if (muxerStarted) {
                    mediaMuxer?.stop()
                    muxerStopped = true
                    Timber.tag(TAG).d("Muxer stopped")
                }
            } catch (e: IllegalStateException) {
                Timber.tag(TAG).w("Muxer already stopped: ${e.message}")
            }

            try {
                mediaMuxer?.release()
            } catch (e: Exception) {
                Timber.tag(TAG).w("Error releasing muxer: ${e.message}")
            }

            // Cleanup EGL surface và context
            try {
                val display = EGL14.eglGetCurrentDisplay()
                if (encoderEglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(display, encoderEglSurface)
                }
                if (encoderEglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(display, encoderEglContext)
                }
                encoderSurface?.release()
            } catch (e: Exception) {
                Timber.tag(TAG).w("Error releasing EGL resources: ${e.message}")
            }

            var resultUri: Uri? = null

            if (savedOutputFile != null &&
                savedOutputFile.exists() &&
                savedOutputFile.length() > 0
            ) {
                resultUri = cacheFileToContentUri(ctx, savedOutputFile)
            }

            post {
                // 🔥 trả content:// thay vì path
                onRecordingStateChanged?.invoke(false, resultUri?.toString())
            }

            mediaCodec = null
            mediaMuxer = null
            encoderSurface = null
            encoderEglSurface = EGL14.EGL_NO_SURFACE
            encoderEglContext = EGL14.EGL_NO_CONTEXT
            outputFile = null
        }
    }

    fun isRecording(): Boolean = isRecording

    private fun drainEncoder(eos: Boolean) {
        // Kiểm tra nếu không còn recording thì không làm gì
        if (!isRecording && !eos) return

        try {
            if (eos) {
                Timber.tag(TAG).d("drainEncoder: signaling end of stream")
                mediaCodec?.signalEndOfInputStream()
            }

            val codec = mediaCodec ?: return
            var frameCount = 0

            while (true) {
                val idx = codec.dequeueOutputBuffer(bufferInfo, 10000)

                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!eos) break
                        // Nếu EOS, tiếp tục chờ
                    }

                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxerStarted) {
                            Timber.tag(TAG).w("Format changed after muxer started!")
                            continue
                        }
                        val format = codec.outputFormat
                        Timber.tag(TAG).d("Encoder output format changed: $format")
                        videoTrackIndex = mediaMuxer?.addTrack(format) ?: -1
                        mediaMuxer?.start()
                        muxerStarted = true
                        Timber.tag(TAG).d("Muxer started, track index: $videoTrackIndex")
                    }

                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx) ?: continue

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            bufferInfo.size = 0
                        }

                        if (bufferInfo.size != 0 && muxerStarted) {
                            buf.position(bufferInfo.offset)
                            buf.limit(bufferInfo.offset + bufferInfo.size)
                            mediaMuxer?.writeSampleData(videoTrackIndex, buf, bufferInfo)
                            frameCount++
                        }

                        codec.releaseOutputBuffer(idx, false)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            Timber.tag(TAG)
                                .d("drainEncoder: EOS reached, total frames: $frameCount")
                            break
                        }
                    }
                }
            }

            if (frameCount > 0) {
                Timber.tag(TAG).d("drainEncoder: wrote $frameCount frames in this call")
            }
        } catch (e: IllegalStateException) {
            Timber.tag(TAG).w("drainEncoder: codec in invalid state: ${e.message}")
        } catch (e: Exception) {
            Timber.tag(TAG).e("drainEncoder error: ${e.message}")
        }
    }

    private fun createOutputFile(ctx: Context): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return File(ctx.cacheDir, "VID_$ts.mp4")
    }

    fun release() {
        queueEvent {
            cameraSurfaceTexture?.release()
            overlayBitmap?.recycle()
            GLES20.glDeleteProgram(cameraProgram)
            GLES20.glDeleteProgram(overlayProgram)
            GLES20.glDeleteTextures(2, intArrayOf(cameraTextureId, overlayTextureId), 0)
        }
    }

    fun cacheFileToContentUri(context: Context, file: File): Uri {
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )
    }
}