package com.aegisvision.medbud.perception

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Vision analysis — port of Python `vlm.py`.
 *
 * Ships one JPEG frame to OpenAI's vision model and parses the JSON
 * reply into an [ObservationFrame]. Out-of-vocab labels from the model
 * are discarded; missing fields fall back to schema defaults so a
 * malformed reply never pollutes the state tracker.
 *
 * All network I/O happens on [Dispatchers.IO]; the suspend function is
 * safe to call from the pipeline worker coroutine.
 */
class VisionAnalysisManager(
    private val apiKey: String,
    private val model: String = "gpt-4o-mini",
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Returns `null` on any transport / parse error so the caller can skip. */
    suspend fun describe(jpegBytes: ByteArray, frameIndex: Int): ObservationFrame? =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                Log.w(TAG, "OPENAI_API_KEY missing; skipping VLM call")
                return@withContext null
            }
            val payload = buildPayload(jpegBytes, frameIndex)
            val req = Request.Builder()
                .url(OPENAI_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(payload.toRequestBody(JSON))
                .build()
            try {
                http.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "VLM http ${response.code}: ${response.body?.string()?.take(200)}")
                        return@withContext null
                    }
                    val body = response.body?.string() ?: return@withContext null
                    val content = JSONObject(body)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                    return@withContext sanitise(JSONObject(content))
                }
            } catch (t: Throwable) {
                Log.w(TAG, "VLM transport error", t)
                return@withContext null
            }
        }

    private fun buildPayload(jpegBytes: ByteArray, frameIndex: Int): String {
        val dataUrl = "data:image/jpeg;base64," +
            Base64.encodeToString(jpegBytes, Base64.NO_WRAP)

        val userContent = JSONArray()
            .put(JSONObject().apply {
                put("type", "text")
                put("text", "Frame #$frameIndex. Return the JSON object only. " +
                        "Remember: observation only, no diagnosis.")
            })
            .put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", dataUrl)
                    put("detail", "low")
                })
            })

        val messages = JSONArray()
            .put(JSONObject().apply {
                put("role", "system")
                put("content", VISION_SYSTEM_PROMPT)
            })
            .put(JSONObject().apply {
                put("role", "user")
                put("content", userContent)
            })

        return JSONObject().apply {
            put("model", model)
            put("temperature", 0.0)
            put("response_format", JSONObject().put("type", "json_object"))
            put("messages", messages)
        }.toString()
    }

    private fun sanitise(raw: JSONObject): ObservationFrame {
        fun cleanList(key: String, vocab: Set<String>): List<String> {
            if (!raw.has(key) || raw.isNull(key)) return emptyList()
            val arr = raw.optJSONArray(key) ?: return emptyList()
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val v = arr.optString(i, "")
                if (v in vocab) out += v
            }
            return out
        }

        fun pick(key: String, vocab: Set<String>, default: String): String {
            val v = raw.optString(key, default)
            return if (v in vocab) v else default
        }

        val confidence = raw.optDouble("confidence", 0.0).coerceIn(0.0, 1.0)
        val notes = raw.optString("notes", "").take(200)
        val detections = parseDetections(raw.optJSONArray("detections"))

        return ObservationFrame(
            bleeding = pick("bleeding", Vocab.BLEEDING, "unknown"),
            conscious = pick("conscious", Vocab.CONSCIOUS, "unknown"),
            breathing = pick("breathing", Vocab.BREATHING, "unknown"),
            bodyPartsVisible = cleanList("body_parts_visible", Vocab.BODY_PARTS),
            sceneRisk = cleanList("scene_risk", Vocab.SCENE_RISKS),
            personVisible = pick("person_visible", Vocab.PERSON_VISIBLE, "no"),
            confidence = confidence,
            notes = notes,
            detections = detections,
        )
    }

    /**
     * Parse and clamp bbox detections. Any entry with missing/invalid
     * coordinates is dropped — partial garbage from the model is better
     * ignored than drawn in the wrong place.
     */
    private fun parseDetections(arr: JSONArray?): List<Detection> {
        if (arr == null || arr.length() == 0) return emptyList()
        val out = ArrayList<Detection>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val label = o.optString("label", "").trim()
            if (label.isEmpty()) continue
            val bbox = o.optJSONArray("bbox") ?: continue
            if (bbox.length() < 4) continue
            val x = bbox.optDouble(0, -1.0)
            val y = bbox.optDouble(1, -1.0)
            val w = bbox.optDouble(2, -1.0)
            val h = bbox.optDouble(3, -1.0)
            if (x < 0.0 || y < 0.0 || w <= 0.0 || h <= 0.0) continue
            if (x > 1.0 || y > 1.0 || w > 1.0 || h > 1.0) continue
            out += Detection(
                label = label.take(32),
                severity = o.optString("severity", "").take(16),
                confidence = o.optDouble("confidence", 0.0).coerceIn(0.0, 1.0),
                x = x, y = y, w = w, h = h,
            )
        }
        return out.take(MAX_DETECTIONS)
    }

    companion object {
        private const val TAG = "VisionAnalysis"
        private const val OPENAI_URL = "https://api.openai.com/v1/chat/completions"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val MAX_DETECTIONS = 8

        /**
         * Encode I420 planes (as delivered by DAT) to a JPEG byte array.
         *
         * Android's [YuvImage] only understands NV21 — V/U interleaved after
         * the Y plane. So we pack Y then alternating V,U bytes into a single
         * byte array and let [YuvImage.compressToJpeg] handle the encoding.
         */
        fun encodeI420ToJpeg(
            yPlane: ByteArray,
            uPlane: ByteArray,
            vPlane: ByteArray,
            width: Int,
            height: Int,
            quality: Int = 70,
        ): ByteArray {
            val ySize = width * height
            val uvSize = ySize / 2
            val nv21 = ByteArray(ySize + uvSize)
            System.arraycopy(yPlane, 0, nv21, 0, ySize)
            val chromaCount = uvSize / 2
            var dst = ySize
            for (i in 0 until chromaCount) {
                nv21[dst++] = vPlane[i]
                nv21[dst++] = uPlane[i]
            }
            val stream = ByteArrayOutputStream(ySize)
            YuvImage(nv21, ImageFormat.NV21, width, height, null)
                .compressToJpeg(Rect(0, 0, width, height), quality, stream)
            return stream.toByteArray()
        }

        /** Vision prompt — identical semantics to Python `prompts.VISION_SYSTEM`. */
        val VISION_SYSTEM_PROMPT = """
You are a medical observation assistant running on live POV video from a first responder's smart glasses. Your ONLY job is to extract visually observable signals from one still frame at a time. You are NOT diagnosing, advising, or deciding treatment — a separate system does that.

Return STRICT JSON — no prose, no markdown fences — matching exactly this schema:

{
  "bleeding":           "none" | "minor" | "heavy" | "unknown",
  "conscious":          "yes"  | "no"    | "unknown",
  "breathing":          "normal" | "abnormal" | "none" | "unknown",
  "body_parts_visible": [ string, ... ],
  "scene_risk":         [ string, ... ],
  "person_visible":     "yes" | "no",
  "confidence":         number between 0.0 and 1.0,
  "notes":              string under 200 characters,
  "detections":         [ { "label": string, "severity": string, "confidence": number,
                            "bbox": [x, y, w, h] }, ... ]
}

Field rules:
- "bleeding": heavy=pooling/flowing; minor=small/limited; none=no blood; unknown=blurry/obstructed/no person.
- "conscious": no=slumped/limp/unresponsive; yes=upright/moving/looking at camera; otherwise unknown.
- "breathing": default unknown; none=chest motionless AND unresponsive; abnormal=gasping/choking; normal=visibly calm chest motion.
- "body_parts_visible": subset of ["face","head","neck","chest","abdomen","arm","hand","leg","foot","back"].
- "scene_risk": subset of ["fire","smoke","traffic","water","electrical","crowd","weapon","fall","cold","heat","none"].
- "person_visible": yes if a human body other than the wearer's own hand is in frame; if no, all medical fields must be "unknown" and arrays empty.
- "confidence": overall certainty about this frame; lower for blurry/dark/ambiguous.
- "notes": one short sentence of what is visually evident; do not speculate.
- "detections": one entry per visually-salient item in frame. label = short tag ("bleeding", "hand", "face", "fire", "person", ...). severity is optional ("heavy"/"minor" for bleeding; otherwise empty). bbox = [x, y, w, h] normalized to [0,1], top-left origin. Emit at most 6 entries; omit entirely if nothing is visible.

Hard rules:
1. When in doubt, use "unknown" or empty arrays. Never guess.
2. Never include fields outside the schema.
3. Never wrap the JSON in markdown or explanations.
4. If the image is unreadable, return all "unknown" / empty with low confidence.
""".trimIndent()
    }
}
