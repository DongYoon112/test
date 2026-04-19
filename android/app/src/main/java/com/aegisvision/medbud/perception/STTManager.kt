package com.aegisvision.medbud.perception

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Speech-to-text — port of Python `stt.py`.
 *
 * One short audio clip in, transcript string out. Empty string = no speech
 * detected; `null` = transport / API error.
 */
class STTManager(
    private val apiKey: String,
    private val modelId: String = "scribe_v1",
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun transcribe(
        audioBytes: ByteArray,
        contentType: String = "audio/wav",
    ): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            Log.w(TAG, "ELEVENLABS_API_KEY missing; skipping STT call")
            return@withContext null
        }
        if (audioBytes.isEmpty()) return@withContext ""

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model_id", modelId)
            .addFormDataPart(
                "file", "clip",
                audioBytes.toRequestBody(contentType.toMediaType()),
            )
            .build()

        val req = Request.Builder()
            .url(ELEVENLABS_URL)
            .addHeader("xi-api-key", apiKey)
            .post(body)
            .build()

        try {
            http.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "STT http ${response.code}: ${response.body?.string()?.take(200)}")
                    return@withContext null
                }
                val payload = response.body?.string() ?: return@withContext null
                return@withContext JSONObject(payload).optString("text", "").trim()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "STT transport error", t)
            return@withContext null
        }
    }

    companion object {
        private const val TAG = "STTManager"
        private const val ELEVENLABS_URL = "https://api.elevenlabs.io/v1/speech-to-text"
    }
}
