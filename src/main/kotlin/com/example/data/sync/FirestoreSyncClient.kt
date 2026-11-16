package com.example.data.sync

import android.util.Log
import com.example.data.auth.GoogleAuthManager
import com.example.desktop.platform.CloudConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Minimal Firestore REST client for the shared PWA document.
 *
 * Only the operations the sync engine needs are implemented: read one document and replace one
 * document. Requests are authenticated with the user's Firebase ID token, which is exactly what
 * the Firestore security rules check (`request.auth.uid == userId`).
 */
internal class FirestoreSyncClient(
    private val auth: GoogleAuthManager
) {

    private val http = OkHttpClient.Builder()
        .callTimeout(45, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private fun documentUrl(path: String): String {
        val encoded = path.split('/').joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }
        return "https://firestore.googleapis.com/v1/projects/${CloudConfig.PROJECT_ID}" +
            "/databases/${CloudConfig.FIRESTORE_DATABASE_ID}/documents/$encoded" +
            "?key=${CloudConfig.API_KEY}"
    }

    /** Reads a document, or returns null when it does not exist. */
    fun read(path: String): Map<String, Any?>? {
        val response = execute(Request.Builder().url(documentUrl(path)).get().build(), allowMissing = true)
        if (response.isMissing) return null
        val body = response.body ?: return null
        return decodeFields(JSONObject(body).optJSONObject("fields"))
    }

    /** Replaces the whole document with [data] (Firestore `set()` semantics). */
    fun write(path: String, data: Map<String, Any?>) {
        val fields = JSONObject()
        for ((key, value) in data) fields.put(key, encodeValue(value))
        val body = JSONObject().put("fields", fields).toString()
        val response = execute(
            Request.Builder().url(documentUrl(path)).patch(body.toRequestBody(jsonMedia)).build(),
            allowMissing = false
        )
        if (!response.isSuccessful) {
            throw FirebaseSyncException(response.message ?: "Could not write to Firestore")
        }
    }

    private class Response(
        val isSuccessful: Boolean,
        val isMissing: Boolean,
        val code: Int,
        val body: String?,
        val message: String?
    )

    private fun execute(request: Request, allowMissing: Boolean): Response {
        var attempt = 0
        while (true) {
            val token = auth.validIdToken(forceRefresh = attempt > 0)
                ?: throw FirebaseSyncException("You need to sign in with Google again")
            val authorized = request.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
            http.newCall(authorized).execute().use { response ->
                val body = response.body?.string()
                if (response.isSuccessful) {
                    return Response(true, false, response.code, body, null)
                }
                if (response.code == 404) {
                    return Response(false, true, 404, body, "Not found")
                }
                if (response.code == 401 && attempt == 0) {
                    attempt++
                    continue
                }
                val message = try {
                    JSONObject(body.orEmpty()).getJSONObject("error").optString("message", "")
                } catch (_: Exception) {
                    ""
                }
                Log.w(TAG, "Firestore ${request.method} failed (${response.code}): $message")
                return Response(
                    isSuccessful = false,
                    isMissing = false,
                    code = response.code,
                    body = body,
                    message = message.ifBlank { "HTTP ${response.code}" }
                )
            }
        }
    }

    // ------------------------------------------------------------- (de)coding

    private fun encodeValue(value: Any?): JSONObject = when (value) {
        null -> JSONObject().put("nullValue", JSONObject.NULL)
        is String -> JSONObject().put("stringValue", value)
        is Boolean -> JSONObject().put("booleanValue", value)
        is Int, is Long, is Short, is Byte -> JSONObject().put("integerValue", value.toString())
        is Float, is Double -> JSONObject().put("doubleValue", (value as Number).toDouble())
        is Map<*, *> -> {
            val fields = JSONObject()
            for ((k, v) in value) fields.put(k.toString(), encodeValue(v))
            JSONObject().put("mapValue", JSONObject().put("fields", fields))
        }
        is Iterable<*> -> {
            val values = JSONArray()
            for (v in value) values.put(encodeValue(v))
            JSONObject().put("arrayValue", JSONObject().put("values", values))
        }
        else -> JSONObject().put("stringValue", value.toString())
    }

    private fun decodeFields(fields: JSONObject?): Map<String, Any?>? {
        if (fields == null) return null
        val map = LinkedHashMap<String, Any?>()
        val keys = fields.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = fields.optJSONObject(key) ?: continue
            map[key] = decodeValue(value)
        }
        return map
    }

    private fun decodeValue(obj: JSONObject): Any? = when {
        obj.has("nullValue") -> null
        obj.has("stringValue") -> obj.getString("stringValue")
        obj.has("booleanValue") -> obj.getBoolean("booleanValue")
        obj.has("integerValue") -> obj.getString("integerValue").toLongOrNull() ?: 0L
        obj.has("doubleValue") -> obj.getDouble("doubleValue")
        obj.has("timestampValue") -> obj.getString("timestampValue")
        obj.has("mapValue") -> decodeFields(obj.getJSONObject("mapValue").optJSONObject("fields"))
        obj.has("arrayValue") -> {
            val array = obj.getJSONObject("arrayValue").optJSONArray("values")
            val list = mutableListOf<Any?>()
            if (array != null) for (i in 0 until array.length()) list.add(decodeValue(array.getJSONObject(i)))
            list
        }
        else -> null
    }

    companion object {
        private const val TAG = "FirestoreSyncClient"
    }
}

class FirebaseSyncException(message: String) : Exception(message)
