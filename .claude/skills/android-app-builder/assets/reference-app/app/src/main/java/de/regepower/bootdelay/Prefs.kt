package de.regepower.bootdelay

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("bootdelay", Context.MODE_PRIVATE)

    var initialDelaySec: Int
        get() = sp.getInt(KEY_INITIAL, 30)
        set(v) = sp.edit().putInt(KEY_INITIAL, v).apply()

    var gapSec: Int
        get() = sp.getInt(KEY_GAP, 10)
        set(v) = sp.edit().putInt(KEY_GAP, v).apply()

    var packages: List<String>
        get() = sp.getString(KEY_PKGS, "").orEmpty().split('\n').filter { it.isNotBlank() }
        set(v) = sp.edit().putString(KEY_PKGS, v.joinToString("\n")).apply()

    /** Boot (Settings.Global.BOOT_COUNT) for which the autostart already ran or was seen; -1 = unknown. */
    var lastBootCount: Int
        get() = sp.getInt(KEY_BOOT, -1)
        set(v) = sp.edit().putInt(KEY_BOOT, v).apply()

    /** Uptime at that moment; a smaller uptime later means the counter was stale and a new boot happened. */
    var lastBootUptime: Long
        get() = sp.getLong(KEY_UPTIME, 0L)
        set(v) = sp.edit().putLong(KEY_UPTIME, v).apply()

    /** Remember "this boot is done" (called once after an update so a stray BOOT_COMPLETED cannot trigger a run). */
    fun markBootHandled(context: Context) {
        lastBootCount = currentBootCount(context)
        lastBootUptime = SystemClock.elapsedRealtime()
    }

    companion object {
        fun currentBootCount(context: Context): Int =
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

        private const val KEY_BOOT = "boot_count"
        private const val KEY_UPTIME = "boot_uptime"
        private const val KEY_INITIAL = "initial"
        private const val KEY_GAP = "gap"
        private const val KEY_PKGS = "pkgs"
    }
}
