package com.bigq.demodogcat.opengl

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
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
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import androidx.core.graphics.createBitmap
import com.bigq.demodogcat.R
import com.bigq.demodogcat.saytheword.WordItem
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import androidx.core.graphics.toColorInt

/**
 * GLSurfaceView tích hợp sẵn chức năng quay video với overlay
 * - Render camera preview từ SurfaceTexture
 * - Vẽ overlay (text, thời gian) lên trên
 * - Encode video với overlay đã được "bake in"
 */
class CameraGLSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs), GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "CameraGLSurfaceView"
        private const val MIME_TYPE = "video/avc"
        private const val BIT_RATE = 8_000_000
        private const val FRAME_RATE = 30 // 30fps is safer for more devices
        private const val I_FRAME_INTERVAL = 1
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val MAX_VIDEO_WIDTH = 1080
        private const val MAX_VIDEO_HEIGHT = 1920
    }

    // Camera texture
    private var cameraTextureId = 0
    private var cameraSurfaceTexture: SurfaceTexture? = null
    private val stMatrix = FloatArray(16)

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
    private var videoWidth = 0   // Actual video dimensions (aligned to 16)
    private var videoHeight = 0  // Actual video dimensions (aligned to 16)

    // Camera preview dimensions (from CameraX)
    private var cameraPreviewWidth = 0
    private var cameraPreviewHeight = 0

    // Recording state
    @Volatile
    private var isRecording = false
    @Volatile
    private var isEncoderReleased = false  // Guard against calling released codec
    private var recordingStartTime = 0L  // milliseconds for UI display
    private var recordingStartTimeNano = 0L  // nanoseconds for presentation time
    private var framesSentToEncoder = 0  // Debug counter
    private var encoderContext: android.opengl.EGLContext = EGL14.EGL_NO_CONTEXT
    private var encoderEglDisplay: android.opengl.EGLDisplay = EGL14.EGL_NO_DISPLAY

    // Encoder
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var encoderSurface: Surface? = null
    private var encoderEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var videoTrackIndex = -1
    private var muxerStarted = false
    private var bufferInfo = MediaCodec.BufferInfo()
    private var outputFile: File? = null

    // EGL for encoding
    private var sharedEglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglConfig: android.opengl.EGLConfig? = null

    // Camera facing
    private var isFrontCamera = false

    // Word Animation State
    private var wordItems: List<WordItem> = emptyList()
    private var activeWordIndex: Int = -1
    private val iconCache = mutableMapOf<Int, Bitmap>()
    private var backgroundBitmap: Bitmap? = null  // cached background image
    private val backgroundPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val borderPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 5f }
    private val textPaint = Paint().apply {
        color = Color.BLACK
        textSize = 24f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    // Level system
    private var currentLevel: Int = 1       // 1-5
    private var totalLevels: Int = 5
    private var showBorder: Boolean = true   // false sau khi hoàn thành level 5

    // Animation state cho fade-in ảnh (level 1)
    private var itemAlphas: FloatArray = FloatArray(0)  // alpha cho mỗi item (0..255)
    private var animationStartTimeMs: Long = 0L
    private var isAnimatingItems: Boolean = false
    private val animationDurationPerItemMs: Long = 600L  // thời gian fade-in mỗi ảnh
    private val animationDelayBetweenItemsMs: Long = 200L  // delay giữa các ảnh

    // Callbacks
    var onSurfaceTextureReady: ((SurfaceTexture) -> Unit)? = null
    var onRecordingStateChanged: ((Boolean, String?) -> Unit)? = null
    var onTimeUpdate: ((String) -> Unit)? = null
    var onLevelChanged: ((Int) -> Unit)? = null

    // Shaders
    private val cameraVertexShader = """
        uniform mat4 uMVPMatrix;
        uniform mat4 uSTMatrix;
        attribute vec4 aPosition;
        attribute vec4 aTextureCoord;
        varying vec2 vTextureCoord;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
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
        uniform mat4 uMVPMatrix;
        attribute vec4 aPosition;
        attribute vec4 aTextureCoord;
        varying vec2 vTextureCoord;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
            vTextureCoord = aTextureCoord.xy;
        }
    """.trimIndent()

    private val overlayFragmentShader = """
        precision mediump float;
        varying vec2 vTextureCoord;
        uniform sampler2D sTexture;
        void main() {
            gl_FragColor = texture2D(sTexture, vTextureCoord);
        }
    """.trimIndent()

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(this)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        Log.d(TAG, "onSurfaceCreated")

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

        Matrix.setIdentityM(stMatrix, 0)

        // Notify callback
        post {
            cameraSurfaceTexture?.let { onSurfaceTextureReady?.invoke(it) }
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        Log.d(TAG, "onSurfaceChanged: $width x $height")
        viewWidth = width
        viewHeight = height
        GLES20.glViewport(0, 0, width, height)
        needsOverlayUpdate = true
    }

    override fun onDrawFrame(gl: GL10?) {
        // Update camera texture
        try {
            cameraSurfaceTexture?.updateTexImage()
            cameraSurfaceTexture?.getTransformMatrix(stMatrix)
        } catch (e: Exception) {
            // Ignore
        }

        // Update animation alphas nếu đang animate
        if (isAnimatingItems) {
            updateItemAnimations()
            needsOverlayUpdate = true
        }

        // Update overlay if needed (chỉ khi có custom overlay hoặc cần update internal)
        if (useCustomOverlay) {
            // Custom overlay - chỉ update khi có bitmap mới
            if (needsOverlayUpdate && customOverlayBitmap != null) {
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

        // Chỉ vẽ overlay lên preview khi KHÔNG sử dụng custom overlay
        // Nếu dùng custom overlay, Composable sẽ hiển thị overlay trên UI
        if (!useCustomOverlay) {
            drawOverlay()
        }

        // If recording, also draw to encoder surface (luôn vẽ overlay vào video)
        // CRITICAL: Check isEncoderReleased to prevent crash when codec is being released
        if (isRecording && !isEncoderReleased && encoderEglSurface != EGL14.EGL_NO_SURFACE) {
            drawToEncoder()
        } else if (isRecording) {
            // Debug failure case
            Log.w(TAG, "Recording is true but skipping drawToEncoder! released=$isEncoderReleased, surface=${encoderEglSurface != EGL14.EGL_NO_SURFACE}")
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

        // Camera texture coords - giữ nguyên như ban đầu
        // SurfaceTexture transform matrix sẽ xử lý rotation
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

        // MVP matrix - flip horizontally for front camera + aspect ratio correction
        val mvp = FloatArray(16)
        Matrix.setIdentityM(mvp, 0)
        if (isFrontCamera) {
            Matrix.scaleM(mvp, 0, -1f, 1f, 1f)
        }

        // Center-crop: scale camera feed to fill view while maintaining aspect ratio
        // This makes the camera look natural like the native camera app
        if (cameraPreviewWidth > 0 && cameraPreviewHeight > 0 && viewWidth > 0 && viewHeight > 0) {
            // Camera resolution is in sensor orientation (landscape for most phones)
            // After SurfaceTexture rotation, effective dimensions swap for portrait
            val isViewPortrait = viewHeight > viewWidth
            val isCameraLandscape = cameraPreviewWidth > cameraPreviewHeight

            val effectiveCamW: Float
            val effectiveCamH: Float
            if (isViewPortrait && isCameraLandscape) {
                // stMatrix will rotate 90° → swap dimensions
                effectiveCamW = cameraPreviewHeight.toFloat()
                effectiveCamH = cameraPreviewWidth.toFloat()
            } else {
                effectiveCamW = cameraPreviewWidth.toFloat()
                effectiveCamH = cameraPreviewHeight.toFloat()
            }

            val cameraAspect = effectiveCamW / effectiveCamH
            val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()

            if (cameraAspect > viewAspect) {
                // Camera is wider → scale quad width to crop sides
                val scaleX = cameraAspect / viewAspect
                Matrix.scaleM(mvp, 0, scaleX, 1f, 1f)
            } else {
                // Camera is taller → scale quad height to crop top/bottom
                val scaleY = viewAspect / cameraAspect
                Matrix.scaleM(mvp, 0, 1f, scaleY, 1f)
            }
        }

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

    private fun drawOverlay() {
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

        val posHandle = GLES20.glGetAttribLocation(overlayProgram, "aPosition")
        val texHandle = GLES20.glGetAttribLocation(overlayProgram, "aTextureCoord")
        val mvpHandle = GLES20.glGetUniformLocation(overlayProgram, "uMVPMatrix")
        val samplerHandle = GLES20.glGetUniformLocation(overlayProgram, "sTexture")

        // Tính toán vị trí overlay từ normalized coordinates
        // OpenGL coords: -1 to 1, với -1,-1 ở bottom-left
        val left = overlayNormalizedX * 2f - 1f
        val right = (overlayNormalizedX + overlayNormalizedWidth) * 2f - 1f
        val top = 1f - overlayNormalizedY * 2f
        val bottom = 1f - (overlayNormalizedY + overlayNormalizedHeight) * 2f

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
            Log.e(TAG, "No current EGL context!")
            return
        }

        if (isEncoderReleased || mediaCodec == null || encoderEglSurface == EGL14.EGL_NO_SURFACE) {
            return
        }

        try {
            // Switch sang context và surface của Encoder
            if (!EGL14.eglMakeCurrent(
                    encoderEglDisplay,
                    encoderEglSurface,
                    encoderEglSurface,
                    encoderContext
                )
            ) {
                val error = EGL14.eglGetError()
                Log.e(TAG, "Failed to make encoder context current, error: $error")
                return
            }

            // Render camera và overlay vào encoder surface
            GLES20.glViewport(0, 0, videoWidth, videoHeight)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            drawCamera()
            drawOverlay()

            // Cần thiết để đảm bảo GPU render xong trước khi gửi sang encoder
            GLES20.glFinish()

            // Set presentation time
            val pts = System.nanoTime() - recordingStartTimeNano
            EGLExt.eglPresentationTimeANDROID(encoderEglDisplay, encoderEglSurface, pts)

            // Swap buffers để gửi frame đến encoder
            if (!EGL14.eglSwapBuffers(encoderEglDisplay, encoderEglSurface)) {
                val error = EGL14.eglGetError()
                Log.e(TAG, "eglSwapBuffers failed, error: $error")
            } else {
                framesSentToEncoder++
                if (framesSentToEncoder % 60 == 0) {
                    Log.d(TAG, "Recording: $framesSentToEncoder frames sent")
                }
            }

            // Drain encoder
            if (!isEncoderReleased) {
                drainEncoder(false)
            }

        } finally {
            // Quay lại screen surface và context của GLSurfaceView
            if (!EGL14.eglMakeCurrent(
                    encoderEglDisplay,
                    savedDrawSurface,
                    savedReadSurface,
                    currentContext
                )
            ) {
                Log.e(TAG, "Failed to restore GLSurfaceView surface")
            }
        }
    }

    private fun updateOverlayTexture() {
        // Nếu có wordItems, ta vẽ giao diện "Say the word" trực tiếp
        if (wordItems.isNotEmpty()) {
            drawWordAnimationGrid()
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, overlayBitmap, 0)
            needsOverlayUpdate = false
            return
        }

        // Nếu đang sử dụng custom overlay bitmap (từ ngoài truyền vào)
        if (useCustomOverlay && customOverlayBitmap != null) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, customOverlayBitmap, 0)
            needsOverlayUpdate = false

            if (isRecording) {
                val elapsed = System.currentTimeMillis() - recordingStartTime
                val elapsedStr = formatTime(elapsed)
                post { onTimeUpdate?.invoke(elapsedStr) }
            }
            return
        }

        // Fallback: tạo internal overlay đơn giản nếu không có gì đặc biệt
        val w = if (viewWidth > 0) viewWidth else 500
        val h = 100

        if (overlayBitmap == null || overlayBitmap?.width != w || overlayBitmap?.height != h) {
            overlayBitmap?.recycle()
            overlayBitmap = createBitmap(w, h)
        }

        val canvas = Canvas(overlayBitmap!!)
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)

        if (isRecording) {
            val elapsed = System.currentTimeMillis() - recordingStartTime
            val elapsedStr = formatTime(elapsed)
            val timePaint = Paint(textPaint).apply {
                color = Color.RED; textAlign = Paint.Align.RIGHT; textSize = 30f
            }
            canvas.drawText("REC $elapsedStr", w - 30f, 60f, timePaint)
            post { onTimeUpdate?.invoke(elapsedStr) }
        }

        if (overlayText.isNotEmpty()) {
            val textP = Paint(textPaint).apply {
                color = Color.WHITE; textAlign = Paint.Align.LEFT; textSize = 28f
            }
            canvas.drawText(overlayText, 30f, 60f, textP)
        }

        // Upload to GPU
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, overlayBitmap, 0)

        needsOverlayUpdate = false
    }

    // Animation: cập nhật alpha cho từng item (dùng cho fade-in ở level 1)
    private fun updateItemAnimations() {
        if (!isAnimatingItems || itemAlphas.isEmpty()) return

        val now = System.currentTimeMillis()
        val elapsed = now - animationStartTimeMs
        var allDone = true

        for (i in itemAlphas.indices) {
            val itemStart = i * animationDelayBetweenItemsMs
            val itemElapsed = elapsed - itemStart
            if (itemElapsed < 0) {
                itemAlphas[i] = 0f
                allDone = false
            } else if (itemElapsed < animationDurationPerItemMs) {
                // Ease-out cubic
                val progress = itemElapsed.toFloat() / animationDurationPerItemMs.toFloat()
                val eased = 1f - (1f - progress) * (1f - progress) * (1f - progress)
                itemAlphas[i] = (eased * 255f).coerceIn(0f, 255f)
                allDone = false
            } else {
                itemAlphas[i] = 255f
            }
        }

        if (allDone) {
            isAnimatingItems = false
        }
    }

    // Bắt đầu animation fade-in cho items
    fun startItemFadeInAnimation() {
        queueEvent {
            if (wordItems.isEmpty()) return@queueEvent
            itemAlphas = FloatArray(wordItems.size) { 0f }
            animationStartTimeMs = System.currentTimeMillis()
            isAnimatingItems = true
            needsOverlayUpdate = true
        }
    }

    private fun getBackgroundBitmap(): Bitmap? {
        if (backgroundBitmap == null) {
            try {
                backgroundBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.img_say_word_background)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load background: ${e.message}")
            }
        }
        return backgroundBitmap
    }

    private fun drawWordAnimationGrid() {
        val w = if (viewWidth > 0) viewWidth else 1080
        val actualViewH = if (viewHeight > 0) viewHeight else 1920

        // Dùng density để tính dp → px (giống Compose), đảm bảo đồng nhất trên mọi thiết bị
        val density = context.resources.displayMetrics.density

        // Chiều cao overlay cố định 200dp (giống Composable height(200.dp))
        val h = (220f * density).toInt().coerceAtMost(actualViewH)

        // Cập nhật overlayNormalizedHeight để OpenGL quad khớp với bitmap height
        overlayNormalizedHeight = h.toFloat() / actualViewH.toFloat()

        if (overlayBitmap == null || overlayBitmap?.width != w || overlayBitmap?.height != h) {
            overlayBitmap?.recycle()
            overlayBitmap = createBitmap(w, h)
        }

        val canvas = Canvas(overlayBitmap!!)

        // === Vẽ background image (img_say_word_background) crop fill toàn bộ ===
        val bg = getBackgroundBitmap()
        if (bg != null) {
            val destRect = RectF(0f, 0f, w.toFloat(), h.toFloat())
            canvas.drawBitmap(bg, null, destRect, null)
        } else {
            canvas.drawColor(Color.WHITE)
        }

        // === Tất cả measurements tính bằng dp * density (giống Compose) ===

        // Title "1/5" - fontSize = 20.sp, padding(top = 10.dp) trong Composable
        val titleTextSize = 20f * density
        val titlePaddingTop = 20f * density
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = titleTextSize
        textPaint.color = Color.BLACK
        textPaint.typeface = Typeface.DEFAULT_BOLD
        textPaint.alpha = 255
        // drawText y = baseline = paddingTop + textSize
        val titleBaselineY = titlePaddingTop + titleTextSize
        canvas.drawText("$currentLevel/$totalLevels", w / 2f, titleBaselineY, textPaint)

        // === Grid 4x2, chiều rộng 70% (fillMaxWidth(0.7f)), căn giữa ===
        val cols = 4
        val rows = 2
        val spacing = 5f * density   // Arrangement.spacedBy(5.dp) trong Composable
        val spacerAfterTitle = 12f * density  // Spacer(Modifier.height(12.dp))
        val gridTop = titleBaselineY + spacerAfterTitle

        // Grid width = 70% chiều rộng overlay (fillMaxWidth(0.7f))
        val maxGridWidth = w * 0.7f

        // Item vuông (aspectRatio(1f) trong Composable)
        val itemSizeByWidth = (maxGridWidth - (cols - 1) * spacing) / cols

        // Tính item size tối đa theo chiều cao khả dụng (tránh kéo dãn)
        val bottomPadding = 5f * density
        val availableGridH = h - gridTop - bottomPadding
        val itemSizeByHeight = (availableGridH - (rows - 1) * spacing) / rows

        // Lấy min để item vừa theo cả 2 chiều, không bị tràn
        val itemSize = minOf(itemSizeByWidth, itemSizeByHeight)

        // Tính offset để căn giữa grid
        val actualGridWidth = cols * itemSize + (cols - 1) * spacing
        val actualGridHeight = rows * itemSize + (rows - 1) * spacing
        val gridOffsetX = (w - actualGridWidth) / 2f

        // Căn giữa grid trong phần dưới title
        val remainingH = h - gridTop
        val gridOffsetY = gridTop + (remainingH - actualGridHeight) / 2f

        for (i in wordItems.indices) {
            val row = i / cols
            val col = i % cols
            val left = gridOffsetX + col * (itemSize + spacing)
            val top = gridOffsetY + row * (itemSize + spacing)
            val right = left + itemSize
            val bottom = top + itemSize

            // Alpha cho animation (giữ nguyên)
            val alpha = if (isAnimatingItems && i < itemAlphas.size) {
                itemAlphas[i].toInt().coerceIn(0, 255)
            } else {
                255
            }

            // Ảnh crop fill toàn bộ ô vuông (ContentScale.Crop)
            val item = wordItems[i]
            val icon = getIconBitmap(item.image)
            if (icon != null) {
                val destRect = RectF(left, top, right, bottom)
                val iconPaint = Paint().apply { this.alpha = alpha }
                canvas.drawBitmap(icon, null, destRect, iconPaint)
            }

            // Vẽ border lên trên ảnh (giữ nguyên logic thay đổi màu)
            val isActive = (i == activeWordIndex)
            val borderWidth = if (showBorder) {
                if (isActive) 3f * density else 2f * density  // border(width = 2.dp)
            } else {
                1f * density
            }
            if (showBorder) {
                borderPaint.color = if (isActive) "#00C853".toColorInt() else Color.GRAY
                borderPaint.strokeWidth = borderWidth
                borderPaint.alpha = alpha
                canvas.drawRect(left, top, right, bottom, borderPaint)
            } else {
                borderPaint.color = Color.LTGRAY
                borderPaint.strokeWidth = borderWidth
                borderPaint.alpha = alpha
                canvas.drawRect(left, top, right, bottom, borderPaint)
            }
        }

        // Reset alpha
        textPaint.alpha = 255
        borderPaint.alpha = 255
    }

    private fun getIconBitmap(resId: Int): Bitmap? {
        if (!iconCache.containsKey(resId)) {
            try {
                iconCache[resId] = BitmapFactory.decodeResource(context.resources, resId)
            } catch (e: Exception) {
                return null
            }
        }
        return iconCache[resId]
    }

    fun setWordItems(items: List<WordItem>) {
        queueEvent {
            wordItems = items
            // Reset animation alpha
            itemAlphas = FloatArray(items.size) { 255f }
            isAnimatingItems = false
            needsOverlayUpdate = true
        }
    }

    fun setActiveWordIndex(index: Int) {
        queueEvent {
            if (activeWordIndex != index) {
                activeWordIndex = index
                needsOverlayUpdate = true
            }
        }
    }

    fun setCurrentLevel(level: Int) {
        queueEvent {
            currentLevel = level
            // Sau khi hết level 5, tắt border xanh
            showBorder = level <= totalLevels
            needsOverlayUpdate = true
        }
        post { onLevelChanged?.invoke(level) }
    }

    fun setShowBorder(show: Boolean) {
        queueEvent {
            showBorder = show
            needsOverlayUpdate = true
        }
    }

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

    fun setCameraPreviewSize(width: Int, height: Int) {
        cameraPreviewWidth = width
        cameraPreviewHeight = height
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
        customOverlayBitmap = bitmap
        useCustomOverlay = bitmap != null
        needsOverlayUpdate = true
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

    fun startRecording(ctx: Context) {
        if (isRecording) return

        queueEvent {
            try {
                // Reset encoder released flag
                isEncoderReleased = false

                // Create output file
                outputFile = createOutputFile(ctx)

                // Samsung compatibility: Align dimensions to 16 and cap them
                videoWidth = viewWidth
                videoHeight = viewHeight

                // Limit resolution for stability on most devices
                if (videoWidth > MAX_VIDEO_WIDTH || videoHeight > MAX_VIDEO_HEIGHT) {
                    val ratio = videoWidth.toFloat() / videoHeight.toFloat()
                    if (videoWidth > videoHeight) {
                        videoWidth = MAX_VIDEO_WIDTH
                        videoHeight = (videoWidth / ratio).toInt()
                    } else {
                        videoHeight = MAX_VIDEO_HEIGHT
                        videoWidth = (videoHeight * ratio).toInt()
                    }
                }

                // Align to 16 (CRITICAL for Samsung MediaCodec / Exynos)
                videoWidth = (videoWidth / 16) * 16
                videoHeight = (videoHeight / 16) * 16

                Log.d(
                    TAG,
                    "Recording dimensions: $videoWidth x $videoHeight (original: $viewWidth x $viewHeight)"
                )

                // Setup MediaCodec
                val format =
                    MediaFormat.createVideoFormat(MIME_TYPE, videoWidth, videoHeight).apply {
                        setInteger(
                            MediaFormat.KEY_COLOR_FORMAT,
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                        )
                        setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                        setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
                    }

                // Verify capabilities (Robustness for Samsung)
                try {
                    val encoderList =
                        android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
                    val encoderName = encoderList.findEncoderForFormat(format)
                    if (encoderName != null) {
                        mediaCodec = MediaCodec.createByCodecName(encoderName)
                        Log.d(TAG, "Using specific encoder: $encoderName")
                    } else {
                        mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE)
                        Log.w(TAG, "No specific encoder found for format, fallback to default")
                    }
                } catch (e: Exception) {
                    mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE)
                    Log.w(TAG, "Error choosing encoder, fallback to default: ${e.message}")
                }

                mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoderSurface = mediaCodec?.createInputSurface()
                mediaCodec?.start()

                Log.d(TAG, "MediaCodec started, creating encoder EGL environment...")

                val currentDisplay = EGL14.eglGetCurrentDisplay()
                val currentContext = EGL14.eglGetCurrentContext()
                encoderEglDisplay = currentDisplay

                // 1. Tìm Config Recordable chuẩn cho Encoder
                val attribList = intArrayOf(
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
                EGL14.eglChooseConfig(currentDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)
                val encoderConfig = configs[0] ?: throw RuntimeException("No recordable EGL config")

                // 2. Tạo Encoder Context riêng và SHARE với main context (dùng chung texture)
                val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
                encoderContext = EGL14.eglCreateContext(currentDisplay, encoderConfig, currentContext, ctxAttribs, 0)
                if (encoderContext == EGL14.EGL_NO_CONTEXT) {
                    throw RuntimeException("Failed to create shared encoder EGL context")
                }

                // 3. Tạo Surface
                val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
                encoderEglSurface = EGL14.eglCreateWindowSurface(
                    currentDisplay,
                    encoderConfig,
                    encoderSurface,
                    surfaceAttribs,
                    0
                )

                if (encoderEglSurface == EGL14.EGL_NO_SURFACE) {
                    throw RuntimeException("Failed to create encoder EGL surface")
                }

                Log.d(TAG, "Encoder EGL context and surface created (Shared Context mode)")

                // Setup MediaMuxer
                mediaMuxer = MediaMuxer(
                    outputFile!!.absolutePath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )
                muxerStarted = false
                videoTrackIndex = -1

                recordingStartTime = System.currentTimeMillis()
                recordingStartTimeNano = System.nanoTime()  // Cho presentation time
                framesSentToEncoder = 0
                isRecording = true

                post { onRecordingStateChanged?.invoke(true, null) }
                Log.d(TAG, "Recording started, videoSize: ${videoWidth}x${videoHeight}")

            } catch (e: Exception) {
                Log.e(TAG, "Start recording failed: ${e.message}", e)
                post { onRecordingStateChanged?.invoke(false, "Lỗi: ${e.message}") }
            }
        }
    }

    fun stopRecording(ctx: Context) {
        if (!isRecording) return

        // Chỉ set isRecording = false trước queueEvent
        // Điều này ngăn onDrawFrame gửi frame mới vào encoder
        // KHÔNG set isEncoderReleased ở đây - để drainEncoder drain hết frames
        isRecording = false

        // Lưu lại output file trước khi release
        val savedOutputFile = outputFile

        queueEvent {
            try {
                Log.d(TAG, "Stopping recording... framesSent=$framesSentToEncoder")

                // Final drain - đảm bảo tất cả frames được ghi
                // isEncoderReleased vẫn = false ở đây → drainEncoder sẽ drain đầy đủ
                try {
                    drainEncoder(true)
                    Log.d(TAG, "Drain complete successfully")
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "drainEncoder error during stop: ${e.message}")
                }

                // SAU KHI drain xong, đánh dấu encoder released
                isEncoderReleased = true

                Log.d(TAG, "Stopping codec...")

                // Wrap từng bước trong try-catch riêng
                try {
                    mediaCodec?.stop()
                    Log.d(TAG, "Codec stopped")
                } catch (e: Exception) {
                    Log.w(TAG, "Codec stop error (may be expected): ${e.message}")
                }

                try {
                    mediaCodec?.release()
                    Log.d(TAG, "Codec released")
                } catch (e: Exception) {
                    Log.w(TAG, "Codec release error: ${e.message}")
                }

                Log.d(TAG, "muxerStarted=$muxerStarted")

                if (muxerStarted) {
                    try {
                        mediaMuxer?.stop()
                        Log.d(TAG, "Muxer stopped")
                    } catch (e: Exception) {
                        Log.w(TAG, "Muxer stop error: ${e.message}")
                    }
                }
                try {
                    mediaMuxer?.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Muxer release error: ${e.message}")
                }

                // Destroy EGL surface - dùng current display thay vì member eglDisplay
                val currentDisplay = EGL14.eglGetCurrentDisplay()
                if (encoderEglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(encoderEglDisplay, encoderEglSurface)
                }
                if (encoderContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(encoderEglDisplay, encoderContext)
                }
                encoderSurface?.release()
                encoderContext = EGL14.EGL_NO_CONTEXT
                encoderEglDisplay = EGL14.EGL_NO_DISPLAY
                encoderEglSurface = EGL14.EGL_NO_SURFACE

                // Sử dụng file đã ghi trong cache
                val finalPath = savedOutputFile?.absolutePath
                val fileSize = savedOutputFile?.length() ?: 0

                Log.d(
                    TAG,
                    "Recording stopped, file: $finalPath, exists: ${savedOutputFile?.exists()}, size: $fileSize"
                )

                // Chỉ gọi callback với path nếu file thực sự có data
                if (fileSize > 0) {
                    post { onRecordingStateChanged?.invoke(false, finalPath) }
                } else {
                    Log.e(TAG, "Video file is empty! muxerStarted=$muxerStarted, framesSent=$framesSentToEncoder")
                    post { onRecordingStateChanged?.invoke(false, null) }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Stop recording error: ${e.message}", e)
                post { onRecordingStateChanged?.invoke(false, null) }
            }

            mediaCodec = null
            mediaMuxer = null
            encoderSurface = null
            encoderEglSurface = EGL14.EGL_NO_SURFACE
            outputFile = null
        }
    }

    fun isRecording(): Boolean = isRecording

    private fun drainEncoder(eos: Boolean) {
        if (eos) {
            Log.d(TAG, "drainEncoder: signaling end of stream")
            try {
                mediaCodec?.signalEndOfInputStream()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "signalEndOfInputStream failed: ${e.message}")
                return
            }
        }

        val codec = mediaCodec ?: return
        var frameCount = 0

        while (true) {
            // Guard against calling dequeueOutputBuffer on a released codec
            if (isEncoderReleased && !eos) return

            val idx = try {
                codec.dequeueOutputBuffer(bufferInfo, if (eos) 10000 else 0)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "dequeueOutputBuffer failed (codec may be released): ${e.message}")
                break
            }

            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!eos) break
                    // Nếu EOS, tiếp tục chờ
                }

                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) {
                        Log.w(TAG, "Format changed after muxer started!")
                        continue
                    }
                    val format = codec.outputFormat
                    Log.d(TAG, "Encoder output format changed: $format")
                    videoTrackIndex = mediaMuxer?.addTrack(format) ?: -1
                    mediaMuxer?.start()
                    muxerStarted = true
                    Log.d(TAG, "Muxer started, track index: $videoTrackIndex")
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
                        Log.d(TAG, "drainEncoder: EOS reached, total frames: $frameCount")
                        break
                    }
                }
            }
        }

        if (frameCount > 0) {
            Log.d(TAG, "drainEncoder: wrote $frameCount frames in this call")
        }
    }

    private fun createOutputFile(ctx: Context): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return File(ctx.cacheDir, "VID_$ts.mp4")
    }

    fun createVideoFileInCache(context: Context): File {
        val fileName = "VID_${
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        }.mp4"

        return File(context.cacheDir, fileName)
    }

    private fun saveToGallery(ctx: Context, file: File) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }

            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)

            uri?.let {
                resolver.openOutputStream(it)?.use { os ->
                    file.inputStream().use { inp -> inp.copyTo(os) }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(it, values, null, null)
                }
            }

            file.delete()
            Log.d(TAG, "Saved to gallery")

        } catch (e: Exception) {
            Log.e(TAG, "Gallery save error: ${e.message}")
        }
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
}