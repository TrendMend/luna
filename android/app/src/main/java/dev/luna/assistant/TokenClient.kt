package dev.luna.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class LunaException(message: String) : Exception(message)

/** The only HTTP call: `POST /api/token` with the device's access key (docs/protocol.md). */
class TokenClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    data class Token(val serverUrl: String, val token: String)

    suspend fun fetch(base: String, key: String, body: String): Token = withContext(Dispatchers.IO) {
        val request = try {
            Request.Builder()
                .url("${base.trimEnd('/')}/api/token")
                .header("Authorization", "Bearer $key")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        } catch (e: IllegalArgumentException) {
            throw LunaException("That server address does not look right.")
        }
        try {
            http.newCall(request).execute().use { response ->
                when {
                    response.code == 401 -> throw LunaException("That access key was not accepted.")
                    response.code == 429 -> throw LunaException("Too many attempts. Wait a minute.")
                    !response.isSuccessful -> throw LunaException("Could not get a token (${response.code}).")
                }
                val json = JSONObject(response.body.string())
                Token(json.getString("server_url"), json.getString("participant_token"))
            }
        } catch (e: IOException) {
            throw LunaException("Could not reach Luna at $base.")
        } catch (e: org.json.JSONException) {
            throw LunaException("Luna answered, but not in the expected format.")
        }
    }
}
