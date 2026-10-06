package de.regepower.agendago.data

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Colors

/** One selectable colour: [key] is the account's palette key, null for a free colour. */
data class PaletteColor(
    val key: String?,
    val color: Int,
)

/**
 * Changes calendar colours in the system calendar database, i.e. for every calendar app.
 *
 * - Google: only the account's own palette (Colors table, written by the Google sync adapter).
 *   Writing the palette key marks the calendar dirty, so the sync adapter can send it to Google.
 * - Exchange/local: ActiveSync has no calendar colour, so the colour is device-only. The app
 *   remembers it and puts it back when a sync adapter overwrites it ([restore]).
 */
object CalendarColors {
    const val GOOGLE = "com.google"

    /** Fallback palette (Google calendar colours) for accounts without their own palette. */
    val DEFAULT_PALETTE =
        listOf(
            0xFFAC725E,
            0xFFD06B64,
            0xFFF83A22,
            0xFFFA573C,
            0xFFFF7537,
            0xFFFFAD46,
            0xFF42D692,
            0xFF16A765,
            0xFF7BD148,
            0xFFB3DC6C,
            0xFFFBE983,
            0xFFFAD165,
            0xFF92E1C0,
            0xFF9FE1E7,
            0xFF9FC6E7,
            0xFF4986E7,
            0xFF9A9CFF,
            0xFFB99AFF,
            0xFFC2C2C2,
            0xFFCABDBF,
            0xFFCCA6AC,
            0xFFF691B2,
            0xFFCD74E6,
            0xFFA47AE2,
        ).map { it.toInt() }

    private const val FILE = "colors"
    private const val RESTORE = "restore"
    private const val PREFIX = "c."

    fun canWrite(context: Context) = context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Palette of the calendar's account; empty when the account brings none. */
    fun palette(
        context: Context,
        calendar: CalendarInfo,
    ): List<PaletteColor> {
        val result = mutableListOf<PaletteColor>()
        context.contentResolver
            .query(
                Colors.CONTENT_URI,
                arrayOf(Colors.COLOR_KEY, Colors.COLOR),
                "${Colors.ACCOUNT_NAME}=? AND ${Colors.ACCOUNT_TYPE}=? AND ${Colors.COLOR_TYPE}=?",
                arrayOf(calendar.account, calendar.accountType, Colors.TYPE_CALENDAR.toString()),
                null,
            )?.use { c ->
                while (c.moveToNext()) result += PaletteColor(c.getString(0), c.getInt(1))
            }
        // Palette keys are numbers at Google ("1".."24"): keep their order.
        return result.sortedWith(compareBy({ it.key?.toIntOrNull() ?: Int.MAX_VALUE }, { it.key }))
    }

    /** Free colours are only offered where the server does not own the colour. */
    fun allowsFreeColor(calendar: CalendarInfo) = calendar.accountType != GOOGLE

    /** Applies [choice]; returns false if the provider refused it. */
    fun apply(
        context: Context,
        calendar: CalendarInfo,
        choice: PaletteColor,
    ): Boolean {
        val values = ContentValues()
        if (choice.key != null) {
            // The provider's trigger copies the palette colour into CALENDAR_COLOR.
            values.put(Calendars.CALENDAR_COLOR_KEY, choice.key)
        } else {
            values.putNull(Calendars.CALENDAR_COLOR_KEY)
            values.put(Calendars.CALENDAR_COLOR, choice.color)
        }
        val ok = update(context, calendar.id, values)
        if (ok) remember(context, calendar, choice)
        return ok
    }

    fun restoreEnabled(context: Context) = prefs(context).getBoolean(RESTORE, true)

    fun setRestoreEnabled(
        context: Context,
        enabled: Boolean,
    ) = prefs(context).edit().putBoolean(RESTORE, enabled).apply()

    /** Puts remembered device-only colours back if a sync adapter replaced them. */
    fun restore(context: Context) {
        if (!restoreEnabled(context) || !canWrite(context)) return
        val wanted =
            prefs(context).all.mapNotNull { (k, v) ->
                val id = k.removePrefix(PREFIX).takeIf { k.startsWith(PREFIX) }?.toLongOrNull()
                if (id != null && v is Int) id to v else null
            }
        if (wanted.isEmpty()) return
        val current = mutableMapOf<Long, Int>()
        context.contentResolver
            .query(Calendars.CONTENT_URI, arrayOf(Calendars._ID, Calendars.CALENDAR_COLOR), null, null, null)
            ?.use { c -> while (c.moveToNext()) current[c.getLong(0)] = c.getInt(1) }
        val editor = prefs(context).edit()
        for ((id, color) in wanted) {
            val now = current[id]
            when {
                now == null -> editor.remove(PREFIX + id) // calendar is gone
                now != color ->
                    update(
                        context,
                        id,
                        ContentValues().apply {
                            putNull(Calendars.CALENDAR_COLOR_KEY)
                            put(Calendars.CALENDAR_COLOR, color)
                        },
                    )
            }
        }
        editor.apply()
    }

    /** Only device-only colours are remembered; Google palette colours belong to the server. */
    private fun remember(
        context: Context,
        calendar: CalendarInfo,
        choice: PaletteColor,
    ) {
        val editor = prefs(context).edit()
        if (calendar.accountType == GOOGLE) {
            editor.remove(PREFIX + calendar.id)
        } else {
            editor.putInt(PREFIX + calendar.id, choice.color)
        }
        editor.apply()
    }

    private fun update(
        context: Context,
        id: Long,
        values: ContentValues,
    ): Boolean =
        try {
            context.contentResolver.update(ContentUris.withAppendedId(Calendars.CONTENT_URI, id), values, null, null) > 0
        } catch (e: IllegalArgumentException) {
            false // e.g. palette key unknown to the provider
        } catch (e: SecurityException) {
            false
        }

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
