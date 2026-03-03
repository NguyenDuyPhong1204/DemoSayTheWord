package com.bigq.demodogcat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import timber.log.Timber
import java.io.File

class ScreenRecordService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var mediaCodec: MediaCodec? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaMuxer: MediaMuxer? = null
    private var muxerStarted = false
    private var trackIndex = -1
    private var recording = false
    private var outputFile: File? = null

    companion object {
        const val ACTION_STOP = "ACTION_STOP"
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (intent?.action == ACTION_STOP) {
            recording = false
            return START_NOT_STICKY
        }

        // 🔥 1️⃣ Start foreground FIRST
        val notification = createNotification()

        startForeground(
            1,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        // 🔥 2️⃣ THEN get MediaProjection
        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>("data")

        val manager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager

        mediaProjection =
            manager.getMediaProjection(resultCode, data!!)

        // 🔥 3️⃣ THEN start recording
        startRecording()

        return START_NOT_STICKY
    }

    private fun startRecording() {

        val metrics = resources.displayMetrics

        var width = metrics.widthPixels
        var height = metrics.heightPixels
        val density = metrics.densityDpi

        width = width and 0xFFFFFFFE.toInt()
        height = height and 0xFFFFFFFE.toInt()

        outputFile = File(
            cacheDir,
            "record_${System.currentTimeMillis()}.mp4"
        )

        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            width,
            height
        )

        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, 5_000_000)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

        mediaCodec = MediaCodec.createEncoderByType(
            MediaFormat.MIMETYPE_VIDEO_AVC
        )

        mediaCodec?.configure(
            format,
            null,
            null,
            MediaCodec.CONFIGURE_FLAG_ENCODE
        )

        val surface = mediaCodec!!.createInputSurface()
        mediaCodec?.start()

        mediaMuxer = MediaMuxer(
            outputFile!!.absolutePath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenRecord",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null
        )

        recording = true

        Thread {
            val bufferInfo = MediaCodec.BufferInfo()

            while (recording) {

                val index = mediaCodec!!.dequeueOutputBuffer(bufferInfo, 10000)

                if (index >= 0) {

                    val buffer = mediaCodec!!.getOutputBuffer(index)

                    if (!muxerStarted) {
                        val newFormat = mediaCodec!!.outputFormat
                        trackIndex = mediaMuxer!!.addTrack(newFormat)
                        mediaMuxer!!.start()
                        muxerStarted = true
                    }

                    mediaMuxer!!.writeSampleData(
                        trackIndex,
                        buffer!!,
                        bufferInfo
                    )

                    mediaCodec!!.releaseOutputBuffer(index, false)
                }
            }

            stopRecordingInternal()

        }.start()
    }

    private fun createNotification(): Notification {
        val channelId = "screen_record"
        val manager = getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(
                channelId,
                "Screen Record",
                NotificationManager.IMPORTANCE_LOW
            )
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Recording screen...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    private fun stopRecordingInternal() {

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null
        } catch (_: Exception) {
        }

        try {
            mediaMuxer?.stop()
            mediaMuxer?.release()
            mediaMuxer = null
        } catch (_: Exception) {
        }

        virtualDisplay?.release()
        mediaProjection?.stop()

        stopForeground(true)
        stopSelf()

        Timber.tag("RECORD").d("Saved to: ${outputFile?.absolutePath}")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}