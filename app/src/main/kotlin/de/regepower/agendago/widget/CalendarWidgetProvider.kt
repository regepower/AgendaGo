package de.regepower.agendago.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.provider.ContactsContract
import de.regepower.agendago.data.Birthdays
import de.regepower.agendago.data.CalendarColors
import de.regepower.agendago.data.Ews
import de.regepower.agendago.data.GTasks
import de.regepower.agendago.data.OpenTasks
import de.regepower.agendago.data.Tasks
import de.regepower.agendago.data.WidgetPrefs
import java.time.LocalDate
import java.time.ZoneId

class CalendarWidgetProvider : AppWidgetProvider() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_LOCALE_CHANGED,
            -> refreshAsync(context)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        refreshAsync(context)
    }

    override fun onDeleted(
        context: Context,
        appWidgetIds: IntArray,
    ) {
        WidgetPrefs.delete(context, appWidgetIds)
    }

    override fun onDisabled(context: Context) {
        RefreshScheduler.cancel(context)
    }

    private fun refreshAsync(context: Context) {
        val pending = goAsync()
        Thread {
            try {
                WidgetUpdater.updateAll(context.applicationContext)
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_REFRESH = "de.regepower.agendago.REFRESH"
    }
}

/** Redraws all widgets and arms the next refresh triggers. Call from a background thread. */
object WidgetUpdater {
    fun updateAll(context: Context) {
        // Device-only calendar colours that a sync adapter reset (Exchange, local).
        CalendarColors.restore(context)
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, CalendarWidgetProvider::class.java))
        if (ids.isEmpty()) {
            RefreshScheduler.cancel(context)
            NetSync.schedule(context)
            return
        }
        NetSync.schedule(context)
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        // "Today"/"Tmrw" labels change at midnight; finished events must disappear at their end.
        var next =
            LocalDate
                .now(zone)
                .plusDays(1)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli()
        for (id in ids) {
            for (event in WidgetRenderer.render(context, manager, id)) {
                if (!event.allDay && event.end in (now + 1) until next) next = event.end
            }
        }
        RefreshScheduler.setAlarm(context, next + REFRESH_SLACK_MS)
        RefreshScheduler.armJob(context, force = false)
    }

    private const val REFRESH_SLACK_MS = 5_000L
}

/**
 * Two triggers, no polling:
 * - an inexact, non-wakeup alarm at the next midnight / next event end,
 * - a JobScheduler content trigger that fires when calendar data (or contacts) change.
 */
object RefreshScheduler {
    private const val JOB_ID = 1

    fun setAlarm(
        context: Context,
        atMillis: Long,
    ) {
        context.getSystemService(AlarmManager::class.java)?.set(AlarmManager.RTC, atMillis, alarmIntent(context))
    }

    /** Re-arms the change trigger; also when contacts access was granted since the last arming. */
    fun armJob(
        context: Context,
        force: Boolean,
    ) {
        val jobs = context.getSystemService(JobScheduler::class.java) ?: return
        val uris = mutableListOf(CalendarContract.CONTENT_URI)
        // Birthdays: redraw when contacts change (only if access was granted).
        if (Birthdays.permitted(context)) uris += ContactsContract.Contacts.CONTENT_URI
        // Tasks: Tasks.org notifies below content://org.tasks.api/v0 on every task change.
        if (Tasks.permitted(context)) uris += Tasks.CHANGE_URI
        // OpenTasks notifies below content://org.dmfs.tasks (DAVx⁵ sync, edits).
        if (OpenTasks.permitted(context)) uris += OpenTasks.CHANGE_URI
        val pending = jobs.getPendingJob(JOB_ID)
        if (!force && pending != null && pending.triggerContentUris?.size == uris.size) return
        val builder = JobInfo.Builder(JOB_ID, ComponentName(context, CalendarChangeJob::class.java))
        for (uri in uris) {
            builder.addTriggerContentUri(JobInfo.TriggerContentUri(uri, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
        }
        jobs.schedule(builder.setTriggerContentUpdateDelay(1_000).setTriggerContentMaxDelay(10_000).build())
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(context))
        context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        NetSync.schedule(context)
    }

    private fun alarmIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, CalendarWidgetProvider::class.java).setAction(CalendarWidgetProvider.ACTION_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}

/** Runs after calendar changes. A content-trigger job fires once, so it re-arms itself. */
class CalendarChangeJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try {
                WidgetUpdater.updateAll(applicationContext)
            } finally {
                jobFinished(params, false)
                // After jobFinished, so re-scheduling the same id does not stop this job.
                if (hasWidgets(applicationContext)) RefreshScheduler.armJob(applicationContext, force = true)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false

    private fun hasWidgets(context: Context) =
        AppWidgetManager
            .getInstance(context)
            .getAppWidgetIds(ComponentName(context, CalendarWidgetProvider::class.java))
            .isNotEmpty()
}

/**
 * Fetches network task sources (Exchange via EWS, Google Tasks) every 30 minutes while a network
 * is available, then redraws the widgets. Neither pushes to a third-party app, so this is the
 * only polling in the app, and only while such a source is switched on.
 */
class NetSyncJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try {
                NetSync.syncNow(applicationContext)
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}

object NetSync {
    private const val JOB_ID = 2
    private const val PERIOD_MS = 30L * 60 * 1000

    /** Which network sources any placed widget uses (and that are set up). */
    private fun needed(context: Context): Pair<Boolean, Boolean> {
        val manager = AppWidgetManager.getInstance(context)
        val all =
            manager
                .getAppWidgetIds(ComponentName(context, CalendarWidgetProvider::class.java))
                .map { WidgetPrefs.load(context, it) }
                .filter { it.tasks }
        val ews = all.any { it.ews } && Ews.configured(context)
        val gtasks = all.any { it.gtasks } && GTasks.configured(context)
        return ews to gtasks
    }

    /** Fetches the used sources and redraws. Network: call from a background thread. */
    fun syncNow(
        context: Context,
        force: Boolean = false,
    ) {
        val (ews, gtasks) = needed(context)
        if ((ews || force) && Ews.configured(context)) Ews.sync(context)
        if ((gtasks || force) && GTasks.configured(context)) GTasks.sync(context)
        WidgetUpdater.updateAll(context)
    }

    /** Schedules (once) or cancels the periodic fetch, depending on the widgets' sources. */
    fun schedule(context: Context) {
        val jobs = context.getSystemService(JobScheduler::class.java) ?: return
        val (ews, gtasks) = needed(context)
        if (!ews && !gtasks) {
            if (jobs.getPendingJob(JOB_ID) != null) jobs.cancel(JOB_ID)
            return
        }
        val pending = jobs.getPendingJob(JOB_ID)
        if (pending != null && pending.service.className == NetSyncJob::class.java.name) return
        jobs.schedule(
            JobInfo
                .Builder(JOB_ID, ComponentName(context, NetSyncJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(PERIOD_MS)
                .setPersisted(true)
                .build(),
        )
    }
}
