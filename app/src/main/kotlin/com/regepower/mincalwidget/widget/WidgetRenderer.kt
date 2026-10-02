package com.regepower.mincalwidget.widget

import android.Manifest
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.TypedValue
import android.widget.RemoteViews
import com.regepower.mincalwidget.OpenEventActivity
import com.regepower.mincalwidget.R
import com.regepower.mincalwidget.SettingsActivity
import com.regepower.mincalwidget.data.DateLabels
import com.regepower.mincalwidget.data.Event
import com.regepower.mincalwidget.data.EventRepository
import com.regepower.mincalwidget.data.FontStyle
import com.regepower.mincalwidget.data.WidgetPrefs

/** Builds the RemoteViews of one widget. Must not run on the main thread (calendar query). */
object WidgetRenderer {
    /** Colour resources for the current prefs; resolved by the launcher, so day/night still works. */
    data class Palette(
        val bg: Int,
        val text: Int,
        val text2: Int,
    )

    fun palette(prefs: WidgetPrefs) =
        when {
            prefs.invert -> Palette(R.drawable.widget_bg_inv, R.color.w_text_inv, R.color.w_text2_inv)
            else -> Palette(R.drawable.widget_bg, R.color.w_text, R.color.w_text2)
        }.let { if (prefs.transparent) it.copy(bg = R.drawable.widget_bg_none) else it }

    fun hasPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Renders and pushes one widget; returns the shown events (for the next refresh time). */
    fun render(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
    ): List<Event> {
        val prefs = WidgetPrefs.load(context, widgetId)
        val permitted = hasPermission(context)
        val events =
            if (permitted) {
                try {
                    EventRepository(context).upcoming(prefs)
                } catch (e: SecurityException) {
                    emptyList()
                }
            } else {
                emptyList()
            }
        manager.updateAppWidget(widgetId, build(context, widgetId, prefs, events, permitted))
        return events
    }

    private fun build(
        context: Context,
        widgetId: Int,
        prefs: WidgetPrefs,
        events: List<Event>,
        permitted: Boolean,
    ): RemoteViews {
        val pal = palette(prefs)
        val views = RemoteViews(context.packageName, R.layout.widget)
        views.setInt(android.R.id.background, "setBackgroundResource", pal.bg)

        views.setTextViewText(
            R.id.empty,
            context.getString(if (permitted) R.string.widget_empty else R.string.widget_no_permission),
        )
        views.setColor(R.id.empty, "setTextColor", pal.text2)
        views.setTextViewTextSize(R.id.empty, TypedValue.COMPLEX_UNIT_SP, prefs.fontSizeSp.toFloat())
        val emptyClick =
            if (permitted) {
                activityIntent(context, widgetId * 3, Intent(context, OpenEventActivity::class.java), mutable = false)
            } else {
                activityIntent(
                    context,
                    widgetId * 3 + 1,
                    Intent(context, SettingsActivity::class.java)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
                    mutable = false,
                )
            }
        views.setOnClickPendingIntent(R.id.empty, emptyClick)
        views.setEmptyView(R.id.list, R.id.empty)

        val labels = DateLabels(context)
        val items =
            RemoteViews.RemoteCollectionItems
                .Builder()
                .setHasStableIds(true)
                .setViewTypeCount(FontStyle.entries.size)
        for (event in events) {
            items.addItem(event.instanceId, row(context, prefs, pal, labels, event))
        }
        views.setRemoteAdapter(R.id.list, items.build())
        // Row taps fill in the event URI; explicit target, so a mutable PendingIntent is allowed.
        views.setPendingIntentTemplate(
            R.id.list,
            activityIntent(context, widgetId * 3 + 2, Intent(context, OpenEventActivity::class.java), mutable = true),
        )
        return views
    }

    private fun row(
        context: Context,
        prefs: WidgetPrefs,
        pal: Palette,
        labels: DateLabels,
        event: Event,
    ) = RemoteViews(context.packageName, prefs.font.rowLayout).apply {
        val size = prefs.fontSizeSp.toFloat()
        setTextViewText(R.id.date, labels.label(event))
        setTextViewText(R.id.title, titleText(context, prefs, event))
        setTextViewTextSize(R.id.date, TypedValue.COMPLEX_UNIT_SP, size)
        setTextViewTextSize(R.id.title, TypedValue.COMPLEX_UNIT_SP, size)
        setViewLayoutWidth(R.id.date, prefs.dateWidthDp.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
        setColor(R.id.date, "setTextColor", pal.text2)
        setColor(R.id.title, "setTextColor", pal.text)
        setInt(R.id.dot, "setColorFilter", event.color or OPAQUE)
        setOnClickFillInIntent(
            R.id.row,
            Intent()
                .setData(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventId))
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end)
                .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, event.allDay),
        )
    }

    fun titleText(
        context: Context,
        prefs: WidgetPrefs,
        event: Event,
    ): String =
        if (prefs.showLocation && event.location.isNotEmpty()) {
            context.getString(R.string.title_location, event.title, event.location)
        } else {
            event.title
        }

    /** [requestCode] must differ per widget and purpose, otherwise PendingIntents get merged. */
    private fun activityIntent(
        context: Context,
        requestCode: Int,
        intent: Intent,
        mutable: Boolean,
    ): PendingIntent {
        val flags =
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, requestCode, intent, flags)
    }

    private const val OPAQUE = 0xFF000000.toInt()
}
