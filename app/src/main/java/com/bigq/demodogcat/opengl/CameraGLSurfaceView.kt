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
    private val backgroundPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val borderPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 5f }
    private val textPaint = Paint().apply {
        color = Color.BLACK
        textSize = 24f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    // Callbacks
    var onSurfaceTextureReady: ((SurfaceTexture) -> Unit)? = null
    var onRecordingStateChanged: ((Boolean, String?) -> Unit)? = null
    var onTimeUpdate: ((String) -> Unit)? = null

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

        // MVP matrix - flip horizontally for front camera
        val mvp = FloatArray(16)
        Matrix.setIdentityM(mvp, 0)
        if (isFrontCamera) {
            Matrix.scaleM(mvp, 0, -1f, 1f, 1f)
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

        try {
            // Switch CHỈ SURFACE, KHÔNG đổi context - để texture vẫn available
            if (!EGL14.eglMakeCurrent(
                    currentDisplay,
                    encoderEglSurface,
                    encoderEglSurface,
                    currentContext  // Sử dụng CÙNG context của GLSurfaceView
                )
            ) {
                val error = EGL14.eglGetError()
                Log.e(TAG, "Failed to make encoder surface current, error: $error")
                return
            }

            // Render camera và overlay vào encoder surface
            // Sử dụng videoWidth/videoHeight thay vì viewWidth/viewHeight để khớp với MediaCodec config
            GLES20.glViewport(0, 0, videoWidth, videoHeight)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            drawCamera()
            drawOverlay()

            // Set presentation time
            val pts = System.nanoTime() - recordingStartTimeNano
            EGLExt.eglPresentationTimeANDROID(currentDisplay, encoderEglSurface, pts)

            // Swap buffers để gửi frame đến encoder
            if (!EGL14.eglSwapBuffers(currentDisplay, encoderEglSurface)) {
                val error = EGL14.eglGetError()
                Log.e(TAG, "eglSwapBuffers failed, error: $error")
            }

            // Drain encoder
            drainEncoder(false)

        } finally {
            // Phục hồi surface của GLSurfaceView (vẫn dùng cùng context)
            if (!EGL14.eglMakeCurrent(
                    currentDisplay,
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

    private fun drawWordAnimationGrid() {
        // Overlay chiếm phần trên của video
        val w = if (videoWidth > 0) videoWidth else (if (viewWidth > 0) viewWidth else 1080)
        val h = if (videoHeight > 0) (videoHeight * 0.35f).toInt() else 600

        if (overlayBitmap == null || overlayBitmap?.width != w || overlayBitmap?.height != h) {
            overlayBitmap?.recycle()
            overlayBitmap = createBitmap(w, h)
        }

        val canvas = Canvas(overlayBitmap!!)
        canvas.drawColor(Color.WHITE) // Nền trắng như trong Compose

        // Title 1/5 và Recording time
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 32f
        textPaint.color = Color.BLACK
        canvas.drawText("1/5", 40f, 60f, textPaint)

        if (isRecording) {
            val elapsed = System.currentTimeMillis() - recordingStartTime
            val elapsedStr = formatTime(elapsed)
            val timePaint =
                Paint(textPaint).apply { color = Color.RED; textAlign = Paint.Align.RIGHT }
            canvas.drawText("REC $elapsedStr", w - 40f, 60f, timePaint)
            post { onTimeUpdate?.invoke(elapsedStr) }
        }

        // Vẽ Grid (4 cột)
        val cols = 4
        val padding = 30f
        val spacing = 20f
        val gridTop = 100f
        val itemWidth = (w - 2 * padding - (cols - 1) * spacing) / cols
        val itemHeight = itemWidth * 1.2f

        for (i in wordItems.indices) {
            val row = i / cols
            val col = i % cols
            val left = padding + col * (itemWidth + spacing)
            val top = gridTop + row * (itemHeight + spacing)
            val right = left + itemWidth
            val bottom = top + itemHeight

            // Vẽ border
            val isActive = (i == activeWordIndex)
            borderPaint.color = if (isActive) Color.parseColor("#00C853") else Color.BLACK
            borderPaint.strokeWidth = if (isActive) 10f else 4f
            canvas.drawRect(left, top, right, bottom, borderPaint)

            // Vẽ Icon (căn giữa phần trên của item)
            val item = wordItems[i]
            val icon = getIconBitmap(item.image)
            if (icon != null) {
                val iconSize = itemWidth * 0.55f
                val iconLeft = left + (itemWidth - iconSize) / 2
                val iconTop = top + 20f
                val destRect = RectF(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
                canvas.drawBitmap(icon, null, destRect, null)
            }

            // Vẽ Label
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = 26f
            textPaint.color = Color.BLACK
            canvas.drawText(item.label, left + itemWidth / 2, bottom - 25f, textPaint)
        }
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

                Log.d(TAG, "MediaCodec started, creating EGL surface...")

                // Lấy EGL display và config từ GLSurfaceView context hiện tại
                val currentDisplay = EGL14.eglGetCurrentDisplay()
                val currentContext = EGL14.eglGetCurrentContext()

                // Query config ID từ current context
                val configId = IntArray(1)
                EGL14.eglQueryContext(
                    currentDisplay,
                    currentContext,
                    EGL14.EGL_CONFIG_ID,
                    configId,
                    0
                )

                // Tìm config với ID đó
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
                Log.d(TAG, "Using config from GLSurfaceView context, configId: ${configId[0]}")

                // Create EGL surface for encoder với config của GLSurfaceView
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
                    Log.e(TAG, "Failed to create encoder EGL surface, error: $error")
                    throw RuntimeException("Failed to create encoder EGL surface")
                }

                Log.d(TAG, "Encoder EGL surface created successfully")

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

                post { onRecordingStateChanged?.invoke(true, null) }
                Log.d(TAG, "Recording started")

            } catch (e: Exception) {
                Log.e(TAG, "Start recording failed: ${e.message}", e)
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
            try {
                Log.d(TAG, "Stopping recording...")

                // Final drain - đảm bảo tất cả frames được ghi
                drainEncoder(true)

                Log.d(TAG, "Drain complete, stopping codec...")

                mediaCodec?.stop()
                mediaCodec?.release()

                Log.d(TAG, "Codec stopped, muxerStarted=$muxerStarted")

                if (muxerStarted) {
                    mediaMuxer?.stop()
                    Log.d(TAG, "Muxer stopped")
                }
                mediaMuxer?.release()

                if (encoderEglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, encoderEglSurface)
                }
                encoderSurface?.release()

                // Sử dụng file đã ghi trong cache
                val finalPath = savedOutputFile?.absolutePath

                Log.d(
                    TAG,
                    "Recording stopped, file: $finalPath, exists: ${savedOutputFile?.exists()}, size: ${savedOutputFile?.length()}"
                )

                // File đã được lưu trong cache, không cần lưu vào gallery

                post { onRecordingStateChanged?.invoke(false, finalPath) }

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
            mediaCodec?.signalEndOfInputStream()
        }

        val codec = mediaCodec ?: return
        var frameCount = 0

        while (true) {
            val idx = codec.dequeueOutputBuffer(bufferInfo, if (eos) 10000 else 0)

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