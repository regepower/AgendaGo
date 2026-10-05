package de.regepower.agendago.data

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.sqlite.SQLiteException
import android.net.Uri
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Open tasks from the OpenTasks provider (filled by DAVx⁵ and other CalDAV sync adapters).
 * Contract: https://github.com/dmfs/opentasks/blob/master/opentasks-contract/src/main/java/org/dmfs/tasks/contract/TaskContract.java
 */
object OpenTasks {
    const val PACKAGE = "org.dmfs.tasks"
    const val PERMISSION = "org.dmfs.permission.READ_TASKS"
    private const val AUTHORITY = "org.dmfs.tasks"
    val CHANGE_URI: Uri = Uri.parse("content://$AUTHORITY")
    private val LISTS: Uri = Uri.parse("content://$AUTHORITY/tasklists")
    private val TASKS: Uri = Uri.parse("content://$AUTHORITY/tasks")

    fun installed(context: Context) = TaskSources.installed(context, PACKAGE)

    fun permitted(context: Context) = installed(context) && context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun lists(context: Context): List<TaskList> {
        if (!permitted(context)) return emptyList()
        val result = mutableListOf<TaskList>()
        query(context, LISTS, arrayOf("_id", "list_name", "list_color", "account_name"), "visible=1")?.use { c ->
            while (c.moveToNext()) {
                result += TaskList(c.getLong(0), c.getString(1).orEmpty(), c.getString(3).orEmpty(), c.getInt(2))
            }
        }
        return result.sortedWith(compareBy({ it.account.lowercase() }, { it.title.lowercase() }))
    }

    /** Open (not completed or cancelled) tasks of visible lists. All-day due dates are UTC midnights. */
    fun upcoming(
        context: Context,
        prefs: WidgetPrefs,
        lastDay: LocalDate,
    ): List<Event> {
        if (!permitted(context)) return emptyList()
        val zone = ZoneId.systemDefault()
        val result = mutableListOf<Event>()
        val projection = arrayOf("_id", "title", "due", "is_allday", "list_id", "list_color")
        val ids = prefs.openTaskListIds.toList()
        val selection =
            buildString {
                append("is_closed=0 AND visible=1")
                if (ids.isNotEmpty()) append(" AND list_id IN (${ids.joinToString(",") { "?" }})")
            }
        query(context, TASKS, projection, selection, ids.map { it.toString() }.toTypedArray())?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val due = if (c.isNull(2)) 0L else c.getLong(2)
                val allDay = c.getInt(3) != 0
                val day =
                    if (due == 0L) {
                        null
                    } else {
                        Instant.ofEpochMilli(due).atZone(if (allDay) ZoneOffset.UTC else zone).toLocalDate()
                    }
                TaskSources
                    .row(
                        stableId = TaskSources.OPENTASKS_ID_BASE - id,
                        title = c.getString(1).orEmpty(),
                        due = due,
                        allDay = allDay,
                        day = day,
                        color = c.getInt(5).takeIf { it != 0 } ?: Tasks.DEFAULT_COLOR,
                        link = "$TASKS/$id",
                        prefs = prefs,
                        lastDay = lastDay,
                    )?.let { result += it }
            }
        }
        return result
    }

    private fun query(
        context: Context,
        uri: Uri,
        projection: Array<String>,
        selection: String,
        args: Array<String>? = null,
    ): Cursor? =
        try {
            context.contentResolver.query(uri, projection, selection, args?.takeIf { it.isNotEmpty() }, null)
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: SecurityException) {
            null
        } catch (e: SQLiteException) {
            null
        }
}
