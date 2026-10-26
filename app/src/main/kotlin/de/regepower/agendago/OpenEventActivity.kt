package de.regepower.agendago

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CalendarContract
import de.regepower.agendago.data.Openers
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Invisible trampoline for widget taps. Opens the tapped event, or the calendar app at "now"
 * when no event is given. Needed because a widget list's PendingIntent template must be
 * mutable, and Android 14+ only allows mutable PendingIntents with an explicit target.
 */
class OpenEventActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val event = intent.data
        val target =
            if (event != null && event.authority != CalendarContract.AUTHORITY) {
                // Birthdays link to the contact, tasks to their app or web page.
                Intent(Intent.ACTION_VIEW, event)
            } else if (event != null) {
                Intent(Intent.ACTION_VIEW, event)
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, intent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 0L))
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, intent.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, 0L))
                    .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, intent.getBooleanExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false))
            } else {
                val time =
                    CalendarContract.CONTENT_URI
                        .buildUpon()
                        .appendPath("time")
                        .also {
                            ContentUris.appendId(it, System.currentTimeMillis())
                        }.build()
                Intent(Intent.ACTION_VIEW, time)
            }
        // The app the user picked for this source (settings "Linked apps"), if any.
        val source = intent.getStringExtra(Openers.EXTRA_SOURCE)?.let { key -> Openers.Source.entries.firstOrNull { it.key == key } }
        val chosen = source?.let { dayInCalendarApp(it) ?: Openers.chosenIntent(this, it, target) }
        // Otherwise calendar links go to the device's calendar app (APP_CALENDAR), not to whatever
        // else registered for calendar URIs (Outlook etc. grab Exchange events otherwise).
        if (chosen == null && target.data?.authority == CalendarContract.AUTHORITY) calendarPackage(target)?.let { target.setPackage(it) }
        try {
            startActivity((chosen ?: target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            // No calendar app installed: nothing sensible to open.
        }
        finish()
    }

    /**
     * Birthdays and tasks with a calendar app chosen: open that app at the entry's day
     * (content://com.android.calendar/time/<millis>, the standard "show the calendar at" intent).
     * Undated tasks open today. Null = not a calendar app (or a calendar event: it opens itself).
     */
    private fun dayInCalendarApp(source: Openers.Source): Intent? {
        if (source == Openers.Source.CALENDAR) return null
        val pkg = Openers.get(this, source) ?: return null
        val begin = intent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 0L)
        val millis =
            when {
                begin == 0L -> System.currentTimeMillis()
                // All-day rows are stored as UTC midnight: noon of that date in the local zone.
                intent.getBooleanExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false) ->
                    Instant
                        .ofEpochMilli(begin)
                        .atZone(ZoneOffset.UTC)
                        .toLocalDate()
                        .atTime(LocalTime.NOON)
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                else -> begin
            }
        val time =
            CalendarContract.CONTENT_URI
                .buildUpon()
                .appendPath("time")
                .also { ContentUris.appendId(it, millis) }
                .build()
        val view = Intent(Intent.ACTION_VIEW, time).setPackage(pkg)
        return view.takeIf { packageManager.queryIntentActivities(it, 0).isNotEmpty() }
    }

    /** Package of the default calendar app, if it can show [view]; null = let Android choose. */
    private fun calendarPackage(view: Intent): String? {
        val selector = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)
        val pkg = packageManager.resolveActivity(selector, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        // "android" = the chooser itself (several calendar apps, no default).
        if (pkg == null || pkg == "android") return null
        val handles = packageManager.queryIntentActivities(Intent(view).setPackage(pkg), 0).isNotEmpty()
        return pkg.takeIf { handles }
    }
}
