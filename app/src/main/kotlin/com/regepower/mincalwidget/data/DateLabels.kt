package com.regepower.mincalwidget.data

import android.content.Context
import android.text.format.DateFormat
import com.regepower.mincalwidget.R
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

/** Compact date column text: "Today 16:00", "Tmrw", "24.10. 09:30" (locale-aware). */
class DateLabels(
    private val context: Context,
) {
    private val zone: ZoneId = ZoneId.systemDefault()
    private val today: LocalDate = LocalDate.now(zone)
    private val locale = context.resources.configuration.locales[0]
    private val dateFormat = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "dM"), locale)
    private val timeFormat = DateFormat.getTimeFormat(context)

    fun label(event: Event): String {
        val day = event.startDay(zone)
        val dayText = dayText(day)
        // Multi-day events: "Today - Tmrw", "Today - 5.10." (no time, like the original widget).
        val last = event.lastDay(zone)
        if (last > day && last > today) return context.getString(R.string.date_range, dayText, dayText(last))
        // All-day events and events that started on an earlier day show no time.
        if (event.allDay || day < today) return dayText
        return context.getString(R.string.date_time, dayText, timeFormat.format(Date(event.begin)))
    }

    private fun dayText(day: LocalDate): String =
        when {
            day <= today -> context.getString(R.string.today)
            day == today.plusDays(1) -> context.getString(R.string.tomorrow)
            else -> day.format(dateFormat)
        }
}
