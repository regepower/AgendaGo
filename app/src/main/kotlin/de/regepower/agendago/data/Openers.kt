package de.regepower.agendago.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import de.regepower.agendago.R

/**
 * Which app a tap on a widget row opens, chosen per source by the user ("Standard" = automatic:
 * calendar app for events, contacts for birthdays, the task app / web page of the source).
 * Stored as package names in the exported widget store, so the choice moves with the config.
 */
object Openers {
    /** Row sources; [key] is stored, [label] shown in the settings. */
    enum class Source(
        val key: String,
        val label: Int,
    ) {
        CALENDAR("calendar", R.string.open_src_calendar),
        BIRTHDAY("birthday", R.string.open_src_birthday),
        GTASKS("gtasks", R.string.src_gtasks),
        EWS("ews", R.string.src_ews),
        TASKS_ORG("tasksorg", R.string.src_tasksorg),
        OPENTASKS("opentasks", R.string.src_opentasks),
    }

    const val EXTRA_SOURCE = "de.regepower.agendago.SOURCE"
    private const val PREFIX = "open."

    /** Source of a widget row: kind, and for tasks the stable-id range of the task source. */
    fun sourceOf(event: Event): Source =
        when (event.kind) {
            Event.Kind.EVENT -> Source.CALENDAR
            Event.Kind.BIRTHDAY -> Source.BIRTHDAY
            Event.Kind.TASK ->
                when {
                    event.instanceId > TaskSources.OPENTASKS_ID_BASE -> Source.TASKS_ORG
                    event.instanceId > TaskSources.EWS_ID_BASE -> Source.OPENTASKS
                    event.instanceId > TaskSources.GTASKS_ID_BASE -> Source.EWS
                    else -> Source.GTASKS
                }
        }

    fun get(
        context: Context,
        source: Source,
    ): String? = WidgetPrefs.store(context).getString(PREFIX + source.key, null)

    /** [pkg] null = back to the automatic choice. */
    fun set(
        context: Context,
        source: Source,
        pkg: String?,
    ) {
        val editor = WidgetPrefs.store(context).edit()
        if (pkg == null) editor.remove(PREFIX + source.key) else editor.putString(PREFIX + source.key, pkg)
        editor.apply()
    }

    fun resetAll(context: Context) {
        val editor = WidgetPrefs.store(context).edit()
        for (s in Source.entries) editor.remove(PREFIX + s.key)
        editor.apply()
    }

    /** Display name of an installed app, or null if it is gone. */
    @Suppress("DEPRECATION")
    fun appLabel(
        context: Context,
        pkg: String,
    ): String? =
        try {
            val pm = context.packageManager
            pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }

    /** Launchable apps (label, package), sorted by label; needs the MAIN/LAUNCHER query in the manifest. */
    fun launchableApps(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm
            .queryIntentActivities(main, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .filter { it.second != context.packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
    }

    /**
     * The intent for a tap: [view] restricted to the chosen app if it can show it, otherwise the
     * chosen app's start screen; null = no choice (or the app is gone), use the automatic way.
     */
    fun chosenIntent(
        context: Context,
        source: Source,
        view: Intent,
    ): Intent? {
        val pkg = get(context, source) ?: return null
        val pm = context.packageManager
        val targeted = Intent(view).setPackage(pkg)
        if (pm.queryIntentActivities(targeted, 0).isNotEmpty()) return targeted
        return pm.getLaunchIntentForPackage(pkg)
    }
}
