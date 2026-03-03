package com.bigq.demodogcat

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

object Function {
    fun copyRawToCache(context: Context, rawResId: Int): String {
        val file = File(context.cacheDir, "temp_music.m4a")
        context.resources.openRawResource(rawResId).use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        }
        return file.absolutePath
    }
    fun mergeVideoAndAudio(
        videoPath: String,
        audioPath: String,
        outputPath: String
    ) {
        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        val videoExtractor = MediaExtractor()
        videoExtractor.setDataSource(videoPath)

        var videoTrackIndex = -1
        for (i in 0 until videoExtractor.trackCount) {
            val format = videoExtractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("video/")) {
                videoExtractor.selectTrack(i)
                videoTrackIndex = muxer.addTrack(format)
                break
            }
        }

        val audioExtractor = MediaExtractor()
        audioExtractor.setDataSource(audioPath)

        var audioTrackIndex = -1
        for (i in 0 until audioExtractor.trackCount) {
            val format = audioExtractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                audioExtractor.selectTrack(i)
                audioTrackIndex = muxer.addTrack(format)
                break
            }
        }

        muxer.start()

        val buffer = ByteBuffer.allocate(1024 * 1024)
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            val size = videoExtractor.readSampleData(buffer, 0)
            if (size < 0) break

            bufferInfo.offset = 0
            bufferInfo.size = size
            bufferInfo.presentationTimeUs = videoExtractor.sampleTime
            bufferInfo.flags = videoExtractor.sampleFlags

            muxer.writeSampleData(videoTrackIndex, buffer, bufferInfo)
            videoExtractor.advance()
        }

        while (true) {
            val size = audioExtractor.readSampleData(buffer, 0)
            if (size < 0) break

            bufferInfo.offset = 0
            bufferInfo.size = size
            bufferInfo.presentationTimeUs = audioExtractor.sampleTime
            bufferInfo.flags = audioExtractor.sampleFlags

            muxer.writeSampleData(audioTrackIndex, buffer, bufferInfo)
            audioExtractor.advance()
        }

        muxer.stop()
        muxer.release()
        videoExtractor.release()
        audioExtractor.release()
    }
}