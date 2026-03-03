package com.bigq.demodogcat.opengl

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Video Encoder sử dụng MediaCodec để encode video từ OpenGL surface
 */
class VideoEncoder(
    private val context: Context,
    private val width: Int,
    private val height: Int,
    private val bitRate: Int = 6_000_000,
    private val frameRate: Int = 30
) {
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var inputSurface: Surface? = null
    
    private var videoTrackIndex = -1
    private var muxerStarted = false
    private var isRecording = false
    
    private var outputFile: File? = null
    
    private val bufferInfo = MediaCodec.BufferInfo()
    
    companion object {
        private const val TAG = "VideoEncoder"
        private const val MIME_TYPE = "video/avc" // H.264
        private const val I_FRAME_INTERVAL = 1
    }

    /**
     * Chuẩn bị encoder và trả về Surface để render
     */
    fun prepare(): Surface {
        // Create MediaFormat
        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        }
        
        // Create MediaCodec encoder
        mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = createInputSurface()
            start()
        }
        
        // Create output file
        outputFile = createOutputFile()
        
        // Create MediaMuxer
        mediaMuxer = MediaMuxer(outputFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        
        isRecording = true
        
        return inputSurface!!
    }

    /**
     * Drain encoder output (call this after each frame)
     */
    fun drainEncoder(endOfStream: Boolean) {
        if (endOfStream) {
            mediaCodec?.signalEndOfInputStream()
        }
        
        val codec = mediaCodec ?: return
        
        while (true) {
            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
            
            when {
                outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                }
                outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) {
                        throw RuntimeException("Format changed twice")
                    }
                    val newFormat = codec.outputFormat
                    videoTrackIndex = mediaMuxer?.addTrack(newFormat) ?: -1
                    mediaMuxer?.start()
                    muxerStarted = true
                }
                outputBufferIndex >= 0 -> {
                    val encodedData = codec.getOutputBuffer(outputBufferIndex)
                        ?: throw RuntimeException("Encoder output buffer was null")
                    
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    
                    if (bufferInfo.size != 0 && muxerStarted) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        mediaMuxer?.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                    }
                    
                    codec.releaseOutputBuffer(outputBufferIndex, false)
                    
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        break
                    }
                }
            }
        }
    }

    /**
     * Dừng recording và giải phóng resources
     */
    fun stop(): File? {
        isRecording = false
        
        try {
            drainEncoder(true)
        } catch (e: Exception) {
            Log.e(TAG, "Error draining encoder: ${e.message}")
        }
        
        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing codec: ${e.message}")
        }
        
        try {
            if (muxerStarted) {
                mediaMuxer?.stop()
            }
            mediaMuxer?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing muxer: ${e.message}")
        }
        
        inputSurface?.release()
        
        mediaCodec = null
        mediaMuxer = null
        inputSurface = null
        muxerStarted = false
        
        // Save to gallery
        outputFile?.let { saveToGallery(it) }
        
        return outputFile
    }

    /**
     * Kiểm tra xem encoder có đang recording không
     */
    fun isRecording(): Boolean = isRecording

    /**
     * Lấy input surface để render
     */
    fun getInputSurface(): Surface? = inputSurface

    private fun createOutputFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "VID_${timestamp}.mp4"
        
        // Sử dụng cache directory để tạm lưu file
        val cacheDir = context.cacheDir
        return File(cacheDir, fileName)
    }

    private fun saveToGallery(file: File) {
        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
            
            uri?.let { videoUri ->
                resolver.openOutputStream(videoUri)?.use { outputStream ->
                    file.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(videoUri, contentValues, null, null)
                }
                
                Log.d(TAG, "Video saved to gallery: $videoUri")
            }
            
            // Xóa file tạm
            file.delete()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error saving to gallery: ${e.message}")
        }
    }
}
