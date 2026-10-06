package de.regepower.agendago.data

import android.content.Context
import android.content.pm.PackageManager
import java.time.LocalDate
import java.time.ZoneOffset

/** Combines all enabled task sources: Tasks.org, OpenTasks, Exchange and Google Tasks (caches). */
object TaskSources {
    /** Stable-id ranges per source, apart from calendar instances and birthdays. */
    const val TASKS_ORG_ID_BASE = -1_000_000_000_000L
    const val OPENTASKS_ID_BASE = -2_000_000_000_000L
    const val EWS_ID_BASE = -3_000_000_000_000L
    const val GTASKS_ID_BASE = -4_000_000_000_000L
    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun upcoming(
        context: Context,
        prefs: WidgetPrefs,
        lastDay: LocalDate,
    ): List<Event> {
        val result = mutableListOf<Event>()
        if (prefs.tasksOrg) result += Tasks.upcoming(context, prefs, lastDay)
        if (prefs.openTasks) result += OpenTasks.upcoming(context, prefs, lastDay)
        if (prefs.ews) result += Ews.upcoming(context, prefs, lastDay)
        if (prefs.gtasks) result += GTasks.upcoming(context, prefs, lastDay)
        return result
    }

    /**
     * One widget row for a task, or null if it is outside the window. [day] is the due day
     * (null = no due date); all-day rows are stored as UTC midnight like calendar all-day events.
     */
    fun row(
        stableId: Long,
        title: String,
        due: Long,
        allDay: Boolean,
        day: LocalDate?,
        color: Int,
        link: String?,
        prefs: WidgetPrefs,
        lastDay: LocalDate,
    ): Event? {
        val begin: Long
        if (day == null) {
            if (!prefs.tasksWithoutDate) return null
            begin = 0L
        } else {
            if (day >= lastDay) return null
            begin = if (allDay) day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() else due
        }
        return Event(
            instanceId = stableId,
            eventId = 0,
            begin = begin,
            end = if (allDay && day != null) begin + DAY_MS else begin,
            allDay = allDay || day == null,
            title = title,
            location = "",
            color = color,
            link = link,
            kind = Event.Kind.TASK,
        )
    }

    fun installed(
        context: Context,
        pkg: String,
    ): Boolean =
        try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
}
