package com.example.hawakeword

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.hawakeword.databinding.ActivitySettingsBinding
import com.example.hawakeword.databinding.ItemStepBinding
import com.google.android.material.color.DynamicColors
import kotlin.concurrent.thread

/**
 * App-wide settings: Home Assistant connection, default thresholds and cooldown, start on boot,
 * and the permission/background checklist. Per-word settings live behind each word's gear icon
 * on the home screen.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        // Managed build: settings are behind the admin password.
        if (!AdminLock.isUnlocked()) {
            AdminLock.require(this, onCancel = { finish() }) { recreate() }
            return
        }
        AdminLock.touch()
        if (BuildConfig.LOCKED && !BuildConfig.DEBUG) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }

        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.cardAdmin.visibility = if (AdminLock.enabled) View.VISIBLE else View.GONE
        b.btnChangePassword.setOnClickListener { AdminLock.changePassword(this) { toast("Password changed") } }
        b.btnLockNow.setOnClickListener {
            AdminLock.lockNow()
            finish()
        }

        b.toolbar.setNavigationOnClickListener { finish() }
        b.tvAbout.text = "HA Wake Word ${Setup.appVersion(this)}"

        b.etHaUrl.setText(prefs.haUrl)
        b.etDeviceName.setText(prefs.deviceNameOverride)
        b.etClientId.setText(
            if (prefs.clientId == "${prefs.haUrl}/local/hawakeword.html") "" else prefs.clientId
        )
        b.etThreshold.setText(prefs.threshold.toString())
        b.etSendThreshold.setText(prefs.sendThreshold.toString())
        b.etCooldown.setText(prefs.cooldownMs.toString())
        b.switchBoot.isChecked = prefs.startOnBoot

        b.btnSignIn.setOnClickListener { signIn() }
        b.btnTest.setOnClickListener { saveSettings(); sendTest() }
        b.btnUpdateName.setOnClickListener { updateRegistrationNow() }

        refresh()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        AdminLock.touch()
    }

    override fun onResume() {
        super.onResume()
        if (!::b.isInitialized) return
        if (!AdminLock.isUnlocked()) {
            finish() // session expired while the screen was in the background
            return
        }
        refresh()
    }

    /** Settings autosave when leaving the screen. */
    override fun onPause() {
        super.onPause()
        if (::b.isInitialized) saveSettings()
    }

    private fun saveSettings() {
        prefs.haUrl = b.etHaUrl.text.toString()
        prefs.clientId = b.etClientId.text.toString()
        prefs.deviceNameOverride = b.etDeviceName.text.toString()
        b.etThreshold.text.toString().toFloatOrNull()?.let { prefs.threshold = it.coerceIn(0.01f, 1f) }
        b.etSendThreshold.text.toString().toFloatOrNull()?.let { prefs.sendThreshold = it }
        b.etCooldown.text.toString().toLongOrNull()?.let { prefs.cooldownMs = it.coerceAtLeast(0) }
        prefs.startOnBoot = b.switchBoot.isChecked
    }

    // --- Home Assistant ----------------------------------------------------------

    private fun signIn() {
        saveSettings()
        if (prefs.haUrl.isBlank()) {
            toast("Enter the HA URL first", long = true)
            return
        }
        startActivity(Intent(Intent.ACTION_VIEW, HaAuth.authorizeUrl(prefs.haUrl, prefs.clientId)))
    }

    /** Saves the settings and pushes name/version changes to HA via update_registration. */
    private fun updateRegistrationNow() {
        saveSettings()
        if (!prefs.isRegistered) {
            toast("Sign in first; the name is used when registering", long = true)
            return
        }
        thread {
            val err = HaClient.syncRegistration(this, prefs, force = true)
            runOnUiThread {
                toast(err ?: "Updated in Home Assistant")
                refresh()
            }
        }
    }

    private fun sendTest() {
        thread {
            val err = HaClient.sendDetection(prefs, "test", 1.0f, System.currentTimeMillis())
            runOnUiThread {
                toast(err ?: "Event sent")
                refresh()
            }
        }
    }

    private fun toast(msg: String, long: Boolean = false) =
        Toast.makeText(this, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    // --- permissions / setup checklist ---------------------------------------------

    private class Item(
        val title: String, val done: Boolean, val hint: String, val action: String,
        val always: Boolean = false, val run: () -> Unit,
    )

    private fun setupItems(): List<Item> = listOf(
        Item(
            "Microphone", Setup.micGranted(this),
            "Needed to hear the wake word; audio never leaves the phone. Choose \"While using the app\" (not \"Only this time\"). Android has no \"all the time\" option for the microphone.", "Allow"
        ) { ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 2) },
        Item(
            "Notifications", Setup.notificationsGranted(this),
            "Android shows a persistent notification while listening; without permission it may be hidden.",
            "Allow"
        ) { ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2) },
        Item(
            "Battery: unrestricted", Setup.batteryUnrestricted(this),
            "Choose \"Allow\" so Android doesn't stop the listener. On some phones (Samsung, Xiaomi, OnePlus...) " +
                "also set Battery to \"Unrestricted\" and allow auto-start in the app's system settings.",
            "Fix"
        ) { Setup.requestBatteryExemption(this) },
        Item(
            "App permissions", false,
            "Opens Android's settings for this app. Under Permissions > Microphone choose " +
                "\"Allow only while using the app\". Use this if the permission prompt no longer appears.",
            "Open", always = true
        ) { Setup.openAppSettings(this) },
        Item(
            "Home Assistant", prefs.isRegistered,
            "Enter your HA URL above, then sign in. The app registers as a device in HA.", "Sign in"
        ) { signIn() },
    )

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (::b.isInitialized) refresh()
    }

    private fun renderSetup() {
        b.setupSteps.removeAllViews()
        setupItems().forEach { s ->
            val row = ItemStepBinding.inflate(layoutInflater, b.setupSteps, false)
            row.stepTitle.text = (if (s.always) "" else if (s.done) "✓  " else "○  ") + s.title
            if (s.done && !s.always) {
                row.stepHint.visibility = View.GONE
                row.stepAction.visibility = View.GONE
            } else {
                row.stepHint.text = s.hint
                row.stepAction.text = s.action
                row.stepAction.setOnClickListener { s.run() }
            }
            b.setupSteps.addView(row.root)
        }
    }

    private fun refresh() {
        b.tvConnection.text = when {
            !prefs.isRegistered -> "Not signed in"
            prefs.hassDeviceId.isEmpty() -> "Connected as \"${prefs.deviceName}\" (looking up device id…)"
            else -> "Connected as \"${prefs.deviceName}\"\nHA device id: ${prefs.hassDeviceId}"
        }
        b.btnSignIn.text = if (prefs.isRegistered) "Sign in again" else "Sign in"
        renderSetup()
    }
}
