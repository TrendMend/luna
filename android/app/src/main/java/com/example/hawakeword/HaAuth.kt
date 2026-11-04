package com.example.hawakeword

import android.net.Uri
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * OAuth2 (IndieAuth) sign-in and mobile_app registration.
 * Docs: https://developers.home-assistant.io/docs/auth_api/
 *       https://developers.home-assistant.io/docs/api/native-app-integration/setup
 *
 * The access token is only used once, to register the device. After that all traffic
 * goes through the webhook id, so no token needs to be stored.
 */
object HaAuth {
    /**
     * Must be declared on the client_id page (<link rel="redirect_uri" ...>). The scheme differs
     * per build flavor so the standard and managed apps can be installed side by side.
     */
    val REDIRECT_URI = "${BuildConfig.AUTH_SCHEME}://auth"

    fun authorizeUrl(haUrl: String, clientId: String): Uri =
        Uri.parse("$haUrl/auth/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("state", haUrl)
            .build()

    /** Exchanges the authorization code for an access token. Call from a background thread. */
    fun exchangeCode(haUrl: String, clientId: String, code: String): Result<String> = runCatching {
        val form = "grant_type=authorization_code" +
            "&code=" + URLEncoder.encode(code, "UTF-8") +
            "&client_id=" + URLEncoder.encode(clientId, "UTF-8")
        val (status, body) = post("$haUrl/auth/token", form, "application/x-www-form-urlencoded", null)
        check(status in 200..299) { "Token exchange failed: HTTP $status $body" }
        JSONObject(body).getString("access_token")
    }

    /** Registers this install with the mobile_app integration and stores the result. */
    fun register(haUrl: String, accessToken: String, prefs: Prefs, appVersion: String): Result<Unit> = runCatching {
        val payload = JSONObject()
            .put("device_id", prefs.deviceId)
            .put("app_id", "com.example.hawakeword")
            .put("app_name", "HA Wake Word")
            .put("app_version", appVersion)
            .put("device_name", prefs.deviceName)
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("os_name", "Android")
            .put("os_version", Build.VERSION.RELEASE)
            // HA returns a `secret` only if it supports encryption (PyNaCl installed).
            .put("supports_encryption", true)
            .toString()
        val (status, body) = post("$haUrl/api/mobile_app/registrations", payload, "application/json", accessToken)
        check(status in 200..299) {
            if (status == 404) "mobile_app integration not loaded in HA (HTTP 404)" else "Registration failed: HTTP $status $body"
        }
        val json = JSONObject(body)
        prefs.saveRegistration(
            webhookId = json.getString("webhook_id"),
            cloudhookUrl = json.optString("cloudhook_url").ifEmpty { null },
            remoteUiUrl = json.optString("remote_ui_url").ifEmpty { null },
            secret = json.optString("secret").ifEmpty { null },
        )
        // HA now has exactly what we just sent, so nothing to update yet.
        prefs.syncedRegistration = HaClient.registrationFingerprint(appVersion, prefs)
    }

    private fun post(url: String, body: String, contentType: String, bearer: String?): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", contentType)
            if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            return status to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            conn.disconnect()
        }
    }
}
