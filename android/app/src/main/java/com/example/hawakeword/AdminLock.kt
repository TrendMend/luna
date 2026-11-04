package com.example.hawakeword

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.example.hawakeword.databinding.DialogPasswordBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Admin password for the managed build variant ([BuildConfig.LOCKED]). The user sets it on first
 * run; it then guards settings, wake word changes and stopping the listener.
 *
 * - Only a salted PBKDF2-HMAC-SHA256 hash is stored (in the encrypted preferences).
 * - Wrong guesses are throttled with a growing lockout.
 * - Unlocking lasts a couple of minutes of inactivity, then the app re-locks.
 * - There is no recovery: clearing app data or reinstalling removes the password and the Home
 *   Assistant registration with it. Block that with device management where it matters.
 *
 * In the standard variant every function here is a no-op that reports "unlocked".
 */
object AdminLock {
    const val MIN_LENGTH = 6
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val SESSION_MS = 2 * 60 * 1000L
    private const val FREE_ATTEMPTS = 5
    private const val BASE_LOCKOUT_MS = 30_000L
    private const val MAX_LOCKOUT_MS = 15 * 60 * 1000L

    @Volatile private var unlockedUntil = 0L

    val enabled: Boolean get() = BuildConfig.LOCKED

    fun isSet(prefs: Prefs) = prefs.adminHash.isNotEmpty()

    /** True when not managed, or when the admin has unlocked recently. */
    fun isUnlocked(): Boolean = !enabled || SystemClock.elapsedRealtime() < unlockedUntil

    /** Extends the unlocked session while the admin is actively using protected screens. */
    fun touch() {
        if (enabled && isUnlocked()) unlockedUntil = SystemClock.elapsedRealtime() + SESSION_MS
    }

    fun lockNow() {
        unlockedUntil = 0L
    }

    // --- password storage ------------------------------------------------------

    fun passwordProblem(password: String): String? =
        if (password.length < MIN_LENGTH) "Use at least $MIN_LENGTH characters" else null

    fun setPassword(prefs: Prefs, password: String) {
        require(passwordProblem(password) == null) { "Password too short" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.adminSalt = salt.toHex()
        prefs.adminHash = hash(password, salt).toHex()
        prefs.adminFailures = 0
        prefs.adminLockoutUntil = 0L
    }

    sealed class Attempt {
        object Ok : Attempt()
        object Wrong : Attempt()
        class LockedOut(val seconds: Long) : Attempt()
    }

    fun verify(prefs: Prefs, password: String): Attempt {
        val now = System.currentTimeMillis()
        if (now < prefs.adminLockoutUntil) {
            return Attempt.LockedOut(((prefs.adminLockoutUntil - now) + 999) / 1000)
        }
        val ok = isSet(prefs) &&
            MessageDigest.isEqual(hash(password, prefs.adminSalt.fromHex()), prefs.adminHash.fromHex())
        if (ok) {
            prefs.adminFailures = 0
            prefs.adminLockoutUntil = 0L
            unlockedUntil = SystemClock.elapsedRealtime() + SESSION_MS
            return Attempt.Ok
        }
        val failures = prefs.adminFailures + 1
        prefs.adminFailures = failures
        if (failures >= FREE_ATTEMPTS) {
            val steps = (failures - FREE_ATTEMPTS).coerceAtMost(10)
            val lockout = (BASE_LOCKOUT_MS shl steps).coerceAtMost(MAX_LOCKOUT_MS)
            prefs.adminLockoutUntil = now + lockout
            return Attempt.LockedOut((lockout + 999) / 1000)
        }
        return Attempt.Wrong
    }

    private fun hash(password: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS))
            .encoded

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun String.fromHex() = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // --- dialogs ---------------------------------------------------------------

    /** Runs [onSuccess] now if unlocked, otherwise after the admin enters the password. */
    fun require(activity: Activity, onCancel: () -> Unit = {}, onSuccess: () -> Unit) {
        if (isUnlocked()) {
            touch()
            onSuccess()
        } else {
            prompt(activity, onCancel, onSuccess)
        }
    }

    private fun prompt(activity: Activity, onCancel: () -> Unit, onSuccess: () -> Unit) {
        val prefs = Prefs(activity)
        val v = DialogPasswordBinding.inflate(activity.layoutInflater)
        v.tvMessage.text = "Enter the admin password to continue."

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Locked")
            .setView(v.root)
            .setPositiveButton("Unlock", null) // set below so wrong passwords keep the dialog open
            .setNegativeButton("Cancel") { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()

        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val entered = v.etCurrent.text.toString()
            v.etCurrent.setText("")
            when (val r = verify(prefs, entered)) {
                is Attempt.Ok -> {
                    dialog.dismiss()
                    onSuccess()
                }
                is Attempt.Wrong -> v.tilCurrent.error = "Wrong password"
                is Attempt.LockedOut -> v.tilCurrent.error = "Too many attempts. Try again in ${r.seconds} s"
            }
        }
    }

    /** Dialog to change the password (asks for the current one). */
    fun changePassword(activity: Activity, onDone: () -> Unit = {}) {
        val prefs = Prefs(activity)
        val v = DialogPasswordBinding.inflate(activity.layoutInflater)
        v.tvMessage.text = "Choose a new admin password (at least $MIN_LENGTH characters)."
        v.tilCurrent.hint = "Current password"
        v.tilNew.visibility = View.VISIBLE
        v.tilConfirm.visibility = View.VISIBLE

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Change password")
            .setView(v.root)
            .setPositiveButton("Change", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()

        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            v.tilCurrent.error = null
            v.tilNew.error = null
            v.tilConfirm.error = null
            val current = v.etCurrent.text.toString()
            val new = v.etNew.text.toString()
            val confirm = v.etConfirm.text.toString()

            passwordProblem(new)?.let { v.tilNew.error = it; return@setOnClickListener }
            if (new != confirm) {
                v.tilConfirm.error = "Passwords don't match"
                return@setOnClickListener
            }
            when (val r = verify(prefs, current)) {
                is Attempt.Ok -> {
                    setPassword(prefs, new)
                    dialog.dismiss()
                    onDone()
                }
                is Attempt.Wrong -> v.tilCurrent.error = "Wrong password"
                is Attempt.LockedOut -> v.tilCurrent.error = "Too many attempts. Try again in ${r.seconds} s"
            }
        }
    }

    /** Small helper for screens that show a lock hint. */
    fun describe(textView: TextView) {
        textView.text = if (!enabled) "" else if (isUnlocked()) "Unlocked" else "Locked"
    }
}
