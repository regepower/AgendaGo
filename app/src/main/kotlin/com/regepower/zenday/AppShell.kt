package com.regepower.zenday

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.provider.DocumentsContract
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
 * help_text, cfg_save, cfg_load, cfg_saved, cfg_loaded, cfg_invalid, cfg_error, cfg_overwrite,
 * cfg_overwrite_ok, cfg_not_found; forward onActivityResult to [onResult].
 * Config file: always "<AppName>.json" in a folder the user picks (save asks before overwriting).
 */
object AppShell {
    const val REQ_SAVE = 7301
    const val REQ_LOAD = 7302
    private const val SHELL_PREFS = "appshell"
    private const val KEY_FOLDER = "folder"

    fun header(a: Activity): LinearLayout =
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
            addView(icon(a, R.drawable.ic_save, R.string.cfg_save) { startSave(a) }, LinearLayout.LayoutParams(size, size))
            addView(icon(a, R.drawable.ic_load, R.string.cfg_load) { startLoad(a) }, LinearLayout.LayoutParams(size, size))
            addView(icon(a, R.drawable.ic_help, R.string.help) { showHelp(a) }, LinearLayout.LayoutParams(size, size))
        }

    fun showHelp(a: Activity) {
        AlertDialog
            .Builder(a)
            .setTitle(R.string.help)
            .setMessage(a.getText(R.string.help_text))
            .setPositiveButton(R.string.help_ok, null)
            .show()
    }

    /**
     * Handles the folder dialogs; true if the result was ours. [keep] = device-specific keys that are
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
        val tree = data?.data
        if (resultCode != Activity.RESULT_OK || tree == null) return true
        rememberFolder(a, tree)
        if (requestCode == REQ_SAVE) save(a, tree, sp, keep) else load(a, tree, sp, keep, onLoaded)
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

    /** Fixed file name: "<AppName>.json", always in the folder the user picks. */
    private fun fileName(a: Activity) = "${a.getString(R.string.app_name)}.json"

    // Save and load both pick a folder: the file name is ours, so there are no copies like "ZenDay (1).json".
    private fun startSave(a: Activity) = pickFolder(a, REQ_SAVE)

    private fun startLoad(a: Activity) = pickFolder(a, REQ_LOAD)

    private fun pickFolder(
        a: Activity,
        request: Int,
    ) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        lastFolder(a)?.let { i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        @Suppress("DEPRECATION")
        a.startActivityForResult(i, request)
    }

    /** Keeps access to the folder, so the picker opens there next time. Not part of the exported config. */
    private fun rememberFolder(
        a: Activity,
        tree: Uri,
    ) {
        try {
            a.contentResolver.takePersistableUriPermission(
                tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            Log.w("AppShell", "persist folder", e)
        }
        shellPrefs(a).edit().putString(KEY_FOLDER, tree.toString()).apply()
    }

    private fun lastFolder(a: Activity): Uri? = shellPrefs(a).getString(KEY_FOLDER, null)?.let(Uri::parse)

    private fun shellPrefs(a: Activity) = a.getSharedPreferences(SHELL_PREFS, Activity.MODE_PRIVATE)

    /** Document uri of [name] directly in [tree] (case-insensitive, like the file systems), or null. */
    private fun findFile(
        a: Activity,
        tree: Uri,
        name: String,
    ): Uri? {
        val parent = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return try {
            a.contentResolver.query(children, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1).equals(name, ignoreCase = true)) {
                        return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                    }
                }
                null
            }
        } catch (e: IllegalArgumentException) {
            Log.w("AppShell", "list folder", e)
            null
        } catch (e: SecurityException) {
            Log.w("AppShell", "list folder", e)
            null
        }
    }

    private fun save(
        a: Activity,
        tree: Uri,
        sp: SharedPreferences,
        keep: (String) -> Boolean,
    ) {
        val name = fileName(a)
        val existing = findFile(a, tree, name)
        if (existing == null) {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val created =
                try {
                    DocumentsContract.createDocument(a.contentResolver, parent, ConfigIO.MIME, name)
                } catch (e: IOException) {
                    Log.w("AppShell", "create config", e)
                    null
                } catch (e: IllegalArgumentException) {
                    Log.w("AppShell", "create config", e)
                    null
                }
            if (created == null) toast(a, R.string.cfg_error) else write(a, created, sp, keep)
            return
        }
        // Overwrite only after asking; "Cancel" saves nothing.
        AlertDialog
            .Builder(a)
            .setTitle(R.string.cfg_save)
            .setMessage(a.getString(R.string.cfg_overwrite, name))
            .setPositiveButton(R.string.cfg_overwrite_ok) { _, _ -> write(a, existing, sp, keep) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun write(
        a: Activity,
        uri: Uri,
        sp: SharedPreferences,
        keep: (String) -> Boolean,
    ) {
        try {
            val out = a.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("no stream")
            out.use { it.write(ConfigIO.toJson(sp, a.getString(R.string.app_name), keep).toByteArray()) }
            toast(a, R.string.cfg_saved)
        } catch (e: IOException) {
            Log.w("AppShell", "save config", e)
            toast(a, R.string.cfg_error)
        } catch (e: SecurityException) {
            Log.w("AppShell", "save config", e)
            toast(a, R.string.cfg_error)
        }
    }

    private fun load(
        a: Activity,
        tree: Uri,
        sp: SharedPreferences,
        keep: (String) -> Boolean,
        onLoaded: () -> Unit,
    ) {
        val name = fileName(a)
        val uri = findFile(a, tree, name)
        if (uri == null) {
            Toast.makeText(a, a.getString(R.string.cfg_not_found, name), Toast.LENGTH_LONG).show()
            return
        }
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
        if (json == null || !ConfigIO.fromJson(sp, json, a.getString(R.string.app_name), keep)) {
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
