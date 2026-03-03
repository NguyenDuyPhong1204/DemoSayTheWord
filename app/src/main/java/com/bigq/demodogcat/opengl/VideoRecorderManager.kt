package com.bigq.demodogcat.opengl

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.bigq.demodogcat.opengl.CameraGLRenderer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Quản lý toàn bộ quá trình quay video với OpenGL overlay
 */
class VideoRecorderManager(
    private val context: Context
) {
    private var glTextureView: GLTextureView? = null
    private var renderer: CameraGLRenderer? = null
    private var videoEncoder: VideoEncoder? = null
    private var eglSurfaceWrapper: EGLSurfaceWrapper? = null
    
    private var isRecording = false
    private var recordingStartTime = 0L
    
    // Handler cho việc cập nhật thời gian
    private var timeHandler: Handler? = null
    private var timeHandlerThread: HandlerThread? = null
    
    // Callback khi có thay đổi trạng thái
    var onRecordingStateChanged: ((Boolean) -> Unit)? = null
    var onRecordingTimeUpdate: ((String) -> Unit)? = null
    var onRecordingComplete: ((String?) -> Unit)? = null
    
    companion object {
        private const val TAG = "VideoRecorderManager"
    }

    /**
     * Khởi tạo GLTextureView và renderer
     */
    fun initialize(glView: GLTextureView, onSurfaceReady: (SurfaceTexture) -> Unit) {
        glTextureView = glView
        
        glView.initialize(
            onSurfaceReady = { surfaceTexture ->
                onSurfaceReady(surfaceTexture)
            },
            onRendererCreated = { createdRenderer ->
                renderer = createdRenderer
            }
        )
    }

    /**
     * Cập nhật overlay text
     */
    fun updateOverlayText(text: String) {
        renderer?.updateOverlayText(text)
    }

    /**
     * Đặt vị trí overlay
     */
    fun setOverlayPosition(x: Float, y: Float, width: Float, height: Float) {
        renderer?.setOverlayPosition(x, y, width, height)
    }

    /**
     * Đặt camera facing
     */
    fun setFrontCamera(isFront: Boolean) {
        renderer?.setFrontCamera(isFront)
    }

    /**
     * Bắt đầu quay video
     */
    fun startRecording(width: Int, height: Int) {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return
        }
        
        try {
            // Tạo video encoder
            videoEncoder = VideoEncoder(context, width, height)
            val encoderSurface = videoEncoder!!.prepare()
            
            // Tạo EGL surface wrapper cho encoder surface
            eglSurfaceWrapper = EGLSurfaceWrapper(
                sharedContext = null, // Sẽ tạo context mới
                surface = encoderSurface,
                width = width,
                height = height
            )
            
            isRecording = true
            recordingStartTime = System.currentTimeMillis()
            
            // Bắt đầu timer cập nhật thời gian
            startTimeUpdater()
            
            onRecordingStateChanged?.invoke(true)
            
            Log.d(TAG, "Recording started")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}", e)
            stopRecording()
        }
    }

    /**
     * Dừng quay video
     */
    fun stopRecording() {
        if (!isRecording) {
            return
        }
        
        isRecording = false
        
        // Dừng timer
        stopTimeUpdater()
        
        // Dừng encoder
        val outputFile = try {
            videoEncoder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping encoder: ${e.message}", e)
            null
        }
        
        // Giải phóng EGL surface
        try {
            eglSurfaceWrapper?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing EGL surface: ${e.message}", e)
        }
        
        eglSurfaceWrapper = null
        videoEncoder = null
        
        onRecordingStateChanged?.invoke(false)
        onRecordingComplete?.invoke(outputFile?.absolutePath)
        
        Log.d(TAG, "Recording stopped: ${outputFile?.absolutePath}")
    }

    /**
     * Kiểm tra trạng thái recording
     */
    fun isRecording(): Boolean = isRecording

    /**
     * Render một frame và encode nếu đang recording
     */
    fun onFrameAvailable() {
        if (isRecording && videoEncoder != null && eglSurfaceWrapper != null) {
            try {
                // Render lên encoder surface
                eglSurfaceWrapper?.makeCurrent()
                
                // Set presentation time
                val presentationTime = (System.nanoTime() - recordingStartTime * 1_000_000)
                eglSurfaceWrapper?.setPresentationTime(presentationTime)
                
                // Swap buffers
                eglSurfaceWrapper?.swapBuffers()
                
                // Drain encoder
                videoEncoder?.drainEncoder(false)
                
                eglSurfaceWrapper?.makeCurrentNone()
                
            } catch (e: Exception) {
                Log.e(TAG, "Error encoding frame: ${e.message}")
            }
        }
    }

    private fun startTimeUpdater() {
        timeHandlerThread = HandlerThread("TimeUpdater").apply { start() }
        timeHandler = Handler(timeHandlerThread!!.looper)
        
        val updateRunnable = object : Runnable {
            override fun run() {
                if (isRecording) {
                    val elapsedMs = System.currentTimeMillis() - recordingStartTime
                    val timeString = formatElapsedTime(elapsedMs)
                    
                    // Cập nhật overlay với thời gian realtime
                    val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    val overlayText = "📍 Hà Nội - $currentTime | ⏱️ $timeString"
                    
                    renderer?.updateOverlayText(overlayText)
                    onRecordingTimeUpdate?.invoke(timeString)
                    
                    timeHandler?.postDelayed(this, 100) // Cập nhật mỗi 100ms
                }
            }
        }
        
        timeHandler?.post(updateRunnable)
    }

    private fun stopTimeUpdater() {
        timeHandler?.removeCallbacksAndMessages(null)
        timeHandlerThread?.quitSafely()
        timeHandler = null
        timeHandlerThread = null
    }

    private fun formatElapsedTime(elapsedMs: Long): String {
        val seconds = (elapsedMs / 1000) % 60
        val minutes = (elapsedMs / 1000 / 60) % 60
        val hours = elapsedMs / 1000 / 60 / 60
        
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    /**
     * Giải phóng tất cả resources
     */
    fun release() {
        stopRecording()
        renderer?.release()
        renderer = null
        glTextureView = null
    }
}
