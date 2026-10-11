package com.example.hawakeword

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Talks to Home Assistant through the mobile_app webhook. Call from a background thread. */
object HaClient {
    const val EVENT_TYPE = "hawakeword_detected"

    /** Outcome of one webhook call: [error] is null on success, [body] the raw response text. */
    private class Reply(val error: String?, val body: String = "")

    /**
     * Fires a `hawakeword_detected` event. `device_id` is this phone's Home Assistant device
     * registry id, so automations can bind to the exact phone. Returns null on success,
     * otherwise an error message.
     */
    fun sendDetection(prefs: Prefs, wakeWord: String, score: Float, timestamp: Long): String? {
        val eventData = JSONObject()
            .put("wake_word", wakeWord)
            .put("score", score.toDouble())
            .put("device", prefs.deviceName)
            .put("timestamp", timestamp)
        if (prefs.hassDeviceId.isNotEmpty()) eventData.put("device_id", prefs.hassDeviceId)

        return postWebhook(
            prefs, "fire_event",
            JSONObject().put("event_type", EVENT_TYPE).put("event_data", eventData)
        ).error
    }

    /**
     * Learns this phone's device registry id from `get_config` (`hass_device_id`) and stores it.
     * It is the same id a blueprint's device picker returns, so it identifies the phone exactly,
     * independent of any name. Returns null on success, otherwise an error message.
     */
    fun refreshDeviceId(prefs: Prefs): String? {
        if (!prefs.isRegistered) return null
        val reply = postWebhook(prefs, "get_config", JSONObject())
        if (reply.error != null) return reply.error
        return try {
            var json = JSONObject(reply.body)
            // With a secret, HA encrypts responses too.
            if (json.optBoolean("encrypted") && prefs.secret.isNotEmpty()) {
                json = JSONObject(String(Crypto.decrypt(prefs.secret, json.getString("encrypted_data"))))
            }
            val id = json.optString("hass_device_id")
            if (id.isEmpty()) "get_config did not include a device id" else {
                prefs.hassDeviceId = id
                null
            }
        } catch (e: Exception) {
            "get_config: ${e.message}"
        }
    }

    /** What HA should currently know about this install. Changes trigger an update. */
    fun registrationFingerprint(appVersion: String, prefs: Prefs): String =
        listOf(appVersion, prefs.deviceName, Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE)
            .joinToString("|")

    /**
     * Keeps Home Assistant in sync: learns the device id if unknown, and sends
     * `update_registration` if the app version, Android version, model or device name differ
     * from what HA last received. Pass [force] to send regardless.
     * Returns null on success (or nothing to do), otherwise an error message.
     */
    fun syncRegistration(context: Context, prefs: Prefs, force: Boolean = false): String? {
        if (!prefs.isRegistered) return null
        if (prefs.hassDeviceId.isEmpty()) refreshDeviceId(prefs)
        if (!prefs.isRegistered) return "Device was removed in Home Assistant, sign in again"

        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
        } catch (e: Exception) {
            "0"
        }
        val fingerprint = registrationFingerprint(version, prefs)
        if (!force && fingerprint == prefs.syncedRegistration) return null

        val err = postWebhook(
            prefs, "update_registration",
            JSONObject()
                .put("app_version", version)
                .put("device_name", prefs.deviceName)
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("os_version", Build.VERSION.RELEASE)
        ).error
        if (err == null) prefs.syncedRegistration = fingerprint
        return err
    }

    /**
     * Posts a webhook request, trying the Cloudhook URL, then the remote UI URL, then the local
     * URL. With a secret, `type` stays in the clear and only `data` is encrypted.
     * A 410 means the device was removed in HA, so the registration is cleared.
     */
    private fun postWebhook(prefs: Prefs, type: String, data: JSONObject): Reply {
        if (!prefs.isRegistered) return Reply("Not signed in to Home Assistant")

        val id = prefs.webhookId
        val urls = listOfNotNull(
            prefs.cloudhookUrl.ifEmpty { null },
            prefs.remoteUiUrl.ifEmpty { null }?.let { "${it.trimEnd('/')}/api/webhook/$id" },
            prefs.haUrl.ifEmpty { null }?.let { "$it/api/webhook/$id" },
        )

        val body = try {
            val envelope = JSONObject().put("type", type)
            if (prefs.secret.isNotEmpty()) {
                envelope
                    .put("encrypted", true)
                    .put("encrypted_data", Crypto.encrypt(prefs.secret, data.toString().toByteArray()))
            } else {
                envelope.put("data", data)
            }.toString()
        } catch (e: Exception) {
            return Reply("Encryption error: ${e.message}")
        }

        var lastError = "No URL configured"
        for (url in urls) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                val text = (if (code >= 400) conn.errorStream else conn.inputStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                conn.disconnect()
                when {
                    code in 200..299 -> return Reply(null, text)
                    code == 410 -> {
                        prefs.clearRegistration()
                        return Reply("Device was removed in Home Assistant, sign in again")
                    }
                    else -> lastError = "HTTP $code"
                }
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            }
        }
        return Reply(lastError)
    }
}
