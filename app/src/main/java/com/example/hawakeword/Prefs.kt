package com.example.hawakeword

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/** Settings plus the mobile_app registration (webhook id etc.), stored encrypted. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "settings_encrypted",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /** Base URL of the HA instance, e.g. http://homeassistant.local:8123 (no trailing slash). */
    var haUrl: String
        get() = sp.getString("haUrl", "")?.ifEmpty { null } ?: BuildConfig.DEFAULT_HA_URL
        set(v) = sp.edit().putString("haUrl", v.trim().trimEnd('/')).apply()

    /**
     * OAuth client_id: URL of a page declaring our redirect (see docs/index.html).
     * Blank means "served by HA itself", i.e. <haUrl>/local/hawakeword.html (config/www/hawakeword.html).
     */
    var clientId: String
        get() = sp.getString("clientId", "")?.ifEmpty { null } ?: "$haUrl/local/hawakeword.html"
        set(v) = sp.edit().putString("clientId", v.trim()).apply()

    var threshold: Float
        get() = sp.getFloat("threshold", 0.5f)
        set(v) = sp.edit().putFloat("threshold", v).apply()

    /**
     * Threshold for one model file: its own override if set, then the built-in default for words
     * that ship with a tuned one (see [BuiltInDefaults]), then the app-wide [threshold].
     */
    fun thresholdFor(file: String): Float = sp.getFloat("thr_$file", defaultThresholdFor(file))

    /** What "use defaults" means for this word. */
    fun defaultThresholdFor(file: String): Float =
        BuiltInDefaults.threshold(ModelCatalog.wakeWordName(file)) ?: threshold

    fun hasThresholdOverride(file: String): Boolean = sp.contains("thr_$file")

    /** Pass null to remove the override and fall back to the default. */
    fun setThresholdOverride(file: String, value: Float?) {
        sp.edit().apply {
            if (value == null) remove("thr_$file") else putFloat("thr_$file", value.coerceIn(0.01f, 1f))
        }.apply()
    }

    /**
     * Minimum score to actually send an event to HA. 0 = same as the detection threshold.
     * Detections between the detection threshold and this value are still shown in the app
     * ("below send threshold") but not sent, which is handy for tuning.
     */
    var sendThreshold: Float
        get() = sp.getFloat("sendThreshold", 0f)
        set(v) = sp.edit().putFloat("sendThreshold", v.coerceIn(0f, 1f)).apply()

    fun hasSendOverride(file: String): Boolean = sp.contains("send_$file")

    /** Per-word send threshold override; null removes it. */
    fun setSendOverride(file: String, value: Float?) {
        sp.edit().apply {
            if (value == null) remove("send_$file") else putFloat("send_$file", value.coerceIn(0f, 1f))
        }.apply()
    }

    /** Effective score needed to send: never below the word's detection threshold. */
    fun sendThresholdFor(file: String): Float =
        maxOf(thresholdFor(file), sp.getFloat("send_$file", sendThreshold))

    var cooldownMs: Long
        get() = sp.getLong("cooldownMs", 2000L)
        set(v) = sp.edit().putLong("cooldownMs", v).apply()

    var startOnBoot: Boolean
        get() = sp.getBoolean("startOnBoot", false)
        set(v) = sp.edit().putBoolean("startOnBoot", v).apply()

    /** True once the first-run onboarding has been completed. */
    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit().putBoolean("onboarded", v).apply()

    /** Set when the onboarding wizard first opens. Lets us treat already-registered installs as done. */
    var onboardingStarted: Boolean
        get() = sp.getBoolean("onboardingStarted", false)
        set(v) = sp.edit().putBoolean("onboardingStarted", v).apply()

    /**
     * Whether a model is listened for. Until the user flips it, only words in
     * [BuiltInDefaults.ENABLED] are on; everything else bundled is off.
     */
    fun isModelEnabled(file: String): Boolean =
        sp.getBoolean("on_$file", ModelCatalog.wakeWordName(file) in BuiltInDefaults.ENABLED)

    fun setModelEnabled(file: String, on: Boolean) {
        sp.edit().putBoolean("on_$file", on).apply()
    }

    /** User-chosen device name; blank means "use the phone model". */
    var deviceNameOverride: String
        get() = sp.getString("deviceName", "") ?: ""
        set(v) = sp.edit().putString("deviceName", v.trim()).apply()

    /** Name shown in Home Assistant. */
    val deviceName: String
        get() = deviceNameOverride.ifBlank { android.os.Build.MODEL }

    /** Fingerprint of the registration data HA last received (registration or update_registration). */
    var syncedRegistration: String
        get() = sp.getString("syncedRegistration", "") ?: ""
        set(v) = sp.edit().putString("syncedRegistration", v).apply()

    // --- admin password (managed variant) ---------------------------------------
    // Only a salted PBKDF2 hash is stored, never the password.

    var adminHash: String
        get() = sp.getString("adminHash", "") ?: ""
        set(v) = sp.edit().putString("adminHash", v).apply()

    var adminSalt: String
        get() = sp.getString("adminSalt", "") ?: ""
        set(v) = sp.edit().putString("adminSalt", v).apply()

    /** Consecutive wrong passwords since the last success. */
    var adminFailures: Int
        get() = sp.getInt("adminFailures", 0)
        set(v) = sp.edit().putInt("adminFailures", v).apply()

    /** Wall-clock millis until which password attempts are refused. */
    var adminLockoutUntil: Long
        get() = sp.getLong("adminLockoutUntil", 0L)
        set(v) = sp.edit().putLong("adminLockoutUntil", v).apply()

    /** Stable per-install id sent as device_id during registration. */
    val deviceId: String
        get() = sp.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
            sp.edit().putString("deviceId", it).apply()
        }

    var webhookId: String
        get() = sp.getString("webhookId", "") ?: ""
        private set(v) = sp.edit().putString("webhookId", v).apply()

    var cloudhookUrl: String
        get() = sp.getString("cloudhookUrl", "") ?: ""
        private set(v) = sp.edit().putString("cloudhookUrl", v).apply()

    var remoteUiUrl: String
        get() = sp.getString("remoteUiUrl", "") ?: ""
        private set(v) = sp.edit().putString("remoteUiUrl", v).apply()

    /** Hex secret for webhook encryption; empty when HA didn't enable encryption. */
    var secret: String
        get() = sp.getString("secret", "") ?: ""
        private set(v) = sp.edit().putString("secret", v).apply()

    /** This phone's device id in Home Assistant's device registry (from get_config); blank until known. */
    var hassDeviceId: String
        get() = sp.getString("hassDeviceId", "") ?: ""
        set(v) = sp.edit().putString("hassDeviceId", v).apply()

    val isRegistered: Boolean get() = webhookId.isNotEmpty()

    fun saveRegistration(webhookId: String, cloudhookUrl: String?, remoteUiUrl: String?, secret: String?) {
        this.webhookId = webhookId
        this.cloudhookUrl = cloudhookUrl.orEmpty()
        this.remoteUiUrl = remoteUiUrl.orEmpty()
        this.secret = secret.orEmpty()
        hassDeviceId = "" // looked up again after (re)registering
    }

    fun clearRegistration() {
        webhookId = ""
        cloudhookUrl = ""
        remoteUiUrl = ""
        secret = ""
        syncedRegistration = ""
        hassDeviceId = ""
    }
}
