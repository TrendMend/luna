package dev.luna.assistant

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * The app's icon in the launcher belongs to an activity-alias (".Launcher" in the manifest), so it can be switched off without touching
 * MainActivity: an admin who hides the app (managed configuration `hide_app`) leaves Luna reachable only through the assistant gesture,
 * wake words and calls, all of which start MainActivity or AssistActivity directly.
 */
object LauncherIcon {
    const val ALIAS = "dev.luna.assistant.Launcher"

    /** Pure, so it is tested on a JVM. */
    fun shouldShow(appHidden: Boolean): Boolean = !appHidden

    /** Makes the icon match the current settings. Cheap to repeat: nothing happens (and nothing is killed) when it is already right. */
    fun apply(context: Context) {
        val show = shouldShow(Settings(context).appHidden)
        runCatching {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context.packageName, ALIAS),
                if (show) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
