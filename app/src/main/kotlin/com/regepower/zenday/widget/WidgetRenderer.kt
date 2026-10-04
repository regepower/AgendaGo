package com.regepower.zenday.widget

import android.Manifest
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.regepower.zenday.OpenEventActivity
import com.regepower.zenday.R
import com.regepower.zenday.SettingsActivity
import com.regepower.zenday.data.DateLabels
import com.regepower.zenday.data.Event
import com.regepower.zenday.data.EventRepository
import com.regepower.zenday.data.FontStyle
import com.regepower.zenday.data.WidgetPrefs

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
        val density = context.resources.displayMetrics.density
        val (padH, padV) = paddingDp(prefs).let { (h, v) -> (h * density).toInt() to (v * density).toInt() }
        views.setViewPadding(android.R.id.background, padH, padV, padH, padV)

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
        val cols = columns(prefs, labels)
        val items =
            RemoteViews.RemoteCollectionItems
                .Builder()
                .setHasStableIds(true)
                .setViewTypeCount(FontStyle.entries.size)
        for (event in events) {
            items.addItem(event.instanceId, row(context, prefs, pal, labels, cols, event))
        }
        views.setRemoteAdapter(R.id.list, items.build())
        // Row taps fill in the event URI; explicit target, so a mutable PendingIntent is allowed.
        views.setPendingIntentTemplate(
            R.id.list,
            activityIntent(context, widgetId * 3 + 2, Intent(context, OpenEventActivity::class.java), mutable = true),
        )
        return views
    }

    /** Empty checkbox for tasks, colour dot otherwise. */
    fun markerFor(event: Event) = if (event.kind == Event.Kind.TASK) R.drawable.task_box else R.drawable.dot

    /** Column widths (dp) shared by widget and settings preview. */
    data class Columns(
        val day: Float,
        val time: Float,
    )

    fun columns(
        prefs: WidgetPrefs,
        labels: DateLabels,
    ) = Columns(prefs.dateWidthDp.toFloat(), labels.timeColumnWidthDp(prefs))

    /** Inner padding in dp: none on a transparent background, room for the rounded corners otherwise. */
    fun paddingDp(prefs: WidgetPrefs): Pair<Int, Int> = if (prefs.transparent) 0 to 0 else 10 to 6

    private fun row(
        context: Context,
        prefs: WidgetPrefs,
        pal: Palette,
        labels: DateLabels,
        cols: Columns,
        event: Event,
    ) = RemoteViews(context.packageName, prefs.font.rowLayout).apply {
        val size = prefs.fontSizeSp.toFloat()
        val label = labels.label(event)
        setImageViewResource(R.id.dot, markerFor(event))
        setTextViewText(R.id.date, label.day)
        setTextViewText(R.id.time, label.time)
        setTextViewText(R.id.title, event.title)
        val location = if (prefs.showLocation) event.location else ""
        setTextViewText(R.id.location, location)
        setViewVisibility(R.id.location, if (location.isEmpty()) View.GONE else View.VISIBLE)
        // A multi-day range uses the time column too.
        setViewVisibility(R.id.time, if (label.span) View.GONE else View.VISIBLE)
        val dayWidth = if (label.span) cols.day + cols.time else cols.day
        setViewLayoutWidth(R.id.date, dayWidth, TypedValue.COMPLEX_UNIT_DIP)
        setViewLayoutWidth(R.id.time, cols.time, TypedValue.COMPLEX_UNIT_DIP)
        for (id in TEXT_IDS) setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, size)
        setColor(R.id.date, "setTextColor", if (labels.isOverdue(event)) R.color.w_overdue else pal.text2)
        setColor(R.id.time, "setTextColor", pal.text2)
        setColor(R.id.title, "setTextColor", pal.text)
        setColor(R.id.location, "setTextColor", pal.text2)
        setInt(R.id.dot, "setColorFilter", event.color or OPAQUE)
        setOnClickFillInIntent(
            R.id.row,
            Intent()
                .setData(
                    event.link?.let(Uri::parse)
                        ?: ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventId),
                ).putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end)
                .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, event.allDay),
        )
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
    private val TEXT_IDS = intArrayOf(R.id.date, R.id.time, R.id.title, R.id.location)
}
