package com.regepower.mincalwidget.data

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.SystemClock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** A Tasks.org list (e.g. a Google Tasks list) for the list picker. */
data class TaskList(
    val id: Long,
    val title: String,
    val account: String,
    val color: Int,
)

/**
 * Open tasks via the Tasks.org content provider (Tasks.org 15.12+). Tasks.org syncs Google Tasks,
 * so the widget needs no Google sign-in of its own.
 * Spec: https://github.com/tasks/tasks/blob/main/CONTENT_PROVIDER.md
 */
object Tasks {
    const val PACKAGE = "org.tasks"
    const val PERMISSION = "org.tasks.permission.READ_TASKS"
    private const val BASE = "content://org.tasks.api/v0"
    val CHANGE_URI: Uri = Uri.parse(BASE)

    /** Colour when a list has none (Google Tasks lists have no colour). */
    const val DEFAULT_COLOR = 0xFF1E88E5.toInt()

    fun installed(context: Context): Boolean =
        try {
            context.packageManager.getPackageInfo(PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    fun permitted(context: Context) = installed(context) && context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun lists(context: Context): List<TaskList> {
        if (!permitted(context)) return emptyList()
        val accounts = mutableMapOf<Long, String>()
        query(context, "$BASE/accounts?limit=1000", arrayOf("_id", "name", "username"))?.use { c ->
            while (c.moveToNext()) {
                accounts[c.getLong(0)] = c.getString(2)?.takeIf { it.isNotBlank() } ?: c.getString(1).orEmpty()
            }
        }
        val result = mutableListOf<TaskList>()
        query(context, "$BASE/lists?limit=1000", arrayOf("_id", "title", "color", "account_id"))?.use { c ->
            while (c.moveToNext()) {
                result += TaskList(c.getLong(0), c.getString(1).orEmpty(), accounts[c.getLong(3)].orEmpty(), c.getInt(2))
            }
        }
        return result.sortedWith(compareBy({ it.account.lowercase() }, { it.title.lowercase() }))
    }

    /**
     * Open tasks due before [lastDay] (overdue ones included) and, if [withoutDate], tasks without
     * a due date. All-day dates come as local midnight; they are converted to UTC midnight like
     * calendar all-day events, so [Event.startDay] works for both.
     */
    fun upcoming(
        context: Context,
        prefs: WidgetPrefs,
        lastDay: LocalDate,
    ): List<Event> {
        if (!permitted(context)) return emptyList()
        val zone = ZoneId.systemDefault()
        val colors = lists(context).associate { it.id to it.color }
        val result = mutableListOf<Event>()
        val projection = arrayOf("_id", "title", "due_date", "due_all_day", "list_id", "parent_id")
        query(context, "$BASE/tasks?completed=0&sort=due&limit=1000", projection)?.use { c ->
            while (c.moveToNext()) {
                val listId = c.getLong(4)
                if (prefs.taskListIds.isNotEmpty() && listId !in prefs.taskListIds) continue
                val due = c.getLong(2)
                val allDay = c.getInt(3) != 0
                val begin: Long
                if (due == 0L) {
                    if (!prefs.tasksWithoutDate) continue
                    begin = 0L
                } else {
                    val day = Instant.ofEpochMilli(due).atZone(zone).toLocalDate()
                    if (day >= lastDay) continue
                    begin = if (allDay) day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() else due
                }
                val id = c.getLong(0)
                result +=
                    Event(
                        instanceId = TASK_ID_BASE - id,
                        eventId = 0,
                        begin = begin,
                        end = if (allDay && due != 0L) begin + DAY_MS else begin,
                        allDay = allDay || due == 0L,
                        title = c.getString(1).orEmpty(),
                        location = "",
                        color = colors[listId]?.takeIf { it != 0 } ?: DEFAULT_COLOR,
                        link = "$BASE/tasks/$id",
                        kind = Event.Kind.TASK,
                    )
            }
        }
        return result
    }

    /** First query after a Tasks.org cold start can return null (tasks/tasks#4706): retry once. */
    private fun query(
        context: Context,
        uri: String,
        projection: Array<String>,
    ): Cursor? {
        val parsed = Uri.parse(uri)
        repeat(2) { attempt ->
            try {
                context.contentResolver.query(parsed, projection, null, null, null)?.let { return it }
            } catch (e: IllegalArgumentException) {
                return null
            } catch (e: SecurityException) {
                return null
            }
            if (attempt == 0) SystemClock.sleep(RETRY_MS)
        }
        return null
    }

    private const val RETRY_MS = 400L
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Keeps task ids apart from calendar instance ids and birthday ids in the stable-id list. */
    private const val TASK_ID_BASE = -1_000_000_000_000L
}
