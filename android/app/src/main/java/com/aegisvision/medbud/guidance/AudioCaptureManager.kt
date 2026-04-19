package com.aegisvision.medbud.guidance

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Captures short microphone windows and wraps them in a WAV header ready
 * for upload to ElevenLabs STT.
 *
 * Format: 16 kHz, 16-bit, mono PCM. Matches what ElevenLabs' STT handles
 * well and keeps payload under ~200 KB for a 5-second window.
 *
 * Requires [Manifest.permission.RECORD_AUDIO]; returns an empty array if
 * the permission isn't granted rather than crashing, so the loop can
 * continue without voice.
 */
class AudioCaptureManager(private val context: Context) {

    /**
     * Record for [durationMs] and return a WAV-wrapped byte array.
     * Cancelling the coroutine stops recording immediately and returns
     * whatever was captured up to that point.
     */
    @SuppressLint("MissingPermission")
    suspend fun captureWav(durationMs: Long): ByteArray = withContext(Dispatchers.IO) {
        if (!hasRecordPermission()) {
            Log.w(TAG, "RECORD_AUDIO not granted; returning empty audio")
            return@withContext EMPTY
        }

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CFG, ENCODING)
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            Log.w(TAG, "AudioRecord.getMinBufferSize failed")
            return@withContext EMPTY
        }
        val bufSize = maxOf(minBuf, 4096)
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CFG, ENCODING, bufSize)
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord init threw", t)
            return@withContext EMPTY
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord not initialised")
            try { record.release() } catch (_: Throwable) {}
            return@withContext EMPTY
        }

        val pcm = ByteArrayOutputStream(bufSize * 10)
        val chunk = ByteArray(bufSize)
        try {
            record.startRecording()
            val end = System.currentTimeMillis() + durationMs
            while (System.currentTimeMillis() < end) {
                val n = record.read(chunk, 0, chunk.size)
                if (n > 0) pcm.write(chunk, 0, n)
                else if (n < 0) break
                // Respect coroutine cancellation between reads.
                if (!currentCoroutineContext().isActive) break
            }
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord read error", t)
        } finally {
            try { record.stop() } catch (_: Throwable) {}
            try { record.release() } catch (_: Throwable) {}
        }

        wrapWav(pcm.toByteArray())
    }

    private fun hasRecordPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    private fun wrapWav(pcm: ByteArray): ByteArray {
        if (pcm.isEmpty()) return EMPTY
        val channels = 1
        val bitsPerSample = 16
        val byteRate = SAMPLE_RATE * channels * bitsPerSample / 8
        val totalDataLen = pcm.size + 36

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(totalDataLen)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)                                 // Subchunk1Size (PCM)
            putShort(1)                                // AudioFormat = PCM
            putShort(channels.toShort())
            putInt(SAMPLE_RATE)
            putInt(byteRate)
            putShort((channels * bitsPerSample / 8).toShort()) // BlockAlign
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcm.size)
        }.array()

        return header + pcm
    }

    companion object {
        private const val TAG = "AudioCapture"
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL_CFG = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private val EMPTY = ByteArray(0)
    }
}
