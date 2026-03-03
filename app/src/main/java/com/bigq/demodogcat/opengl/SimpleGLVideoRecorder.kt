package com.bigq.demodogcat.opengl

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.*
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.text.SimpleDateFormat
import java.util.*

/**
 * Simplified OpenGL Video Recorder với overlay
 * Render camera preview và overlay text lên video output
 */
class SimpleGLVideoRecorder(private val context: Context) {

    companion object {
        private const val TAG = "SimpleGLVideoRecorder"
        private const val MIME_TYPE = "video/avc"
        private const val BIT_RATE = 6_000_000
        private const val FRAME_RATE = 30
        private const val I_FRAME_INTERVAL = 1
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }

    // Video dimensions
    private var videoWidth = 1080
    private var videoHeight = 1920

    // MediaCodec và Muxer
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var muxerStarted = false
    private var bufferInfo = MediaCodec.BufferInfo()

    // EGL objects
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderSurface: Surface? = null

    // OpenGL objects
    private var cameraTextureId = 0
    private var overlayTextureId = 0
    private var cameraProgram = 0
    private var overlayProgram = 0

    // Buffers
    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var cameraTexBuffer: FloatBuffer
    private lateinit var overlayTexBuffer: FloatBuffer

    // Camera SurfaceTexture
    private var cameraSurfaceTexture: SurfaceTexture? = null
    private val stMatrix = FloatArray(16)

    // Recording state
    private var isRecording = false
    private var recordingStartTime = 0L
    private var outputFile: File? = null

    // Overlay
    private var overlayBitmap: Bitmap? = null
    private var overlayText = "📍 Recording..."
    private var needsOverlayUpdate = true

    // Handler thread
    private var encoderThread: HandlerThread? = null
    private var encoderHandler: Handler? = null

    // Callbacks
    var onRecordingStarted: (() -> Unit)? = null
    var onRecordingStopped: ((String?) -> Unit)? = null
    var onTimeUpdate: ((String) -> Unit)? = null

    // Camera facing
    private var isFrontCamera = false

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

    // Vertex coords (full-screen quad)
    private val vertexCoords = floatArrayOf(
        -1f, -1f, 0f,
        1f, -1f, 0f,
        -1f, 1f, 0f,
        1f, 1f, 0f
    )

    // Camera texture coords (flipped for correct orientation)
    private val cameraTexCoords = floatArrayOf(
        0f, 1f,
        1f, 1f,
        0f, 0f,
        1f, 0f
    )

    // Overlay texture coords
    private val overlayTexCoords = floatArrayOf(
        0f, 1f,
        1f, 1f,
        0f, 0f,
        1f, 0f
    )

    /**
     * Khởi tạo camera texture và trả về SurfaceTexture
     */
    fun setupCameraTexture(width: Int, height: Int): SurfaceTexture {
        videoWidth = width
        videoHeight = height

        // Start encoder thread
        encoderThread = HandlerThread("EncoderThread").apply { start() }
        encoderHandler = Handler(encoderThread!!.looper)

        // Create offline EGL context for encoding
        encoderHandler?.post {
            setupEGL()
            setupGL()
        }

        // Wait for setup
        Thread.sleep(200)

        return cameraSurfaceTexture!!
    }

    private fun setupEGL() {
        // 1. Get display
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("Unable to get EGL display")
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("Unable to initialize EGL")
        }

        // 2. Choose config
        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)

        // 3. Create context
        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)

        // 4. Create PBuffer surface (offscreen)
        val surfaceAttribs = intArrayOf(
            EGL14.EGL_WIDTH, videoWidth,
            EGL14.EGL_HEIGHT, videoHeight,
            EGL14.EGL_NONE
        )
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0], surfaceAttribs, 0)

        // 5. Make current
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    private fun setupGL() {
        // Create buffers
        vertexBuffer = createFloatBuffer(vertexCoords)
        cameraTexBuffer = createFloatBuffer(cameraTexCoords)
        overlayTexBuffer = createFloatBuffer(overlayTexCoords)

        // Create camera texture (OES)
        val textures = IntArray(2)
        GLES20.glGenTextures(2, textures, 0)

        cameraTextureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        overlayTextureId = textures[1]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // Create SurfaceTexture
        cameraSurfaceTexture = SurfaceTexture(cameraTextureId)

        // Create shader programs
        cameraProgram = createProgram(cameraVertexShader, cameraFragmentShader)
        overlayProgram = createProgram(overlayVertexShader, overlayFragmentShader)

        Matrix.setIdentityM(stMatrix, 0)

        Log.d(TAG, "GL setup complete")
    }

    private fun createFloatBuffer(array: FloatArray): FloatBuffer {
        return ByteBuffer.allocateDirect(array.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(array)
            .apply { position(0) }
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)

        return GLES20.glCreateProgram().also { program ->
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)
        }
    }

    private fun loadShader(type: Int, source: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
        }
    }

    /**
     * Bắt đầu quay video
     */
    fun startRecording() {
        if (isRecording) return

        encoderHandler?.post {
            try {
                // Create output file
                outputFile = createOutputFile()

                // Setup MediaCodec
                val format = MediaFormat.createVideoFormat(MIME_TYPE, videoWidth, videoHeight).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                    setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
                }

                mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE)
                mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoderSurface = mediaCodec?.createInputSurface()
                mediaCodec?.start()

                // Setup MediaMuxer
                mediaMuxer = MediaMuxer(outputFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                muxerStarted = false
                videoTrackIndex = -1

                isRecording = true
                recordingStartTime = System.currentTimeMillis()

                // Start encoding loop
                startEncodingLoop()

                encoderHandler?.post { onRecordingStarted?.invoke() }

                Log.d(TAG, "Recording started")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recording: ${e.message}", e)
            }
        }
    }

    private fun startEncodingLoop() {
        val frameInterval = 1000L / FRAME_RATE

        val encodingRunnable = object : Runnable {
            override fun run() {
                if (!isRecording) return

                try {
                    // Update time display
                    updateTimeOverlay()

                    // Render frame
                    renderFrame()

                    // Drain encoder
                    drainEncoder(false)

                } catch (e: Exception) {
                    Log.e(TAG, "Encoding error: ${e.message}")
                }

                if (isRecording) {
                    encoderHandler?.postDelayed(this, frameInterval)
                }
            }
        }

        encoderHandler?.post(encodingRunnable)
    }

    private fun updateTimeOverlay() {
        val elapsed = System.currentTimeMillis() - recordingStartTime
        val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val elapsedStr = formatElapsedTime(elapsed)

        overlayText = "📍 Hà Nội - $currentTime | ⏱️ $elapsedStr"
        needsOverlayUpdate = true

        // Notify UI
        onTimeUpdate?.invoke(elapsedStr)
    }

    private fun formatElapsedTime(ms: Long): String {
        val s = (ms / 1000) % 60
        val m = (ms / 1000 / 60) % 60
        val h = ms / 1000 / 60 / 60
        return if (h > 0) String.format("%02d:%02d:%02d", h, m, s)
        else String.format("%02d:%02d", m, s)
    }

    private fun renderFrame() {
        // Update camera texture
        cameraSurfaceTexture?.updateTexImage()
        cameraSurfaceTexture?.getTransformMatrix(stMatrix)

        // If recording, render to encoder surface
        if (isRecording && encoderSurface != null) {
            // Create window surface for encoder
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

            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            val encoderEglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], encoderSurface, surfaceAttribs, 0)

            EGL14.eglMakeCurrent(eglDisplay, encoderEglSurface, encoderEglSurface, eglContext)
            GLES20.glViewport(0, 0, videoWidth, videoHeight)

            // Clear
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            // Draw camera
            drawCamera()

            // Update overlay texture if needed
            if (needsOverlayUpdate) {
                updateOverlayTexture()
                needsOverlayUpdate = false
            }

            // Draw overlay
            drawOverlay()

            // Set presentation time
            val presentationTime = (System.nanoTime() - recordingStartTime * 1_000_000)
            EGLExt.eglPresentationTimeANDROID(eglDisplay, encoderEglSurface, presentationTime)

            // Swap
            EGL14.eglSwapBuffers(eglDisplay, encoderEglSurface)

            // Destroy temporary surface
            EGL14.eglDestroySurface(eglDisplay, encoderEglSurface)

            // Restore pbuffer context
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        }
    }

    private fun drawCamera() {
        GLES20.glUseProgram(cameraProgram)

        val positionHandle = GLES20.glGetAttribLocation(cameraProgram, "aPosition")
        val texCoordHandle = GLES20.glGetAttribLocation(cameraProgram, "aTextureCoord")
        val mvpMatrixHandle = GLES20.glGetUniformLocation(cameraProgram, "uMVPMatrix")
        val stMatrixHandle = GLES20.glGetUniformLocation(cameraProgram, "uSTMatrix")
        val textureHandle = GLES20.glGetUniformLocation(cameraProgram, "sTexture")

        // MVP matrix (flip for front camera)
        val mvpMatrix = FloatArray(16)
        Matrix.setIdentityM(mvpMatrix, 0)
        if (isFrontCamera) {
            Matrix.scaleM(mvpMatrix, 0, -1f, 1f, 1f)
        }

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glEnableVertexAttribArray(texCoordHandle)

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        cameraTexBuffer.position(0)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, cameraTexBuffer)

        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(stMatrixHandle, 1, false, stMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glUniform1i(textureHandle, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    private fun drawOverlay() {
        if (overlayBitmap == null) return

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        GLES20.glUseProgram(overlayProgram)

        val positionHandle = GLES20.glGetAttribLocation(overlayProgram, "aPosition")
        val texCoordHandle = GLES20.glGetAttribLocation(overlayProgram, "aTextureCoord")
        val mvpMatrixHandle = GLES20.glGetUniformLocation(overlayProgram, "uMVPMatrix")
        val textureHandle = GLES20.glGetUniformLocation(overlayProgram, "sTexture")

        // Overlay positioned at top-left
        val overlayVertices = floatArrayOf(
            -0.95f, 0.75f, 0f,
            0.5f, 0.75f, 0f,
            -0.95f, 0.95f, 0f,
            0.5f, 0.95f, 0f
        )
        val overlayVertexBuffer = createFloatBuffer(overlayVertices)

        val mvpMatrix = FloatArray(16)
        Matrix.setIdentityM(mvpMatrix, 0)

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glEnableVertexAttribArray(texCoordHandle)

        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, overlayVertexBuffer)

        overlayTexBuffer.position(0)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, overlayTexBuffer)

        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        GLES20.glUniform1i(textureHandle, 1)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun updateOverlayTexture() {
        val width = 600
        val height = 80

        overlayBitmap?.recycle()
        overlayBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val canvas = Canvas(overlayBitmap!!)

        // Background
        val bgPaint = Paint().apply {
            color = Color.argb(180, 0, 0, 0)
            isAntiAlias = true
        }
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 16f, 16f, bgPaint)

        // Text
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 32f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        val textY = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(overlayText, width / 2f, textY, textPaint)

        // Upload to GPU
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, overlayBitmap, 0)
    }

    private fun drainEncoder(endOfStream: Boolean) {
        if (endOfStream) {
            mediaCodec?.signalEndOfInputStream()
        }

        val codec = mediaCodec ?: return

        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)

            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    videoTrackIndex = mediaMuxer?.addTrack(format) ?: -1
                    mediaMuxer?.start()
                    muxerStarted = true
                }
                outputIndex >= 0 -> {
                    val buffer = codec.getOutputBuffer(outputIndex)
                        ?: throw RuntimeException("Null encoder output buffer")

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }

                    if (bufferInfo.size != 0 && muxerStarted) {
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        mediaMuxer?.writeSampleData(videoTrackIndex, buffer, bufferInfo)
                    }

                    codec.releaseOutputBuffer(outputIndex, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        break
                    }
                }
            }
        }
    }

    /**
     * Dừng quay video
     */
    fun stopRecording() {
        if (!isRecording) return

        isRecording = false

        encoderHandler?.post {
            try {
                drainEncoder(true)

                mediaCodec?.stop()
                mediaCodec?.release()

                if (muxerStarted) {
                    mediaMuxer?.stop()
                }
                mediaMuxer?.release()

                encoderSurface?.release()

                // Save to gallery
                outputFile?.let { saveToGallery(it) }

                val path = outputFile?.absolutePath
                onRecordingStopped?.invoke(path)

                Log.d(TAG, "Recording stopped: $path")

            } catch (e: Exception) {
                Log.e(TAG, "Stop recording error: ${e.message}", e)
                onRecordingStopped?.invoke(null)
            }

            mediaCodec = null
            mediaMuxer = null
            encoderSurface = null
        }
    }

    /**
     * Set camera facing
     */
    fun setFrontCamera(isFront: Boolean) {
        isFrontCamera = isFront
    }

    /**
     * Cập nhật overlay text
     */
    fun setOverlayText(text: String) {
        overlayText = text
        needsOverlayUpdate = true
    }

    private fun createOutputFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return File(context.cacheDir, "VID_$timestamp.mp4")
    }

    private fun saveToGallery(file: File) {
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

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)

            uri?.let {
                resolver.openOutputStream(it)?.use { os ->
                    file.inputStream().use { input -> input.copyTo(os) }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(it, values, null, null)
                }
            }

            file.delete()
            Log.d(TAG, "Saved to gallery: $uri")

        } catch (e: Exception) {
            Log.e(TAG, "Save to gallery error: ${e.message}", e)
        }
    }

    fun isRecording() = isRecording

    /**
     * Giải phóng resources
     */
    fun release() {
        stopRecording()

        encoderHandler?.post {
            cameraSurfaceTexture?.release()
            overlayBitmap?.recycle()

            GLES20.glDeleteProgram(cameraProgram)
            GLES20.glDeleteProgram(overlayProgram)
            GLES20.glDeleteTextures(2, intArrayOf(cameraTextureId, overlayTextureId), 0)

            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }

        encoderThread?.quitSafely()
    }
}
