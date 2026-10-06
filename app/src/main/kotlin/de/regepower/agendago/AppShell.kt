package de.regepower.agendago

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.IOException

/**
 * Shared top row of all our apps: large bold app name, then save config, load config and help.
 * Drop-in: copy with ConfigIO.kt, the icons ic_save/ic_load/ic_help and the strings help, help_ok,
 * help_text, donate, donate_text, donate_url, cfg_save, cfg_load, cfg_saved, cfg_loaded, cfg_invalid, cfg_error, cfg_overwrite,
 * cfg_overwrite_ok, cfg_other_place; forward onActivityResult to [onResult].
 * Config file: picked with the system file dialog (JSON filter); the last file is remembered and
 * overwritten after asking, so no "(1)" copies; works with cloud providers too.
 */
object AppShell {
    const val REQ_SAVE = 7301
    const val REQ_LOAD = 7302
    private const val SHELL_PREFS = "appshell"
    private const val KEY_FILE = "file"

    /** Former app names whose config files still load after a rename. */
    var legacyNames: List<String> = emptyList()

    /** [sp]/[keep] are needed here because overwriting the remembered file needs no picker. */
    fun header(
        a: Activity,
        sp: SharedPreferences,
        keep: (String) -> Boolean = { false },
    ): LinearLayout =
        LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(
                TextView(a).apply {
                    text = a.getString(R.string.app_name)
                    textSize = 24f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(a.getColor(R.color.md_on_container))
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            val size = (44 * a.resources.displayMetrics.density).toInt()
            addView(icon(a, R.drawable.ic_save, R.string.cfg_save) { startSave(a, sp, keep) }, LinearLayout.LayoutParams(size, size))
            addView(icon(a, R.drawable.ic_load, R.string.cfg_load) { startLoad(a) }, LinearLayout.LayoutParams(size, size))
            addView(icon(a, R.drawable.ic_help, R.string.help) { showHelp(a) }, LinearLayout.LayoutParams(size, size))
        }

    /** Help text, then app name + version and the donation line; neutral button opens Liberapay. */
    fun showHelp(a: Activity) {
        val about = "\n\n${a.getString(R.string.app_name)} ${version(a)}\n${a.getString(R.string.donate_text)}"
        AlertDialog
            .Builder(a)
            .setTitle(R.string.help)
            .setMessage(TextUtils.concat(a.getText(R.string.help_text), about))
            .setPositiveButton(R.string.help_ok, null)
            .setNeutralButton(R.string.donate) { _, _ -> openDonate(a) }
            .show()
    }

    /** versionName from the package (no BuildConfig needed). */
    private fun version(a: Activity): String =
        try {
            val pm = a.packageManager
            val info =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(a.packageName, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(a.packageName, 0)
                }
            info.versionName.orEmpty()
        } catch (e: PackageManager.NameNotFoundException) {
            ""
        }

    private fun openDonate(a: Activity) {
        try {
            a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.getString(R.string.donate_url))))
        } catch (e: ActivityNotFoundException) {
            Log.w("AppShell", "donate", e)
            Toast.makeText(a, R.string.donate_url, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Handles the file dialogs; true if the result was ours. [keep] = device-specific keys that are
     * neither exported nor overwritten; [onLoaded] re-applies settings (services, UI).
     */
    fun onResult(
        a: Activity,
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
        sp: SharedPreferences,
        keep: (String) -> Boolean = { false },
        onLoaded: () -> Unit = { a.recreate() },
    ): Boolean {
        if (requestCode != REQ_SAVE && requestCode != REQ_LOAD) return false
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) return true
        rememberFile(a, uri)
        if (requestCode == REQ_SAVE) write(a, uri, sp, keep) else load(a, uri, sp, keep, onLoaded)
        return true
    }

    private fun icon(
        a: Activity,
        res: Int,
        desc: Int,
        onClick: () -> Unit,
    ) = ImageButton(a).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(a.getColor(R.color.md_primary))
        val tv = TypedValue()
        a.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        setBackgroundResource(tv.resourceId)
        contentDescription = a.getString(desc)
        tooltipText = a.getString(desc)
        setOnClickListener { onClick() }
    }

    /** Suggested file name: "<AppName>.json". */
    private fun fileName(a: Activity) = "${a.getString(R.string.app_name)}.json"

    /**
     * The picker can only create files ("AgendaGo(1).json" if the name exists), never overwrite.
     * So the last saved or loaded file is remembered and overwritten after asking; works for
     * local folders and cloud providers (Drive etc.) alike.
     */
    private fun startSave(
        a: Activity,
        sp: SharedPreferences,
        keep: (String) -> Boolean,
    ) {
        val last = lastFile(a)
        val name = last?.let { displayName(a, it) }
        if (last == null || name == null) {
            pickNewFile(a)
            return
        }
        AlertDialog
            .Builder(a)
            .setTitle(R.string.cfg_save)
            .setMessage(a.getString(R.string.cfg_overwrite, name))
            .setPositiveButton(R.string.cfg_overwrite_ok) { _, _ -> write(a, last, sp, keep) }
            .setNeutralButton(R.string.cfg_other_place) { _, _ -> pickNewFile(a) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickNewFile(a: Activity) {
        val i =
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(ConfigIO.MIME)
                .putExtra(Intent.EXTRA_TITLE, fileName(a))
        lastFile(a)?.let { i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        @Suppress("DEPRECATION")
        a.startActivityForResult(i, REQ_SAVE)
    }

    private fun startLoad(a: Activity) {
        val i =
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(ConfigIO.MIME)
        lastFile(a)?.let { i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        @Suppress("DEPRECATION")
        a.startActivityForResult(i, REQ_LOAD)
    }

    /** Keeps access to the file across restarts. Not part of the exported config. */
    private fun rememberFile(
        a: Activity,
        uri: Uri,
    ) {
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            a.contentResolver.takePersistableUriPermission(uri, rw)
        } catch (e: SecurityException) {
            // Some providers grant read only; then the next save falls back to the picker.
            Log.w("AppShell", "persist file", e)
        }
        shellPrefs(a).edit().putString(KEY_FILE, uri.toString()).apply()
    }

    private fun lastFile(a: Activity): Uri? = shellPrefs(a).getString(KEY_FILE, null)?.let(Uri::parse)

    private fun shellPrefs(a: Activity) = a.getSharedPreferences(SHELL_PREFS, Activity.MODE_PRIVATE)

    /** Name of a remembered file, or null if it is gone or no longer accessible. */
    private fun displayName(
        a: Activity,
        uri: Uri,
    ): String? =
        try {
            a.contentResolver
                .query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: SecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: UnsupportedOperationException) {
            null
        }

    private fun write(
        a: Activity,
        uri: Uri,
        sp: SharedPreferences,
        keep: (String) -> Boolean,
    ) {
        val bytes = ConfigIO.toJson(sp, a.getString(R.string.app_name), keep).toByteArray()
        try {
            // "wt" truncates; some cloud providers only know "w" (which replaces the content there).
            val out =
                try {
                    a.contentResolver.openOutputStream(uri, "wt")
                } catch (e: IllegalArgumentException) {
                    a.contentResolver.openOutputStream(uri, "w")
                } catch (e: java.io.FileNotFoundException) {
                    a.contentResolver.openOutputStream(uri, "w")
                } ?: throw IOException("no stream")
            out.use { it.write(bytes) }
            toast(a, R.string.cfg_saved)
        } catch (e: IOException) {
            Log.w("AppShell", "save config", e)
            toast(a, R.string.cfg_error)
        } catch (e: SecurityException) {
            Log.w("AppShell", "save config", e)
            toast(a, R.string.cfg_error)
        } catch (e: IllegalArgumentException) {
            Log.w("AppShell", "save config", e)
            toast(a, R.string.cfg_error)
        }
    }

    private fun load(
        a: Activity,
        uri: Uri,
        sp: SharedPreferences,
        keep: (String) -> Boolean,
        onLoaded: () -> Unit,
    ) {
        val json =
            try {
                a.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (e: IOException) {
                Log.w("AppShell", "load config", e)
                null
            } catch (e: SecurityException) {
                Log.w("AppShell", "load config", e)
                null
            }
        val names = listOf(a.getString(R.string.app_name)) + legacyNames
        if (json == null || names.none { ConfigIO.fromJson(sp, json, it, keep) }) {
            toast(a, R.string.cfg_invalid)
            return
        }
        toast(a, R.string.cfg_loaded)
        onLoaded()
    }

    private fun toast(
        a: Activity,
        res: Int,
    ) = Toast.makeText(a, res, Toast.LENGTH_SHORT).show()
}
