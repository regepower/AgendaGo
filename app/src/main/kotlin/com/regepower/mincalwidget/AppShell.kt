package com.regepower.mincalwidget

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
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
 * help_text, cfg_save, cfg_load, cfg_saved, cfg_loaded, cfg_invalid, cfg_error; forward
 * onActivityResult to [onResult].
 */
object AppShell {
    const val REQ_SAVE = 7301
    const val REQ_LOAD = 7302

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
        if (requestCode == REQ_SAVE) save(a, uri, sp, keep) else load(a, uri, sp, keep, onLoaded)
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

    private fun startSave(a: Activity) {
        val i =
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(ConfigIO.MIME)
                .putExtra(Intent.EXTRA_TITLE, "${a.getString(R.string.app_name)}.json")
        @Suppress("DEPRECATION")
        a.startActivityForResult(i, REQ_SAVE)
    }

    private fun startLoad(a: Activity) {
        val i =
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(ConfigIO.MIME, "text/plain", "application/octet-stream"))
        @Suppress("DEPRECATION")
        a.startActivityForResult(i, REQ_LOAD)
    }

    private fun save(
        a: Activity,
        uri: Uri,
        sp: SharedPreferences,
        keep: (String) -> Boolean,
    ) {
        try {
            a.contentResolver.openOutputStream(uri, "wt")?.use {
                it.write(ConfigIO.toJson(sp, a.getString(R.string.app_name), keep).toByteArray())
            }
            toast(a, R.string.cfg_saved)
        } catch (e: IOException) {
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
