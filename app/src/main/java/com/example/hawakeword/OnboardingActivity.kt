package com.example.hawakeword

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.hawakeword.databinding.ActivityOnboardingBinding
import com.example.hawakeword.databinding.DialogPasswordBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.color.DynamicColors
import kotlin.concurrent.thread

/**
 * First-run wizard: welcome -> permissions -> background -> Home Assistant sign-in -> models -> done.
 * Each step reads the real system/app state, so it can be left and resumed (e.g. after the
 * browser sign-in) and always shows the right thing.
 */
class OnboardingActivity : AppCompatActivity() {

    private enum class Step { WELCOME, PASSWORD, PERMISSIONS, BATTERY, CONNECT, MODELS, DONE }

    /** The password step only exists in the managed (locked) build. */
    private val steps = Step.values().filter { it != Step.PASSWORD || BuildConfig.LOCKED }

    private lateinit var b: ActivityOnboardingBinding
    private lateinit var prefs: Prefs
    private var step = 0

    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) thread {
            val result = ModelCatalog.importFrom(this, uri)
            runOnUiThread {
                Toast.makeText(
                    this, result.fold({ "Imported $it" }, { it.message ?: "Import failed" }), Toast.LENGTH_LONG
                ).show()
                render()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val reopened = prefs.onboardingStarted
        prefs.onboardingStarted = true

        b = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Fresh start: welcome screen. Re-opened after being killed: pick up at the first unfinished step.
        step = savedInstanceState?.getInt("step")
            ?: if (reopened) steps.indexOf(firstIncompleteStep()) else 0
        b.progress.max = steps.size - 1
        b.etHaUrl.setText(prefs.haUrl)
        b.etDeviceName.setText(prefs.deviceNameOverride)
        b.etDeviceName.hint = Build.MODEL

        b.btnBack.setOnClickListener { if (step > 0) { step--; render() } }
        b.btnNext.setOnClickListener { next() }
        b.btnMic.setOnClickListener { request(Manifest.permission.RECORD_AUDIO) }
        b.btnNotif.setOnClickListener { request(Manifest.permission.POST_NOTIFICATIONS) }
        b.btnBattery.setOnClickListener { Setup.requestBatteryExemption(this) }
        b.btnSignIn.setOnClickListener { signIn() }
        b.btnSetPassword.setOnClickListener { setPassword() }
        b.btnImport.setOnClickListener { pickModel.launch(arrayOf("*/*")) }

        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("step", step)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Returning from sign-in: jump to the connect step's result.
        if (prefs.isRegistered && steps[step] == Step.CONNECT) step++
        render()
    }

    override fun onResume() {
        super.onResume()
        render() // permission / battery screens may have changed state
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        render()
    }

    private fun firstIncompleteStep(): Step = when {
        BuildConfig.LOCKED && !AdminLock.isSet(prefs) -> Step.PASSWORD
        !Setup.micGranted(this) -> Step.PERMISSIONS
        !prefs.isRegistered -> Step.CONNECT
        ModelCatalog.available(this).isEmpty() || !ModelCatalog.hasHelpers(this) -> Step.MODELS
        else -> Step.DONE
    }

    // --- navigation -------------------------------------------------------------

    private fun request(permission: String) {
        if (permission == Manifest.permission.POST_NOTIFICATIONS && Build.VERSION.SDK_INT < 33) return
        ActivityCompat.requestPermissions(this, arrayOf(permission), 1)
    }

    private fun signIn() {
        prefs.haUrl = b.etHaUrl.text.toString()
        prefs.deviceNameOverride = b.etDeviceName.text.toString()
        if (prefs.haUrl.isBlank()) {
            Toast.makeText(this, "Enter your Home Assistant address first", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent(Intent.ACTION_VIEW, HaAuth.authorizeUrl(prefs.haUrl, prefs.clientId)))
    }

    private fun setPassword() {
        if (AdminLock.isSet(prefs)) {
            AdminLock.changePassword(this) { render() }
            return
        }
        val v = DialogPasswordBinding.inflate(layoutInflater)
        v.tvMessage.text = "Choose an admin password (at least ${AdminLock.MIN_LENGTH} characters)."
        v.tilCurrent.visibility = View.GONE
        v.tilNew.visibility = View.VISIBLE
        v.tilConfirm.visibility = View.VISIBLE
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Admin password")
            .setView(v.root)
            .setPositiveButton("Set", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val pw = v.etNew.text.toString()
            val problem = AdminLock.passwordProblem(pw)
            when {
                problem != null -> v.tilNew.error = problem
                pw != v.etConfirm.text.toString() -> v.tilConfirm.error = "Passwords don't match"
                else -> {
                    AdminLock.setPassword(prefs, pw)
                    // Setting it counts as unlocking for this session.
                    AdminLock.verify(prefs, pw)
                    dialog.dismiss()
                    render()
                }
            }
        }
    }

    private fun next() {
        if (steps[step] == Step.DONE) {
            prefs.onboarded = true
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        step++
        render()
    }

    // --- rendering --------------------------------------------------------------

    private fun render() {
        val s = steps[step]
        b.progress.setProgressCompat(step, true)
        b.tvStepCount.text = "Step ${step + 1} of ${steps.size}"

        b.groupPassword.visibility = if (s == Step.PASSWORD) View.VISIBLE else View.GONE
        b.groupPermissions.visibility = if (s == Step.PERMISSIONS) View.VISIBLE else View.GONE
        b.groupBattery.visibility = if (s == Step.BATTERY) View.VISIBLE else View.GONE
        b.groupConnect.visibility = if (s == Step.CONNECT) View.VISIBLE else View.GONE
        b.groupModels.visibility = if (s == Step.MODELS) View.VISIBLE else View.GONE

        b.btnBack.visibility = if (step == 0) View.INVISIBLE else View.VISIBLE
        b.btnNext.text = if (s == Step.DONE) "Get started" else "Continue"
        var canContinue = true

        when (s) {
            Step.WELCOME -> {
                b.tvTitle.text = "Wake words for your smart home"
                b.tvBody.text = "HA Wake Word listens on this phone for the words you choose and tells " +
                    "Home Assistant when it hears one, so you can trigger automations. " +
                    "Audio is processed on the phone and never leaves it. Setup takes about a minute."
            }
            Step.PASSWORD -> {
                b.tvTitle.text = "Set an admin password"
                b.tvBody.text = "This is a managed install. The password protects settings, wake words " +
                    "and stopping the listener. Keep it somewhere safe: it can't be recovered."
                canContinue = AdminLock.isSet(prefs)
                b.btnSetPassword.text = if (canContinue) "Change password" else "Set password"
                b.tvPasswordState.text = if (canContinue) "✓  Password set" else ""
            }
            Step.PERMISSIONS -> {
                b.tvTitle.text = "Allow the microphone"
                b.tvBody.text = "The microphone is needed to hear the wake word. When Android asks, " +
                    "choose \"While using the app\" (never \"Only this time\", which is forgotten when " +
                    "the app closes). Android has no \"all the time\" option for the microphone; the " +
                    "listener keeps running in the background once started from the app. " +
                    "The notification permission lets Android show the \"listening\" notification."
                val mic = Setup.micGranted(this)
                val notif = Setup.notificationsGranted(this)
                b.tvMic.text = if (mic) "✓  Microphone allowed" else "○  Microphone"
                b.btnMic.visibility = if (mic) View.GONE else View.VISIBLE
                b.tvNotif.text = if (notif) "✓  Notifications allowed" else "○  Notifications"
                b.btnNotif.visibility = if (notif) View.GONE else View.VISIBLE
                canContinue = mic // notifications are optional
            }
            Step.BATTERY -> {
                b.tvTitle.text = "Keep it running"
                b.tvBody.text = "Android can stop apps that run quietly in the background. Allow this app " +
                    "to run unrestricted so the listener isn't killed. On Samsung, Xiaomi, OnePlus and similar " +
                    "phones, also set Battery to \"Unrestricted\" in the app's system settings."
                val ok = Setup.batteryUnrestricted(this)
                b.tvBattery.text = if (ok) "✓  Running unrestricted" else "○  Battery restrictions apply"
                b.btnBattery.visibility = if (ok) View.GONE else View.VISIBLE
                // Recommended, not required.
            }
            Step.CONNECT -> {
                b.tvTitle.text = "Connect Home Assistant"
                b.tvBody.text = "Enter your Home Assistant address and sign in. This app will appear in " +
                    "Home Assistant as a device, and wake word events show up there."
                val ok = prefs.isRegistered
                b.tvConnect.text = if (ok) "✓  Connected to Home Assistant" else ""
                b.btnSignIn.text = if (ok) "Sign in again" else "Sign in"
                canContinue = ok
            }
            Step.MODELS -> {
                b.tvTitle.text = "Wake words"
                val models = ModelCatalog.available(this)
                val ok = models.isNotEmpty() && ModelCatalog.hasHelpers(this)
                b.tvBody.text = "These words are included with the app. You can switch them on or off, " +
                    "tune them, and import your own later in Settings."
                b.tvModels.text = when {
                    !ModelCatalog.hasHelpers(this) ->
                        "Missing ${ModelCatalog.MEL} / ${ModelCatalog.EMBEDDING}. Import them to continue."
                    models.isEmpty() -> "No wake word models yet. Import one to continue."
                    else -> "Available: " + models.joinToString { ModelCatalog.wakeWordName(it) }
                }
                canContinue = ok
            }
            Step.DONE -> {
                b.tvTitle.text = "You're all set"
                b.tvBody.text = "Press Start on the home screen to begin listening. Thresholds, wake " +
                    "words, the Home Assistant connection and permissions are all in Settings."
            }
        }
        b.btnNext.isEnabled = canContinue
    }
}
