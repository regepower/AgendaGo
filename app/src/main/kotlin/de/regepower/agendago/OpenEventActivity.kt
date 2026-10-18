package de.regepower.agendago

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CalendarContract

/**
 * Invisible trampoline for widget taps. Opens the tapped event, or the calendar app at "now"
 * when no event is given. Needed because a widget list's PendingIntent template must be
 * mutable, and Android 14+ only allows mutable PendingIntents with an explicit target.
 */
class OpenEventActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val event = intent.data
        val launch = if (event?.scheme == "launch") packageManager.getLaunchIntentForPackage(event.schemeSpecificPart) else null
        val target =
            if (launch != null) {
                // Exchange tasks: open the chosen task app.
                launch
            } else if (event != null && event.authority != CalendarContract.AUTHORITY) {
                // Birthday rows link to the contact.
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
        // Calendar links go to the device's calendar app (APP_CALENDAR), not to whatever else
        // registered for calendar URIs (Outlook etc. grab Exchange events otherwise).
        if (target.data?.authority == CalendarContract.AUTHORITY) calendarPackage(target)?.let { target.setPackage(it) }
        try {
            startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            // No calendar app installed: nothing sensible to open.
        }
        finish()
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
