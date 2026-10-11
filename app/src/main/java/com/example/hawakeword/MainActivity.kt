package com.example.hawakeword

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.widget.doAfterTextChanged
import com.example.hawakeword.databinding.ActivityMainBinding
import com.example.hawakeword.databinding.ItemWordHomeBinding
import com.example.hawakeword.databinding.SheetWordBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.DynamicColors
import kotlin.concurrent.thread

/**
 * Home: whether it's listening, a start/stop button, anything that needs attention, and the
 * words being listened for, each with a switch and a gear for its own settings.
 * App-wide configuration lives in [SettingsActivity]; first run goes through [OnboardingActivity].
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs

    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) thread {
            val result = ModelCatalog.importFrom(this, uri)
            runOnUiThread {
                Toast.makeText(
                    this, result.fold({ "Added $it" }, { it.message ?: "Import failed" }), Toast.LENGTH_LONG
                ).show()
                refresh()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        // Installs that were set up before onboarding existed skip it.
        if (!prefs.onboarded && prefs.isRegistered && !prefs.onboardingStarted) prefs.onboarded = true
        if (!prefs.onboarded || (BuildConfig.LOCKED && !AdminLock.isSet(prefs))) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        if (BuildConfig.LOCKED) {
            // No screenshots or task-switcher previews of the app in a managed install.
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }

        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.btnUnlock.setOnClickListener { AdminLock.require(this) { refresh() } }

        b.toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_settings) {
                openSettings()
                true
            } else false
        }
        b.btnToggle.setOnClickListener { onToggle() }
        b.btnWarning.setOnClickListener { openSettings() }
        b.btnAdd.setOnClickListener { AdminLock.require(this) { pickModel.launch(arrayOf("*/*")) } }

        // Managed installs listen as soon as the app is opened, if everything is in place.
        if (BuildConfig.LOCKED && savedInstanceState == null && prefs.isRegistered &&
            Setup.micGranted(this) && !WakeWordService.running
        ) {
            startListening()
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        AdminLock.touch()
    }

    /** What the lock looked like when the screen was last drawn, to redraw when it expires. */
    private var drawnUnlocked = true

    private val liveTick = object : Runnable {
        override fun run() {
            renderLive()
            if (AdminLock.isUnlocked() != drawnUnlocked) refresh()
            b.root.postDelayed(this, 300)
        }
    }

    override fun onPause() {
        super.onPause()
        if (::b.isInitialized) b.root.removeCallbacks(liveTick)
    }

    override fun onResume() {
        super.onResume()
        if (!::b.isInitialized) return
        refresh()
        b.root.post(liveTick)
        // Cheap no-op unless the app/OS version or device name changed since HA last heard.
        if (prefs.isRegistered) thread {
            HaClient.syncRegistration(this, prefs)
            runOnUiThread { refresh() } // a 410 may have cleared the registration
        }
    }

    private fun openSettings() = AdminLock.require(this) {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    // --- start / stop -----------------------------------------------------------

    private fun onToggle() {
        if (WakeWordService.running) {
            AdminLock.require(this) {
                WakeWordService.stop(this)
                b.btnToggle.postDelayed({ refresh() }, 300)
            }
            return
        }
        if (!prefs.isRegistered) {
            Toast.makeText(this, "Sign in to Home Assistant first", Toast.LENGTH_LONG).show()
            openSettings()
            return
        }
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { !Setup.granted(this, it) }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
            return
        }
        startListening()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (Setup.micGranted(this)) {
            startListening()
        } else {
            Toast.makeText(this, "Microphone permission is required", Toast.LENGTH_LONG).show()
        }
    }

    private fun startListening() {
        WakeWordService.start(this)
        b.btnToggle.postDelayed({ refresh() }, 500)
    }

    /** Applies changed word settings by restarting the listener if it's running. */
    private fun restartIfRunning() {
        if (!WakeWordService.running) return
        WakeWordService.stop(this)
        b.btnToggle.postDelayed({ startListening() }, 500)
    }

    // --- rendering ---------------------------------------------------------------

    private fun refresh() {
        val running = WakeWordService.running
        drawnUnlocked = AdminLock.isUnlocked()
        // Managed and locked: show nothing but the unlock prompt.
        b.lockScreen.visibility = if (drawnUnlocked) View.GONE else View.VISIBLE
        b.content.visibility = if (drawnUnlocked) View.VISIBLE else View.GONE
        b.toolbar.menu.findItem(R.id.action_settings)?.isVisible = drawnUnlocked
        b.toolbar.subtitle = if (!drawnUnlocked) "" else Setup.appVersion(this) +
            if (AdminLock.enabled) "  ·  Unlocked" else ""
        if (!drawnUnlocked) return
        b.btnAdd.isEnabled = drawnUnlocked
        val models = ModelCatalog.available(this)
        val helpers = ModelCatalog.hasHelpers(this)

        b.tvState.text = if (running) "Listening" else "Stopped"
        b.btnToggle.text = if (running) "Stop" else "Start listening"
        b.tvDetail.text = when {
            !running && WakeWordService.lastError.isNotEmpty() -> "Stopped: ${WakeWordService.lastError}"
            WakeWordService.lastDetection.isNotEmpty() -> "Last: ${WakeWordService.lastDetection}"
            running -> "Waiting for a wake word…"
            else -> "Not listening"
        }

        // One thing at a time that needs attention.
        val warning = when {
            !prefs.isRegistered -> "Not connected to Home Assistant."
            !Setup.micGranted(this) -> "Microphone permission is off."
            models.isEmpty() || !helpers -> "No wake word models available. Add one below."
            !Setup.batteryUnrestricted(this) ->
                "Battery optimization may stop the listener. Allow unrestricted background use."
            else -> null
        }
        b.cardWarning.visibility = if (warning == null) View.GONE else View.VISIBLE
        warning?.let { b.tvWarning.text = it }

        b.wordList.removeAllViews()
        b.tvNoWords.visibility = if (models.isEmpty()) View.VISIBLE else View.GONE
        models.forEach { file -> b.wordList.addView(wordRow(file)) }
    }

    /** Live mic level and per-word scores while listening, so you can see what the engine hears. */
    private fun renderLive() {
        val live = WakeWordService.live
        if (!WakeWordService.running || !AdminLock.isUnlocked()) {
            b.cardLive.visibility = View.GONE
            return
        }
        b.cardLive.visibility = View.VISIBLE
        if (live == null) {
            b.tvLive.text = "Loading models…"
            return
        }
        fun bar(v: Float, width: Int = 12): String {
            val n = (v.coerceIn(0f, 1f) * width).toInt()
            return "█".repeat(n) + "░".repeat(width - n)
        }
        b.tvLive.text = buildString {
            append("Mic    ${bar(live.level.coerceAtMost(1f) * 2f)}  ${fmt(live.level)}")
            if (live.level < 0.01f) append("  (very quiet: check mic)")
            live.words.forEach {
                append("\n${it.name.padEnd(12).take(12)} ${bar(it.score)}  ${fmt(it.score)}")
                append("  peak ${fmt(it.peak)} / ${fmt(it.threshold)}")
            }
        }
    }

    private fun wordRow(file: String): View {
        val row = ItemWordHomeBinding.inflate(layoutInflater, b.wordList, false)
        val imported = ModelCatalog.isImported(this, file)

        row.tvWord.text = ModelCatalog.wakeWordName(file) + if (imported) " (added)" else ""
        row.tvWordInfo.text =
            "Detect ${fmt(prefs.thresholdFor(file))}  ·  Send ${fmt(prefs.sendThresholdFor(file))}"
        row.switchWord.isChecked = prefs.isModelEnabled(file)
        row.switchWord.setOnCheckedChangeListener { _, on ->
            prefs.setModelEnabled(file, on)
            restartIfRunning()
        }
        row.btnWordSettings.setOnClickListener { showWordSettings(file) }
        if (!AdminLock.isUnlocked()) {
            // Locked: look-only. Tapping the row asks for the password and redraws.
            row.switchWord.isEnabled = false
            row.btnWordSettings.isEnabled = false
            row.root.setOnClickListener { AdminLock.require(this) { refresh() } }
        }
        return row.root
    }

    /** Bottom sheet with this word's own thresholds (and removal for words you added). */
    private fun showWordSettings(file: String) {
        val sheet = BottomSheetDialog(this)
        val v = SheetWordBinding.inflate(layoutInflater)
        val name = ModelCatalog.wakeWordName(file)
        var changed = false

        v.tvSheetTitle.text = name
        v.tvSheetHelp.text = "Detect: score needed to count as heard. Send: score needed to message " +
            "Home Assistant (never below detect). Blank uses the app-wide defaults " +
            "(detect ${fmt(prefs.defaultThresholdFor(file))}, send ${if (prefs.sendThreshold == 0f) "same as detect" else fmt(prefs.sendThreshold)})."

        if (prefs.hasThresholdOverride(file)) v.etDetect.setText(prefs.thresholdFor(file).toString())
        if (prefs.hasSendOverride(file)) v.etSend.setText(prefs.sendThresholdFor(file).toString())

        v.etDetect.doAfterTextChanged {
            val t = it?.toString().orEmpty().trim()
            if (t.isEmpty()) prefs.setThresholdOverride(file, null)
            else t.toFloatOrNull()?.let { x -> prefs.setThresholdOverride(file, x) }
            changed = true
        }
        v.etSend.doAfterTextChanged {
            val t = it?.toString().orEmpty().trim()
            if (t.isEmpty()) prefs.setSendOverride(file, null)
            else t.toFloatOrNull()?.let { x -> prefs.setSendOverride(file, x) }
            changed = true
        }
        v.btnReset.setOnClickListener {
            v.etDetect.setText("")
            v.etSend.setText("")
        }

        if (ModelCatalog.isImported(this, file)) {
            v.btnRemove.visibility = View.VISIBLE
            v.btnRemove.setOnClickListener {
                ModelCatalog.delete(this, file)
                changed = true
                sheet.dismiss()
            }
        }
        v.btnDone.setOnClickListener { sheet.dismiss() }

        sheet.setOnDismissListener {
            refresh()
            if (changed) restartIfRunning()
        }
        sheet.setContentView(v.root)
        sheet.show()
    }

    private fun fmt(v: Float) = "%.2f".format(v)
}
