package com.regepower.mincalwidget.data

import android.content.Context
import android.content.SharedPreferences
import com.regepower.mincalwidget.R

/** System font families offered for the widget; each has its own row layout. */
enum class FontStyle(
    val family: String,
    val label: Int,
    val rowLayout: Int,
) {
    SANS("sans-serif", R.string.font_sans, R.layout.row_sans),
    SERIF("serif", R.string.font_serif, R.layout.row_serif),
    MONO("monospace", R.string.font_mono, R.layout.row_mono),
    CURSIVE("cursive", R.string.font_cursive, R.layout.row_cursive),
}

/**
 * Settings of one widget. Stored per widget id; the last applied settings also become the
 * defaults for widgets placed later.
 */
data class WidgetPrefs(
    /** Empty = all visible calendars. */
    val calendarIds: Set<Long> = emptySet(),
    val maxEvents: Int = 15,
    val maxDays: Int = 14,
    val showLocation: Boolean = true,
    val transparent: Boolean = false,
    val invert: Boolean = false,
    val font: FontStyle = FontStyle.SANS,
    val fontSizeSp: Int = 14,
    val dateWidthDp: Int = 52,
    val birthdays: Boolean = false,
    val tasks: Boolean = false,
    /** Empty = all task lists. */
    val taskListIds: Set<Long> = emptySet(),
    val tasksWithoutDate: Boolean = false,
) {
    companion object {
        val EVENTS = 1..50
        val DAYS = 1..90
        val FONT_SIZE = 10..24
        val DATE_WIDTH = 30..160

        private const val FILE = "widgets"
        private const val DEFAULT = "d."

        /** The whole settings store (all widgets + defaults), exported by the header's save/load. */
        fun store(context: Context): SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

        /** Calendar and task list IDs differ per phone: neither exported nor overwritten. */
        fun isDeviceKey(key: String) = key.endsWith(".cals") || key.endsWith(".tasklists")

        private fun prefix(widgetId: Int?) = if (widgetId == null) DEFAULT else "w$widgetId."

        fun load(
            context: Context,
            widgetId: Int?,
        ): WidgetPrefs {
            val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            val own = prefix(widgetId)
            val p = if (sp.contains(own + "events")) own else DEFAULT
            val d = WidgetPrefs()
            return WidgetPrefs(
                calendarIds =
                    sp.getStringSet(p + "cals", null)?.mapNotNull { it.toLongOrNull() }?.toSet()
                        ?: d.calendarIds,
                maxEvents = sp.getInt(p + "events", d.maxEvents).coerceIn(EVENTS),
                maxDays = sp.getInt(p + "days", d.maxDays).coerceIn(DAYS),
                showLocation = sp.getBoolean(p + "location", d.showLocation),
                transparent = sp.getBoolean(p + "transparent", d.transparent),
                invert = sp.getBoolean(p + "invert", d.invert),
                font = FontStyle.entries.firstOrNull { it.name == sp.getString(p + "font", null) } ?: d.font,
                fontSizeSp = sp.getInt(p + "size", d.fontSizeSp).coerceIn(FONT_SIZE),
                dateWidthDp = sp.getInt(p + "daywidth", d.dateWidthDp).coerceIn(DATE_WIDTH),
                birthdays = sp.getBoolean(p + "birthdays", d.birthdays),
                tasks = sp.getBoolean(p + "tasks", d.tasks),
                taskListIds =
                    sp.getStringSet(p + "tasklists", null)?.mapNotNull { it.toLongOrNull() }?.toSet()
                        ?: d.taskListIds,
                tasksWithoutDate = sp.getBoolean(p + "tasksnodate", d.tasksWithoutDate),
            )
        }

        /** Saves for [widgetId] (if any) and as default for new widgets. */
        fun save(
            context: Context,
            widgetId: Int?,
            prefs: WidgetPrefs,
        ) {
            val editor = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            for (p in setOfNotNull(prefix(widgetId), DEFAULT)) {
                editor
                    .putStringSet(p + "cals", prefs.calendarIds.map { it.toString() }.toSet())
                    .putInt(p + "events", prefs.maxEvents)
                    .putInt(p + "days", prefs.maxDays)
                    .putBoolean(p + "location", prefs.showLocation)
                    .putBoolean(p + "transparent", prefs.transparent)
                    .putBoolean(p + "invert", prefs.invert)
                    .putString(p + "font", prefs.font.name)
                    .putInt(p + "size", prefs.fontSizeSp)
                    .putInt(p + "daywidth", prefs.dateWidthDp)
                    .putBoolean(p + "birthdays", prefs.birthdays)
                    .putBoolean(p + "tasks", prefs.tasks)
                    .putStringSet(p + "tasklists", prefs.taskListIds.map { it.toString() }.toSet())
                    .putBoolean(p + "tasksnodate", prefs.tasksWithoutDate)
            }
            editor.apply()
        }

        fun delete(
            context: Context,
            widgetIds: IntArray,
        ) {
            val sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            val editor = sp.edit()
            for (id in widgetIds) {
                val p = prefix(id)
                sp.all.keys
                    .filter { it.startsWith(p) }
                    .forEach { editor.remove(it) }
            }
            editor.apply()
        }
    }
}
