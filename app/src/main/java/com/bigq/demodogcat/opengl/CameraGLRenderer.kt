package com.bigq.demodogcat.opengl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import com.bigq.demodogcat.opengl.EGLSurfaceWrapper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * OpenGL Renderer cho camera preview với overlay
 * Kết hợp camera texture và overlay texture để render
 */
class CameraGLRenderer(
    private val onSurfaceTextureAvailable: (SurfaceTexture) -> Unit
) : GLSurfaceView.Renderer {

    // Texture IDs
    private var cameraTextureId = 0
    private var overlayTextureId = 0
    
    // Surface texture cho camera
    private var cameraSurfaceTexture: SurfaceTexture? = null
    
    // Shader programs
    private var cameraProgram = 0
    private var overlayProgram = 0
    
    // Vertex và texture coordinates
    private lateinit var vertexBuffer: FloatBuffer
    private lateinit var textureBuffer: FloatBuffer
    private lateinit var overlayTextureBuffer: FloatBuffer
    
    // Transform matrices
    private val stMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    
    // Viewport dimensions
    private var viewWidth = 0
    private var viewHeight = 0
    
    // Overlay data
    private var overlayBitmap: Bitmap? = null
    private var overlayNeedsUpdate = false
    private var overlayText = ""
    
    // Recording surface
    private var recordingSurface: EGLSurfaceWrapper? = null
    private var isRecording = false
    
    // Overlay position và size (normalized 0-1)
    private var overlayX = 0.05f
    private var overlayY = 0.05f
    private var overlayWidth = 0.5f
    private var overlayHeight = 0.1f

    // Camera facing
    private var isFrontCamera = false

    companion object {
        // Vertex shader cho camera (OES texture)
        private const val CAMERA_VERTEX_SHADER = """
            uniform mat4 uMVPMatrix;
            uniform mat4 uSTMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTextureCoord = (uSTMatrix * aTextureCoord).xy;
            }
        """

        // Fragment shader cho camera (OES texture)
        private const val CAMERA_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
        """

        // Vertex shader cho overlay (2D texture)
        private const val OVERLAY_VERTEX_SHADER = """
            uniform mat4 uMVPMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTextureCoord = aTextureCoord.xy;
            }
        """

        // Fragment shader cho overlay (2D texture với alpha)
        private const val OVERLAY_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform sampler2D sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
        """

        // Vertex coordinates (full screen quad)
        private val VERTEX_COORDS = floatArrayOf(
            -1.0f, -1.0f, 0.0f,  // bottom left
            1.0f, -1.0f, 0.0f,   // bottom right
            -1.0f, 1.0f, 0.0f,   // top left
            1.0f, 1.0f, 0.0f     // top right
        )

        // Texture coordinates
        private val TEXTURE_COORDS = floatArrayOf(
            0.0f, 0.0f,  // bottom left
            1.0f, 0.0f,  // bottom right
            0.0f, 1.0f,  // top left
            1.0f, 1.0f   // top right
        )
        
        // Texture coordinates cho camera (flipped vertically)
        private val CAMERA_TEXTURE_COORDS = floatArrayOf(
            0.0f, 1.0f,  // bottom left
            1.0f, 1.0f,  // bottom right
            0.0f, 0.0f,  // top left
            1.0f, 0.0f   // top right
        )
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        
        // Initialize buffers
        initBuffers()
        
        // Create shader programs
        cameraProgram = createProgram(CAMERA_VERTEX_SHADER, CAMERA_FRAGMENT_SHADER)
        overlayProgram = createProgram(OVERLAY_VERTEX_SHADER, OVERLAY_FRAGMENT_SHADER)
        
        // Create camera texture (OES)
        cameraTextureId = createOESTexture()
        
        // Create overlay texture (2D)
        overlayTextureId = create2DTexture()
        
        // Create SurfaceTexture for camera
        cameraSurfaceTexture = SurfaceTexture(cameraTextureId)
        
        // Notify that surface texture is ready
        onSurfaceTextureAvailable(cameraSurfaceTexture!!)
        
        // Initialize matrices
        Matrix.setIdentityM(stMatrix, 0)
        Matrix.setIdentityM(mvpMatrix, 0)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        GLES20.glViewport(0, 0, width, height)
        
        // Update overlay to match new dimensions
        updateOverlayBitmap()
    }

    override fun onDrawFrame(gl: GL10?) {
        // Update camera texture
        cameraSurfaceTexture?.updateTexImage()
        cameraSurfaceTexture?.getTransformMatrix(stMatrix)
        
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        
        // Draw camera preview
        drawCamera()
        
        // Update overlay texture if needed
        if (overlayNeedsUpdate) {
            updateOverlayTexture()
            overlayNeedsUpdate = false
        }
        
        // Draw overlay on top
        drawOverlay()
        
        // If recording, also render to recording surface
        if (isRecording && recordingSurface != null) {
            recordingSurface?.makeCurrent()
            GLES20.glViewport(0, 0, viewWidth, viewHeight)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            drawCamera()
            drawOverlay()
            recordingSurface?.swapBuffers()
            recordingSurface?.makeCurrentNone()
        }
    }

    private fun initBuffers() {
        // Vertex buffer
        vertexBuffer = ByteBuffer.allocateDirect(VERTEX_COORDS.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(VERTEX_COORDS)
        vertexBuffer.position(0)

        // Texture buffer cho overlay (normal)
        overlayTextureBuffer = ByteBuffer.allocateDirect(TEXTURE_COORDS.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(TEXTURE_COORDS)
        overlayTextureBuffer.position(0)
        
        // Texture buffer cho camera (flipped)
        textureBuffer = ByteBuffer.allocateDirect(CAMERA_TEXTURE_COORDS.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(CAMERA_TEXTURE_COORDS)
        textureBuffer.position(0)
    }

    private fun drawCamera() {
        GLES20.glUseProgram(cameraProgram)
        
        // Get attribute/uniform locations
        val positionHandle = GLES20.glGetAttribLocation(cameraProgram, "aPosition")
        val texCoordHandle = GLES20.glGetAttribLocation(cameraProgram, "aTextureCoord")
        val mvpMatrixHandle = GLES20.glGetUniformLocation(cameraProgram, "uMVPMatrix")
        val stMatrixHandle = GLES20.glGetUniformLocation(cameraProgram, "uSTMatrix")
        val textureHandle = GLES20.glGetUniformLocation(cameraProgram, "sTexture")
        
        // Setup MVP matrix (flip horizontally for front camera)
        val cameraMvpMatrix = FloatArray(16)
        Matrix.setIdentityM(cameraMvpMatrix, 0)
        if (isFrontCamera) {
            Matrix.scaleM(cameraMvpMatrix, 0, -1f, 1f, 1f)
        }
        
        // Enable vertex arrays
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        
        // Set vertex data
        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        
        textureBuffer.position(0)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, textureBuffer)
        
        // Set uniforms
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, cameraMvpMatrix, 0)
        GLES20.glUniformMatrix4fv(stMatrixHandle, 1, false, stMatrix, 0)
        
        // Bind camera texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glUniform1i(textureHandle, 0)
        
        // Draw
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        
        // Disable vertex arrays
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    private fun drawOverlay() {
        if (overlayBitmap == null) return
        
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        
        GLES20.glUseProgram(overlayProgram)
        
        // Get attribute/uniform locations
        val positionHandle = GLES20.glGetAttribLocation(overlayProgram, "aPosition")
        val texCoordHandle = GLES20.glGetAttribLocation(overlayProgram, "aTextureCoord")
        val mvpMatrixHandle = GLES20.glGetUniformLocation(overlayProgram, "uMVPMatrix")
        val textureHandle = GLES20.glGetUniformLocation(overlayProgram, "sTexture")
        
        // Create overlay vertex buffer (positioned at top-left)
        val overlayVertices = createOverlayVertices()
        val overlayVertexBuffer = ByteBuffer.allocateDirect(overlayVertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(overlayVertices)
        overlayVertexBuffer.position(0)
        
        // Setup MVP matrix
        val overlayMvpMatrix = FloatArray(16)
        Matrix.setIdentityM(overlayMvpMatrix, 0)
        
        // Enable vertex arrays
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        
        // Set vertex data
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, overlayVertexBuffer)
        
        overlayTextureBuffer.position(0)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, overlayTextureBuffer)
        
        // Set uniforms
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, overlayMvpMatrix, 0)
        
        // Bind overlay texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        GLES20.glUniform1i(textureHandle, 1)
        
        // Draw
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        
        // Disable
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun createOverlayVertices(): FloatArray {
        // Convert normalized coordinates (0-1) to OpenGL coordinates (-1 to 1)
        val left = overlayX * 2 - 1
        val right = (overlayX + overlayWidth) * 2 - 1
        val bottom = 1 - (overlayY + overlayHeight) * 2
        val top = 1 - overlayY * 2
        
        return floatArrayOf(
            left, bottom, 0f,
            right, bottom, 0f,
            left, top, 0f,
            right, top, 0f
        )
    }

    private fun createOESTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return textures[0]
    }

    private fun create2DTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return textures[0]
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        
        return program
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        return shader
    }

    /**
     * Cập nhật overlay text (hiển thị thời gian realtime)
     */
    fun updateOverlayText(text: String) {
        overlayText = text
        overlayNeedsUpdate = true
    }

    /**
     * Đặt vị trí và kích thước overlay (normalized 0-1)
     */
    fun setOverlayPosition(x: Float, y: Float, width: Float, height: Float) {
        overlayX = x
        overlayY = y
        overlayWidth = width
        overlayHeight = height
    }

    /**
     * Đặt front/back camera
     */
    fun setFrontCamera(isFront: Boolean) {
        isFrontCamera = isFront
    }

    private fun updateOverlayBitmap() {
        if (viewWidth == 0 || viewHeight == 0) return
        
        val width = (viewWidth * overlayWidth).toInt().coerceAtLeast(1)
        val height = (viewHeight * overlayHeight).toInt().coerceAtLeast(1)
        
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // Background với bo góc
        val paint = Paint().apply {
            color = Color.argb(180, 0, 0, 0)
            isAntiAlias = true
        }
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 20f, 20f, paint)
        
        // Text
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = height * 0.5f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        
        val text = overlayText.ifEmpty { "📍 Đang quay..." }
        val textY = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(text, width / 2f, textY, textPaint)
        
        overlayBitmap?.recycle()
        overlayBitmap = bitmap
        overlayNeedsUpdate = true
    }

    private fun updateOverlayTexture() {
        overlayBitmap?.let { bitmap ->
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        }
    }

    /**
     * Lấy SurfaceTexture để kết nối với CameraX
     */
    fun getSurfaceTexture(): SurfaceTexture? = cameraSurfaceTexture

    /**
     * Bắt đầu recording
     */
    fun startRecording(surface: EGLSurfaceWrapper) {
        recordingSurface = surface
        isRecording = true
    }

    /**
     * Dừng recording
     */
    fun stopRecording() {
        isRecording = false
        recordingSurface = null
    }

    fun release() {
        cameraSurfaceTexture?.release()
        overlayBitmap?.recycle()
        GLES20.glDeleteProgram(cameraProgram)
        GLES20.glDeleteProgram(overlayProgram)
        GLES20.glDeleteTextures(1, intArrayOf(cameraTextureId), 0)
        GLES20.glDeleteTextures(1, intArrayOf(overlayTextureId), 0)
    }
}
