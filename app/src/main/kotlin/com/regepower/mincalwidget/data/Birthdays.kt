package com.regepower.mincalwidget.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Data
import com.regepower.mincalwidget.R
import java.time.LocalDate
import java.time.ZoneOffset
import android.provider.ContactsContract.CommonDataKinds.Event as ContactEvent

/**
 * Birthdays straight from the contacts (Google removed the contacts birthday calendar in
 * Germany). Shown as all-day events; tapping one opens the contact.
 */
object Birthdays {
    const val COLOR = 0xFFE91E63.toInt()

    fun permitted(context: Context) = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Next birthday of every contact that falls into [today, lastDay). */
    fun upcoming(
        context: Context,
        today: LocalDate,
        lastDay: LocalDate,
    ): List<Event> {
        if (!permitted(context)) return emptyList()
        val projection = arrayOf(Data.CONTACT_ID, Data.LOOKUP_KEY, Data.DISPLAY_NAME, ContactEvent.START_DATE)
        val selection = "${Data.MIMETYPE}=? AND ${ContactEvent.TYPE}=?"
        val args = arrayOf(ContactEvent.CONTENT_ITEM_TYPE, ContactEvent.TYPE_BIRTHDAY.toString())
        val seen = mutableSetOf<Long>()
        val result = mutableListOf<Event>()
        context.contentResolver.query(Data.CONTENT_URI, projection, selection, args, null)?.use { c ->
            while (c.moveToNext()) {
                val contactId = c.getLong(0)
                // A contact can carry the birthday in several linked accounts (Google, Exchange, ...).
                if (!seen.add(contactId)) continue
                val parsed = parse(c.getString(3).orEmpty()) ?: continue
                val next = nextOccurrence(parsed, today)
                if (next >= lastDay) continue
                val name = c.getString(2)?.takeIf { it.isNotBlank() } ?: continue
                val age = parsed.year?.let { next.year - it }?.takeIf { it in 1..150 }
                val title =
                    if (age != null) {
                        context.getString(R.string.birthday_age, name, age)
                    } else {
                        context.getString(R.string.birthday, name)
                    }
                val begin = next.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                result +=
                    Event(
                        instanceId = -contactId - 1,
                        eventId = 0,
                        begin = begin,
                        end = begin + DAY_MS,
                        allDay = true,
                        title = title,
                        location = "",
                        color = COLOR,
                        link = Contacts.getLookupUri(contactId, c.getString(1)).toString(),
                        kind = Event.Kind.BIRTHDAY,
                    )
            }
        }
        return result
    }

    private data class Birth(
        val year: Int?,
        val month: Int,
        val day: Int,
    )

    /** Formats seen in the wild: "1985-04-23", "19850423", "--04-23" (no year), "23.04.1985". */
    private fun parse(raw: String): Birth? {
        val s = raw.trim()
        ISO.find(s)?.let { m ->
            val (y, mo, d) = m.destructured
            return Birth(y.toInt(), mo.toInt(), d.toInt()).takeIf { valid(it) }
        }
        NO_YEAR.find(s)?.let { m ->
            val (mo, d) = m.destructured
            return Birth(null, mo.toInt(), d.toInt()).takeIf { valid(it) }
        }
        GERMAN.find(s)?.let { m ->
            val (d, mo, y) = m.destructured
            return Birth(y.toIntOrNull(), mo.toInt(), d.toInt()).takeIf { valid(it) }
        }
        return null
    }

    private fun valid(b: Birth) = b.month in 1..12 && b.day in 1..31

    /** 29 Feb falls on 28 Feb in non-leap years. */
    private fun nextOccurrence(
        b: Birth,
        today: LocalDate,
    ): LocalDate {
        fun inYear(year: Int): LocalDate {
            val month = java.time.YearMonth.of(year, b.month)
            return month.atDay(b.day.coerceAtMost(month.lengthOfMonth()))
        }
        val thisYear = inYear(today.year)
        return if (thisYear < today) inYear(today.year + 1) else thisYear
    }

    private val ISO = Regex("""^(\d{4})-?(\d{2})-?(\d{2})""")
    private val NO_YEAR = Regex("""^--(\d{2})-?(\d{2})""")
    private val GERMAN = Regex("""^(\d{1,2})\.(\d{1,2})\.(\d{4})?""")
    private const val DAY_MS = 24L * 60 * 60 * 1000
}
