package dev.luna.assistant

import android.app.Activity
import android.os.Build
import android.view.WindowManager

/**
 * Lets this screen appear over the lock screen (and optionally wake the display), on every Android version Luna supports.
 * `Activity.setShowWhenLocked` and `setTurnScreenOn` exist from API 27; on API 26 the same effect comes from window flags.
 */
fun Activity.showOverLockScreen(turnScreenOn: Boolean) {
    if (Build.VERSION.SDK_INT >= 27) {
        setShowWhenLocked(true)
        if (turnScreenOn) setTurnScreenOn(true)
    } else {
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                if (turnScreenOn) WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON else 0,
        )
    }
}
