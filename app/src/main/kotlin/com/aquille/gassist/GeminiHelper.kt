package com.aquille.gassist

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

object GeminiHelper {

    private const val API_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent"

    private val SYSTEM_PROMPT = """
You are an Android automation AI assistant.
You will receive a screenshot of the current Android screen.
Analyze it and decide the next action to complete the user's goal.

Respond ONLY in this exact JSON format, no extra text:
{"thought":"brief reasoning","action":"tap|swipe|type|wait|done","x":0,"y":0,"x2":0,"y2":0,"text":""}

Rules:
- action must be one of: tap, swipe, type, wait, done
- If goal is complete, use action = "done"
- x, y are coordinates for tap or swipe start
- x2, y2 are swipe end coordinates (only for swipe)
- text is the string to type (only for type)
- Always include all fields even if unused (use 0 or "")
""".trimIndent()

    data class AiAction(
        val thought: String,
        val action: String,
        val x: Int = 0,
        val y: Int = 0,
        val x2: Int = 0,
        val y2: Int = 0,
        val text: String = ""
    )

    fun analyze(
        apiKey: String,
        screenshot: Bitmap,
        goal: String,
        history: String,
        originalWidth: Int,
        originalHeight: Int
    ): AiAction {
        val targetWidth  = 768
        val targetHeight = 1280

        val base64Image = bitmapToBase64(screenshot, targetWidth, targetHeight)

        val requestBody = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", SYSTEM_PROMPT) })
                })
            })

            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", "Goal: $goal\nHistory: $history\n\nWhat is the next action?")
                        })
                        put(JSONObject().apply {
                            put("inline_data", JSONObject().apply {
                                put("mime_type", "image/jpeg")
                                put("data", base64Image)
                            })
                        })
                    })
                })
            })

            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", 200)
                put("temperature", 0.1)
                put("responseMimeType", "application/json")
            })
        }

        val url  = URL(API_URL)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("x-goog-api-key", apiKey)
        conn.doOutput      = true
        conn.connectTimeout = 15000
        conn.readTimeout    = 15000

        conn.outputStream.use { it.write(requestBody.toString().toByteArray(Charsets.UTF_8)) }

        val responseCode = conn.responseCode
        val responseText = if (responseCode == HttpURLConnection.HTTP_OK) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            val errorBody = conn.errorStream?.bufferedReader()?.use { it.readText() }
            throw Exception("Gemini API Error $responseCode: $errorBody")
        }

        val rawAction = parseResponse(responseText)

        // Kalibrasi koordinat ke resolusi layar asli
        val scaleX = originalWidth.toFloat()  / targetWidth
        val scaleY = originalHeight.toFloat() / targetHeight

        return rawAction.copy(
            x  = (rawAction.x  * scaleX).toInt(),
            y  = (rawAction.y  * scaleY).toInt(),
            x2 = (rawAction.x2 * scaleX).toInt(),
            y2 = (rawAction.y2 * scaleY).toInt()
        )
    }

    private fun parseResponse(raw: String): AiAction {
        val root = JSONObject(raw)
        val text = root
            .getJSONArray("candidates")
            .getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
            .trim()

        // Bersihkan markdown fence kalau masih ada (jaga-jaga)
        val clean = text
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val obj = JSONObject(clean)

        // Support 2 format: flat (x,y di root) ATAU nested params{}
        // Flat adalah format baru yang lebih konsisten
        val params = obj.optJSONObject("params")

        return AiAction(
            thought = obj.optString("thought", ""),
            action  = obj.optString("action", "wait"),
            x       = params?.optInt("x") ?: obj.optInt("x"),
            y       = params?.optInt("y") ?: obj.optInt("y"),
            x2      = params?.optInt("x2") ?: obj.optInt("x2"),
            y2      = params?.optInt("y2") ?: obj.optInt("y2"),
            text    = params?.optString("text") ?: obj.optString("text", "")
        )
    }

    private fun bitmapToBase64(bitmap: Bitmap, width: Int, height: Int): String {
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val baos   = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 60, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }
}
