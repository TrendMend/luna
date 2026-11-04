package com.example.hawakeword

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors
import kotlin.concurrent.thread

/**
 * Receives `hawakeword://auth?code=...&state=<ha url>` from the Home Assistant login page,
 * exchanges the code, registers the device, then returns to whichever screen started sign-in.
 */
class AuthRedirectActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth_redirect)

        val data = intent?.data
        val code = data?.getQueryParameter("code")
        if (data == null || code == null) {
            returnToApp()
            return
        }

        val prefs = Prefs(this)
        val base = prefs.haUrl.ifBlank { data.getQueryParameter("state").orEmpty() }
        val clientId = prefs.clientId
        val version = Setup.versionName(this)

        thread {
            val result = HaAuth.exchangeCode(base, clientId, code)
                .mapCatching { HaAuth.register(base, it, prefs, version).getOrThrow() }
                .onSuccess { HaClient.refreshDeviceId(prefs) } // bind events to this exact device
            runOnUiThread {
                result
                    .onSuccess { Toast.makeText(this, "Signed in and registered", Toast.LENGTH_SHORT).show() }
                    .onFailure {
                        Toast.makeText(this, it.message ?: "Sign-in failed", Toast.LENGTH_LONG).show()
                    }
                returnToApp()
            }
        }
    }

    private fun returnToApp() {
        val target = if (Prefs(this).onboarded) SettingsActivity::class.java
        else OnboardingActivity::class.java
        startActivity(
            Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }
}
