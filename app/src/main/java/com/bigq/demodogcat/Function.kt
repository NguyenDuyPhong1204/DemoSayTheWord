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
    ): Boolean {
        try {
            // Kiểm tra file video tồn tại và có dữ liệu
            val videoFile = File(videoPath)
            if (!videoFile.exists() || videoFile.length() == 0L) {
                android.util.Log.e("Function", "Video file invalid: exists=${videoFile.exists()}, size=${videoFile.length()}")
                return false
            }

            val audioFile = File(audioPath)
            if (!audioFile.exists() || audioFile.length() == 0L) {
                android.util.Log.e("Function", "Audio file invalid: exists=${audioFile.exists()}, size=${audioFile.length()}")
                return false
            }

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

            var videoDurationUs = 0L
            while (true) {
                val size = videoExtractor.readSampleData(buffer, 0)
                if (size < 0) break

                bufferInfo.offset = 0
                bufferInfo.size = size
                bufferInfo.presentationTimeUs = videoExtractor.sampleTime
                bufferInfo.flags = videoExtractor.sampleFlags
                
                // Cập nhật timestamp cuối cùng của video
                videoDurationUs = videoExtractor.sampleTime

                muxer.writeSampleData(videoTrackIndex, buffer, bufferInfo)
                videoExtractor.advance()
            }

            while (true) {
                val size = audioExtractor.readSampleData(buffer, 0)
                if (size < 0) break
                
                // NẾU thời gian của nhạc vượt quá thời gian video -> Dừng lại (Cắt nhạc)
                if (audioExtractor.sampleTime > videoDurationUs) {
                    break
                }

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

            android.util.Log.d("Function", "Merge completed: $outputPath")
            return true
        } catch (e: Exception) {
            android.util.Log.e("Function", "Merge failed: ${e.message}", e)
            return false
        }
    }
}