package de.regepower.agendago.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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

    /** Known task apps offered for every task source when installed. */
    private val TASK_APPS =
        listOf(
            Ews.TASK_APP, // Tasks & Notes (Exchange via ActiveSync)
            "com.google.android.apps.tasks", // Google Tasks
            "com.microsoft.todos", // Microsoft To Do
            "com.microsoft.office.outlook", // Outlook
            "org.tasks", // Tasks.org
            "org.dmfs.tasks", // OpenTasks
        )

    /**
     * Apps that make sense for [source] (label, package), sorted by label: installed calendar apps
     * always, plus contacts apps for birthdays, task apps and browsers for tasks, and the apps
     * registered for the source's own links. Package visibility: the manifest's queries.
     */
    fun candidates(
        context: Context,
        source: Source,
    ): List<Pair<String, String>> {
        val pm = context.packageManager
        val pkgs = linkedSetOf<String>()

        fun handlers(intent: Intent) = pm.queryIntentActivities(intent, 0).forEach { pkgs += it.activityInfo.packageName }

        fun view(uri: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        // Calendar apps: launcher category and viewers of calendar events.
        handlers(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR))
        handlers(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR))
        handlers(view("content://com.android.calendar/events/1"))
        when (source) {
            Source.CALENDAR -> Unit
            Source.BIRTHDAY -> {
                handlers(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CONTACTS))
                handlers(view("content://com.android.contacts/contacts/1"))
            }
            else -> {
                TASK_APPS.filter { installed(context, it) }.forEach { pkgs += it }
                when (source) {
                    Source.TASKS_ORG -> handlers(view("content://org.tasks.api/v0/tasks/1"))
                    Source.OPENTASKS -> handlers(view("content://org.dmfs.tasks/tasks/1"))
                    // Web links: browsers too.
                    else -> handlers(view("https://example.com/").addCategory(Intent.CATEGORY_BROWSABLE))
                }
            }
        }
        // The current choice stays selectable even if it no longer matches.
        get(context, source)?.let { if (installed(context, it)) pkgs += it }
        pkgs -= context.packageName
        pkgs -= "android" // the chooser itself
        return pkgs
            .mapNotNull { pkg -> appLabel(context, pkg)?.let { it to pkg } }
            .sortedBy { it.first.lowercase() }
    }

    @Suppress("DEPRECATION")
    private fun installed(
        context: Context,
        pkg: String,
    ): Boolean =
        try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
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
