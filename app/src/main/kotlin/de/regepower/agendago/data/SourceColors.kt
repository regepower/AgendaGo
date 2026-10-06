package de.regepower.agendago.data

import android.content.Context
import android.provider.CalendarContract.Calendars

/**
 * Task colours that match the user's calendars: Google Tasks takes the colour of the primary
 * calendar of the same Google account, Exchange tasks the colour of the Exchange calendar of
 * the same mailbox. Falls back to the source's own colour when no calendar matches or calendar
 * access is missing.
 */
object SourceColors {
    private class Cal(
        val account: String,
        val type: String,
        val owner: String,
        val primary: Boolean,
        val name: String,
        val color: Int,
    )

    private fun calendars(context: Context): List<Cal> {
        val projection =
            arrayOf(
                Calendars.ACCOUNT_NAME,
                Calendars.ACCOUNT_TYPE,
                Calendars.OWNER_ACCOUNT,
                Calendars.IS_PRIMARY,
                Calendars.CALENDAR_DISPLAY_NAME,
                Calendars.CALENDAR_COLOR,
            )
        val out = mutableListOf<Cal>()
        try {
            context.contentResolver.query(Calendars.CONTENT_URI, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    out +=
                        Cal(
                            c.getString(0).orEmpty(),
                            c.getString(1).orEmpty(),
                            c.getString(2).orEmpty(),
                            !c.isNull(3) && c.getInt(3) != 0,
                            c.getString(4).orEmpty(),
                            c.getInt(5),
                        )
                }
            }
        } catch (e: SecurityException) {
            return emptyList()
        }
        return out
    }

    /** Primary calendar of [account] (Google), else its calendar named like the account. */
    fun google(
        context: Context,
        account: String?,
    ): Int? {
        if (account == null) return null
        val own = calendars(context).filter { it.type == CalendarColors.GOOGLE && it.account.equals(account, ignoreCase = true) }
        return (
            own.firstOrNull { it.primary }
                ?: own.firstOrNull { it.owner.equals(account, ignoreCase = true) }
                ?: own.firstOrNull { it.name.equals(account, ignoreCase = true) }
        )?.color
    }

    /**
     * Exchange calendar of the EWS mailbox: account types of ActiveSync clients contain "exchange"
     * or "eas"; the account (e-mail) must match the EWS user ("DOMAIN\user" or UPN) or, failing
     * that, the server's mail domain (mail.example.com → example.com).
     */
    fun exchange(
        context: Context,
        user: String,
        url: String,
    ): Int? {
        val name = user.substringAfter('\\').substringBefore('@').lowercase()
        val host =
            url
                .removePrefix("https://")
                .substringBefore('/')
                .substringBefore(':')
                .lowercase()
        val domain = host.split('.').takeLast(2).joinToString(".")
        val eas =
            calendars(context).filter {
                val t = it.type.lowercase()
                t.contains("exchange") || t.contains("eas")
            }
        val byUser = eas.filter { name.isNotEmpty() && it.account.lowercase().substringBefore('@') == name }
        val byDomain = eas.filter { domain.isNotEmpty() && it.account.lowercase().endsWith("@$domain") }
        val pool = byUser.ifEmpty { byDomain }
        return (
            pool.firstOrNull { it.primary } ?: pool.firstOrNull {
                it.owner.equals(
                    it.account,
                    ignoreCase = true,
                )
            } ?: pool.firstOrNull()
        )?.color
    }
}
