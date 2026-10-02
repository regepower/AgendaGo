package com.regepower.mincalwidget.data

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Instances
import com.regepower.mincalwidget.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

data class CalendarInfo(
    val id: Long,
    val name: String,
    val account: String,
    val color: Int,
) {
    val label: String get() = if (account.isBlank() || account == name) name else "$name ($account)"
}

/** One occurrence (recurrences are already expanded by the Instances table). */
data class Event(
    val instanceId: Long,
    val eventId: Long,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val title: String,
    val location: String,
    val color: Int,
) {
    /**
     * Calendar day the event starts on. All-day events are stored as UTC midnights, so they
     * must be read in UTC, otherwise they shift by a day depending on the time zone.
     */
    fun startDay(zone: ZoneId): LocalDate = Instant.ofEpochMilli(begin).atZone(if (allDay) ZoneOffset.UTC else zone).toLocalDate()

    /** Day after the event (exclusive end) for all-day events. */
    fun endDayExclusive(): LocalDate = Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate()

    /** Last calendar day the event covers (an end at midnight belongs to the day before). */
    fun lastDay(zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(maxOf(end - 1, begin)).atZone(if (allDay) ZoneOffset.UTC else zone).toLocalDate()
}

class EventRepository(
    private val context: Context,
) {
    fun calendars(): List<CalendarInfo> {
        val projection =
            arrayOf(
                Calendars._ID,
                Calendars.CALENDAR_DISPLAY_NAME,
                Calendars.ACCOUNT_NAME,
                Calendars.CALENDAR_COLOR,
            )
        val result = mutableListOf<CalendarInfo>()
        context.contentResolver
            .query(Calendars.CONTENT_URI, projection, null, null, null)
            ?.use { c ->
                while (c.moveToNext()) {
                    result += CalendarInfo(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getInt(3))
                }
            }
        return result.sortedWith(compareBy({ it.account.lowercase() }, { it.name.lowercase() }))
    }

    /** Events that are not over yet, within [WidgetPrefs.maxDays] days, sorted, capped. */
    fun upcoming(
        prefs: WidgetPrefs,
        now: Long = System.currentTimeMillis(),
    ): List<Event> {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val lastDay = today.plusDays(prefs.maxDays.toLong())
        val rangeEnd = lastDay.atStartOfDay(zone).toInstant().toEpochMilli()

        // Start one day early: today's all-day events are UTC-based and may lie "before now".
        val uri =
            Instances.CONTENT_URI
                .buildUpon()
                .also {
                    ContentUris.appendId(it, now - DAY_MS)
                    ContentUris.appendId(it, rangeEnd + DAY_MS)
                }.build()
        val projection =
            arrayOf(
                Instances._ID,
                Instances.EVENT_ID,
                Instances.BEGIN,
                Instances.END,
                Instances.ALL_DAY,
                Instances.TITLE,
                Instances.EVENT_LOCATION,
                Instances.DISPLAY_COLOR,
            )
        val ids = prefs.calendarIds.toList()
        val selection =
            buildString {
                // "All" = calendars visible in the calendar app; an explicit choice wins over visibility.
                if (ids.isEmpty()) {
                    append("${Instances.VISIBLE}=1")
                } else {
                    append("${Instances.CALENDAR_ID} IN (${ids.joinToString(",") { "?" }})")
                }
            }
        val args = ids.map { it.toString() }.toTypedArray().takeIf { it.isNotEmpty() }
        val noTitle = context.getString(R.string.no_title)

        val events = mutableListOf<Event>()
        context.contentResolver.query(uri, projection, selection, args, null)?.use { c ->
            while (c.moveToNext()) {
                events +=
                    Event(
                        instanceId = c.getLong(0),
                        eventId = c.getLong(1),
                        begin = c.getLong(2),
                        end = c.getLong(3),
                        allDay = c.getInt(4) != 0,
                        title = c.getString(5)?.takeIf { it.isNotBlank() } ?: noTitle,
                        location = c.getString(6).orEmpty().trim(),
                        color = c.getInt(7),
                    )
            }
        }
        return events
            .filter { e ->
                val notOver = if (e.allDay) e.endDayExclusive() > today else e.end > now || e.begin >= now
                notOver && e.startDay(zone) < lastDay
            }.sortedWith(compareBy({ sortKey(it, zone) }, { !it.allDay }, { it.title.lowercase() }))
            .take(prefs.maxEvents)
    }

    /** All-day events sort at local midnight of their day, i.e. before that day's timed events. */
    private fun sortKey(
        e: Event,
        zone: ZoneId,
    ): Long =
        if (e.allDay) {
            e
                .startDay(zone)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()
        } else {
            e.begin
        }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
