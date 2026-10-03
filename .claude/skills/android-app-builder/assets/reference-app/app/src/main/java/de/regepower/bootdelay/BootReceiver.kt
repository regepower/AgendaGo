package de.regepower.bootdelay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = Prefs(context)
        val boot = Prefs.currentBootCount(context)
        val uptime = SystemClock.elapsedRealtime()
        // Some devices re-deliver BOOT_COMPLETED later (e.g. when the app is started again): run once per boot only.
        val sameBoot = boot != -1 && boot == prefs.lastBootCount && uptime >= prefs.lastBootUptime
        if (sameBoot) {
            Log.i("BootDelay", "duplicate BOOT_COMPLETED ignored (boot $boot)")
            return
        }
        prefs.lastBootCount = boot
        prefs.lastBootUptime = uptime
        LaunchService.start(context, LaunchService.SOURCE_BOOT)
    }
}
