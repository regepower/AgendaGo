package com.regepower.mincalwidget

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Intent
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
        val target =
            if (event != null && event.authority != CalendarContract.AUTHORITY) {
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
        try {
            startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            // No calendar app installed: nothing sensible to open.
        }
        finish()
    }
}
