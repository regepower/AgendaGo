package de.regepower.agendago

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.CheckBox
import android.widget.Toast
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Update check against the app's GitHub releases (no Play Store / F-Droid yet).
 * At most once a day on start, switchable in the help dialog ([toggle]). A newer release is
 * offered in a dialog; "Download" opens the APK link in the browser, which downloads it and
 * starts the installer — so this app needs no REQUEST_INSTALL_PACKAGES, only INTERNET.
 * Drop-in for all our apps: needs the strings source_url and update_* (EN + DE).
 */
object UpdateCheck {
    private const val PREFS = "update_check"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST = "last"
    private const val KEY_SKIP = "skip"
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val TIMEOUT_MS = 10_000

    private class Release(
        val tag: String,
        val url: String,
    )

    fun enabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, true)

    /** Call from the main activity's onResume. */
    fun onStart(a: Activity) {
        if (!enabled(a)) return
        val p = prefs(a)
        val now = System.currentTimeMillis()
        if (now - p.getLong(KEY_LAST, 0L) < DAY_MS) return
        p.edit().putLong(KEY_LAST, now).apply()
        val api = apiUrl(a.getString(R.string.source_url)) ?: return
        val app = a.applicationContext
        Thread {
            val release = fetch(api, a.getString(R.string.app_name)) ?: return@Thread
            val installed = installedVersion(app) ?: return@Thread
            if (!newer(release.tag, installed) || release.tag == prefs(app).getString(KEY_SKIP, null)) return@Thread
            a.runOnUiThread { if (!a.isFinishing && !a.isDestroyed) offer(a, release, installed) }
        }.start()
    }

    /** Switch for the help dialog: "Check for updates on start". */
    fun toggle(a: Activity): CheckBox =
        CheckBox(a).apply {
            text = a.getString(R.string.update_auto)
            isChecked = enabled(a)
            setTextColor(a.getColor(R.color.md_on_surface))
            setOnCheckedChangeListener { _, on ->
                // Switching on checks again at the next start.
                prefs(a)
                    .edit()
                    .putBoolean(KEY_ENABLED, on)
                    .remove(KEY_LAST)
                    .apply()
            }
        }

    private fun offer(
        a: Activity,
        release: Release,
        installed: String,
    ) {
        AlertDialog
            .Builder(a)
            .setTitle(R.string.update_title)
            .setMessage(a.getString(R.string.update_text, a.getString(R.string.app_name), release.tag, installed))
            .setPositiveButton(R.string.update_install) { _, _ ->
                try {
                    a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.url)))
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(a, release.url, Toast.LENGTH_LONG).show()
                }
            }.setNegativeButton(R.string.update_later, null)
            .setNeutralButton(R.string.update_skip) { _, _ ->
                prefs(a).edit().putString(KEY_SKIP, release.tag).apply()
            }.show()
    }

    /** "https://github.com/owner/repo" → releases/latest API URL. */
    private fun apiUrl(source: String): String? {
        val m = Regex("github\\.com/([^/]+)/([^/?#]+)").find(source) ?: return null
        return "https://api.github.com/repos/${m.groupValues[1]}/${m.groupValues[2]}/releases/latest"
    }

    /** Latest release: tag and the APK asset (release page as fallback); null on any error. */
    private fun fetch(
        api: String,
        agent: String,
    ): Release? =
        try {
            val conn = URL(api).openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", agent)
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    null
                } else {
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val assets = json.optJSONArray("assets")
                    var apk: String? = null
                    for (i in 0 until (assets?.length() ?: 0)) {
                        val asset = assets!!.getJSONObject(i)
                        if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                            apk = asset.optString("browser_download_url")
                            break
                        }
                    }
                    val tag = json.optString("tag_name").removePrefix("v")
                    val url = apk?.takeIf { it.isNotBlank() } ?: json.optString("html_url")
                    if (tag.isBlank() || url.isBlank()) null else Release(tag, url)
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            null
        } catch (e: JSONException) {
            null
        } catch (e: SecurityException) {
            null
        }

    @Suppress("DEPRECATION")
    private fun installedVersion(context: Context): String? =
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }

    /** Numeric comparison of "1.0.88"-style versions. */
    private fun newer(
        candidate: String,
        installed: String,
    ): Boolean {
        val a = candidate.split('.').map { it.toIntOrNull() ?: 0 }
        val b = installed.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
