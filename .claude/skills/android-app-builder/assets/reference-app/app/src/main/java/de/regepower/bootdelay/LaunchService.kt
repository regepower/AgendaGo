package de.regepower.bootdelay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast

/**
 * Starts the selected apps one after another.
 *
 * Android 15+ only allows background activity starts for apps that hold
 * SYSTEM_ALERT_WINDOW *and* currently show an overlay window, so a 1x1 invisible
 * overlay is kept for the duration of the run.
 */
class LaunchService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_NOT_STICKY
        running = true
        startForeground(
            NOTIF_ID,
            buildNotification(getString(R.string.notif_title)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        val source = intent?.getStringExtra(EXTRA_SOURCE)
        if (source != SOURCE_BOOT && source != SOURCE_TEST) {
            Log.w(TAG, "ignored start without a valid source: $source")
            finish()
            return START_NOT_STICKY
        }
        val prefs = Prefs(this)
        val pkgs = prefs.packages
        if (pkgs.isEmpty() || !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "nothing to do: packages=${pkgs.size} overlay=${Settings.canDrawOverlays(this)}")
            finish()
            return START_NOT_STICKY
        }
        // Test skips the start delay: the first app is launched right away.
        val initialMs = if (source == SOURCE_TEST) 0L else prefs.initialDelaySec * 1000L
        val gapMs = prefs.gapSec * 1000L
        handler.postDelayed({
            showOverlay()
            launchNext(pkgs, 0, gapMs)
        }, initialMs)
        return START_NOT_STICKY
    }

    private fun launchNext(pkgs: List<String>, index: Int, gapMs: Long) {
        if (index >= pkgs.size) {
            goHome()
            handler.postDelayed({ finish() }, TAIL_MS)
            return
        }
        val pkg = pkgs[index]
        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }
        Toast.makeText(this, getString(R.string.toast_starting, label, index + 1, pkgs.size), Toast.LENGTH_SHORT).show()
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) {
            Log.w(TAG, "no launch intent for $pkg")
        } else {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(launch)
            } catch (e: RuntimeException) {
                Log.e(TAG, "start failed for $pkg", e)
            }
        }
        handler.postDelayed({ launchNext(pkgs, index + 1, gapMs) }, gapMs)
    }

    /** After the last app (and a final gap, see [launchNext]) show the home screen. */
    private fun goHome() {
        try {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: RuntimeException) {
            Log.e(TAG, "home failed", e)
        }
    }

    private fun showOverlay() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = View(this)
        val lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        )
        try {
            wm.addView(view, lp)
            overlay = view
        } catch (e: RuntimeException) {
            Log.e(TAG, "overlay failed", e)
        }
    }

    private fun finish() {
        overlay?.let {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            try {
                wm.removeView(it)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "overlay already removed", e)
            }
        }
        overlay = null
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "BootDelay"
        private const val CHANNEL_ID = "launch"
        private const val NOTIF_ID = 1
        private const val TAIL_MS = 2000L

        const val SOURCE_BOOT = "boot"
        const val SOURCE_TEST = "test"
        private const val EXTRA_SOURCE = "source"

        fun start(context: Context, source: String) {
            context.startForegroundService(
                Intent(context, LaunchService::class.java).putExtra(EXTRA_SOURCE, source),
            )
        }
    }
}
