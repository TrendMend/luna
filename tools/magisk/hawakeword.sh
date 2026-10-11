#!/system/bin/sh
# Magisk boot script: starts the managed HA Wake Word app after boot on a rooted device.
# Install: see docs/MANAGED.md (adb push + copy to /data/adb/service.d/).
PKG=com.example.hawakeword.managed

# Wait for the system, then for storage to be unlocked and the UI to settle.
until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 2; done
sleep 20

pm grant $PKG android.permission.RECORD_AUDIO
pm grant $PKG android.permission.POST_NOTIFICATIONS
dumpsys deviceidle whitelist +$PKG
appops set $PKG RUN_IN_BACKGROUND allow

# Opening the app starts listening in the managed build; then return to the home screen.
am start -n $PKG/com.example.hawakeword.MainActivity
sleep 8
input keyevent KEYCODE_HOME
