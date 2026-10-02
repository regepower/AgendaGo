package com.regepower.mincalwidget.data

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.text.format.DateFormat
import android.util.TypedValue
import com.regepower.mincalwidget.R
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

/**
 * Text for the day and time columns, like the original widget:
 * "Today | 16:00", "Tmrw | ", "24.10. | 09:30", multi-day "Today - Tmrw" across both columns.
 */
class DateLabels(
    private val context: Context,
) {
    /** [span] = text uses the day and the time column (multi-day range). */
    data class Label(
        val day: String,
        val time: String,
        val span: Boolean,
    )

    private val zone: ZoneId = ZoneId.systemDefault()
    private val today: LocalDate = LocalDate.now(zone)
    private val locale = context.resources.configuration.locales[0]
    private val dateFormat = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "dM"), locale)
    private val timeFormat = DateFormat.getTimeFormat(context)

    fun label(event: Event): Label {
        val day = event.startDay(zone)
        val dayText = dayText(day)
        val last = event.lastDay(zone)
        if (last > day && last > today) {
            return Label(context.getString(R.string.date_range, dayText, dayText(last)), "", span = true)
        }
        // All-day events and events that started on an earlier day show no time.
        val time = if (event.allDay || day < today) "" else timeFormat.format(Date(event.begin))
        return Label(dayText, time, span = false)
    }

    /** Width that fits the longest time ("22:59", "10:59 PM") in this font and size, plus a gap. */
    fun timeColumnWidthDp(prefs: WidgetPrefs): Float {
        val metrics = context.resources.displayMetrics
        val paint =
            Paint().apply {
                typeface = Typeface.create(prefs.font.family, Typeface.NORMAL)
                textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, prefs.fontSizeSp.toFloat(), metrics)
            }
        val sample =
            today
                .atTime(22, 58)
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
        val widthPx = paint.measureText(timeFormat.format(Date(sample)))
        return widthPx / metrics.density + COLUMN_GAP_DP
    }

    private fun dayText(day: LocalDate): String =
        when {
            day <= today -> context.getString(R.string.today)
            day == today.plusDays(1) -> context.getString(R.string.tomorrow)
            else -> day.format(dateFormat)
        }

    companion object {
        const val COLUMN_GAP_DP = 6f
    }
}
