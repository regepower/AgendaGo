package com.regepower.mincalwidget

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.regepower.mincalwidget.data.Birthdays
import com.regepower.mincalwidget.data.CalendarInfo
import com.regepower.mincalwidget.data.DateLabels
import com.regepower.mincalwidget.data.Event
import com.regepower.mincalwidget.data.EventRepository
import com.regepower.mincalwidget.data.FontStyle
import com.regepower.mincalwidget.data.TaskList
import com.regepower.mincalwidget.data.Tasks
import com.regepower.mincalwidget.data.WidgetPrefs
import com.regepower.mincalwidget.widget.CalendarWidgetProvider
import com.regepower.mincalwidget.widget.WidgetRenderer
import com.regepower.mincalwidget.widget.WidgetUpdater

/**
 * Widget settings. Opened by the launcher when a widget is placed or reconfigured (with a
 * widget id), or from the app icon (edits the only/chosen widget, else the defaults for new
 * widgets).
 */
class SettingsActivity : Activity() {
    private var widgetId: Int? = null
    private lateinit var prefs: WidgetPrefs
    private var calendars: List<CalendarInfo> = emptyList()
    private var previewEvents: List<Event> = emptyList()

    private lateinit var permissionCard: LinearLayout
    private lateinit var calendarBtn: Button
    private lateinit var birthdaySwitch: CompoundButton
    private lateinit var tasksSwitch: CompoundButton
    private lateinit var tasksNoDateSwitch: CompoundButton
    private lateinit var taskListBtn: Button
    private var taskLists: List<TaskList> = emptyList()
    private lateinit var previewBox: LinearLayout

    private val dp get() = resources.displayMetrics.density

    private fun px(value: Int) = (value * dp).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val extraId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val restoredId =
            savedInstanceState?.getInt(STATE_WIDGET, AppWidgetManager.INVALID_APPWIDGET_ID)
                ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val id = if (extraId != AppWidgetManager.INVALID_APPWIDGET_ID) extraId else restoredId
        if (id != AppWidgetManager.INVALID_APPWIDGET_ID) widgetId = id
        // While placing a widget, Back means "cancel" and the launcher removes the widget again.
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_CONFIGURE && extraId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(RESULT_CANCELED, resultData(extraId))
        }
        show(widgetId)

        if (widgetId == null && savedInstanceState == null) {
            val ids = installedWidgetIds()
            when {
                ids.size == 1 -> show(ids[0])
                ids.size > 1 -> chooseWidget(ids)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermission()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_WIDGET, widgetId ?: AppWidgetManager.INVALID_APPWIDGET_ID)
    }

    // Plain Activity API: AndroidX ActivityResult would cost far more than it saves.
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CALENDAR) {
            refreshPermission()
            refreshWidgets()
        }
        if (requestCode == REQUEST_TASKS) {
            val granted = Tasks.permitted(this)
            prefs = prefs.copy(tasks = granted)
            tasksSwitch.isChecked = granted
            updateTaskControls()
            loadData()
        }
        if (requestCode == REQUEST_CONTACTS) {
            val granted = Birthdays.permitted(this)
            prefs = prefs.copy(birthdays = granted)
            birthdaySwitch.isChecked = granted
            loadData()
        }
    }

    private fun show(id: Int?) {
        widgetId = id
        prefs = WidgetPrefs.load(this, id)
        setContentView(buildLayout())
        refreshPermission()
    }

    // ---- layout -------------------------------------------------------------------------

    private fun buildLayout(): View {
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(16), px(12), px(16), px(16))
            }
        root.addView(
            TextView(this).apply {
                text = getString(R.string.title_settings)
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(getColor(R.color.md_on_container))
            },
        )

        permissionCard =
            card().apply {
                addView(TextView(context).apply { text = getString(R.string.perm_missing) })
                addView(
                    button(R.string.btn_allow) {
                        requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), REQUEST_CALENDAR)
                    }.also { style(it, R.color.md_error_container, R.color.md_on_error_container) },
                    fullWidth(top = 8),
                )
            }
        root.addView(permissionCard, fullWidth(top = 8))

        root.addView(
            card().apply {
                addView(header(R.string.section_calendars))
                calendarBtn = button(R.string.calendars_all) { pickCalendars() }
                addView(calendarBtn, fullWidth(top = 4))
            },
            fullWidth(top = 8),
        )

        root.addView(
            card().apply {
                addView(header(R.string.section_display))
                addView(
                    seekRow(R.string.label_events, WidgetPrefs.EVENTS, prefs.maxEvents, R.string.unit_n) {
                        prefs = prefs.copy(maxEvents = it)
                    },
                    fullWidth(top = 4),
                )
                addView(
                    seekRow(R.string.label_days, WidgetPrefs.DAYS, prefs.maxDays, R.string.unit_n) {
                        prefs = prefs.copy(maxDays = it)
                        loadData()
                    },
                    fullWidth(top = 4),
                )
                addView(
                    switchRow(R.string.show_location, prefs.showLocation) {
                        prefs = prefs.copy(showLocation = it)
                        renderPreview()
                    },
                    fullWidth(top = 4),
                )
                birthdaySwitch =
                    switchRow(R.string.show_birthdays, prefs.birthdays && Birthdays.permitted(context)) { on ->
                        if (on && !Birthdays.permitted(this@SettingsActivity)) {
                            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_CONTACTS)
                        } else {
                            prefs = prefs.copy(birthdays = on)
                            loadData()
                        }
                    }.also { it.tooltipText = getString(R.string.help_birthdays) }
                addView(birthdaySwitch, fullWidth(top = 4))
            },
            fullWidth(top = 12),
        )

        root.addView(tasksCard(), fullWidth(top = 12))

        root.addView(
            card().apply {
                addView(header(R.string.section_appearance))
                addView(TextView(context).apply { text = getString(R.string.label_font) }, fullWidth(top = 4))
                addView(fontGroup(), fullWidth())
                addView(
                    seekRow(R.string.label_font_size, WidgetPrefs.FONT_SIZE, prefs.fontSizeSp, R.string.unit_sp) {
                        prefs = prefs.copy(fontSizeSp = it)
                        renderPreview()
                    },
                    fullWidth(top = 8),
                )
                addView(
                    seekRow(R.string.label_date_width, WidgetPrefs.DATE_WIDTH, prefs.dateWidthDp, R.string.unit_dp) {
                        prefs = prefs.copy(dateWidthDp = it)
                        renderPreview()
                    },
                    fullWidth(top = 4),
                )
                addView(
                    switchRow(R.string.transparent, prefs.transparent) {
                        prefs = prefs.copy(transparent = it)
                        renderPreview()
                    },
                    fullWidth(top = 4),
                )
                addView(
                    switchRow(R.string.invert, prefs.invert) {
                        prefs = prefs.copy(invert = it)
                        renderPreview()
                    }.also { it.tooltipText = getString(R.string.help_invert) },
                    fullWidth(top = 4),
                )
            },
            fullWidth(top = 12),
        )

        root.addView(
            card().apply {
                addView(header(R.string.section_preview))
                previewBox =
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(px(8), px(8), px(8), px(8))
                        clipToOutline = true
                    }
                addView(previewBox, fullWidth(top = 8))
            },
            fullWidth(top = 12),
        )

        val manager = AppWidgetManager.getInstance(this)
        if (widgetId == null && manager.isRequestPinAppWidgetSupported) {
            root.addView(button(R.string.btn_add_widget) { pinWidget() }, fullWidth(top = 12))
        }
        root.addView(
            button(R.string.btn_apply) { apply() }.also { style(it, R.color.md_primary, R.color.md_on_primary) },
            fullWidth(top = 8),
        )

        updateCalendarButton()
        renderPreview()
        return ScrollView(this).apply {
            fitsSystemWindows = true
            addView(root)
        }
    }

    private fun fontGroup() =
        RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            for (font in FontStyle.entries) {
                addView(
                    RadioButton(context).apply {
                        id = View.generateViewId()
                        text = getString(font.label)
                        typeface = Typeface.create(font.family, Typeface.NORMAL)
                        buttonTintList = ColorStateList.valueOf(getColor(R.color.md_primary))
                        isChecked = font == prefs.font
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) {
                                prefs = prefs.copy(font = font)
                                renderPreview()
                            }
                        }
                    },
                )
            }
        }

    // ---- data ---------------------------------------------------------------------------

    private fun hasPermission() = WidgetRenderer.hasPermission(this)

    private fun refreshPermission() {
        permissionCard.visibility = if (hasPermission()) View.GONE else View.VISIBLE
        loadData()
    }

    /** Loads calendars and the next few events for the preview. */
    private fun loadData() {
        if (!hasPermission()) {
            previewEvents = emptyList()
            renderPreview()
            return
        }
        val query = prefs.copy(maxEvents = PREVIEW_ROWS)
        Thread {
            val repo = EventRepository(this)
            val (cals, events) =
                try {
                    repo.calendars() to repo.upcoming(query)
                } catch (e: SecurityException) {
                    emptyList<CalendarInfo>() to emptyList<Event>()
                }
            val lists = Tasks.lists(this)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                calendars = cals
                previewEvents = events
                taskLists = lists
                updateCalendarButton()
                updateTaskListButton()
                renderPreview()
            }
        }.start()
    }

    // ---- tasks (Google Tasks via Tasks.org) ----------------------------------------------

    private fun tasksCard() =
        card().apply {
            addView(header(R.string.section_tasks))
            tasksSwitch =
                switchRow(R.string.show_tasks, prefs.tasks && Tasks.permitted(context)) { on -> onTasksToggled(on) }
                    .also { it.tooltipText = getString(R.string.help_tasks) }
            addView(tasksSwitch, fullWidth(top = 4))
            taskListBtn = button(R.string.task_lists_all) { pickTaskLists() }
            addView(taskListBtn, fullWidth(top = 4))
            tasksNoDateSwitch =
                switchRow(R.string.tasks_without_date, prefs.tasksWithoutDate) {
                    prefs = prefs.copy(tasksWithoutDate = it)
                    loadData()
                }
            addView(tasksNoDateSwitch, fullWidth(top = 4))
            updateTaskControls()
        }

    private fun onTasksToggled(on: Boolean) {
        when {
            !on -> {
                prefs = prefs.copy(tasks = false)
                updateTaskControls()
                loadData()
            }
            !Tasks.installed(this) -> {
                tasksSwitch.isChecked = false
                AlertDialog
                    .Builder(this)
                    .setTitle(R.string.show_tasks)
                    .setMessage(R.string.tasks_install)
                    .setPositiveButton(R.string.btn_install) { _, _ -> openStore() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            !Tasks.permitted(this) -> requestPermissions(arrayOf(Tasks.PERMISSION), REQUEST_TASKS)
            else -> {
                prefs = prefs.copy(tasks = true)
                updateTaskControls()
                loadData()
            }
        }
    }

    private fun openStore() {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${Tasks.PACKAGE}"))
        try {
            startActivity(market)
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${Tasks.PACKAGE}")))
        }
    }

    private fun updateTaskControls() {
        val on = prefs.tasks && Tasks.permitted(this)
        taskListBtn.isEnabled = on
        tasksNoDateSwitch.isEnabled = on
        taskListBtn.alpha = if (on) 1f else DISABLED_ALPHA
        updateTaskListButton()
    }

    private fun updateTaskListButton() {
        val chosen = taskLists.filter { it.id in prefs.taskListIds }
        taskListBtn.text =
            when {
                prefs.taskListIds.isEmpty() || chosen.isEmpty() -> getString(R.string.task_lists_all)
                chosen.size <= 2 -> chosen.joinToString(", ") { it.title }
                else -> resources.getQuantityString(R.plurals.task_lists_some, chosen.size, chosen.size)
            }
    }

    private fun updateCalendarButton() {
        val chosen = calendars.filter { it.id in prefs.calendarIds }
        calendarBtn.text =
            when {
                prefs.calendarIds.isEmpty() || chosen.isEmpty() -> getString(R.string.calendars_all)
                chosen.size <= 2 -> chosen.joinToString(", ") { prettyName(it.name) }
                else -> resources.getQuantityString(R.plurals.calendars_some, chosen.size, chosen.size)
            }
    }

    /** One entry of a picker dialog (calendar or task list). */
    private class PickItem(
        val id: Long,
        val name: String,
        val account: String,
        val color: Int,
        val marker: Int,
    )

    private fun pickCalendars() {
        if (calendars.isEmpty()) {
            message(R.string.pick_calendars_title, if (hasPermission()) R.string.no_calendars else R.string.perm_missing)
            return
        }
        val items = calendars.map { PickItem(it.id, prettyName(it.name), prettyAccount(it.account), it.color, R.drawable.dot) }
        multiPicker(R.string.pick_calendars_title, items, prefs.calendarIds) { ids ->
            prefs = prefs.copy(calendarIds = ids)
            updateCalendarButton()
            loadData()
        }
    }

    private fun pickTaskLists() {
        if (taskLists.isEmpty()) {
            message(R.string.pick_task_lists_title, R.string.no_task_lists)
            return
        }
        val items =
            taskLists.map {
                PickItem(it.id, it.title, it.account, it.color.takeIf { c -> c != 0 } ?: Tasks.DEFAULT_COLOR, R.drawable.task_box)
            }
        multiPicker(R.string.pick_task_lists_title, items, prefs.taskListIds) { ids ->
            prefs = prefs.copy(taskListIds = ids)
            updateTaskListButton()
            loadData()
        }
    }

    private fun message(
        title: Int,
        text: Int,
    ) {
        AlertDialog
            .Builder(this)
            .setTitle(title)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * Checkbox list grouped by account in a height-capped ScrollView (the built-in multi-choice
     * list did not scroll on HyperOS). [selected] empty = all; result empty = all.
     */
    private fun multiPicker(
        title: Int,
        items: List<PickItem>,
        selected: Set<Long>,
        onDone: (Set<Long>) -> Unit,
    ) {
        val list =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(16), px(4), px(16), px(8))
            }
        var account: String? = null
        val boxes =
            items.map { item ->
                if (item.account != account) {
                    account = item.account
                    list.addView(accountHeader(item.account), fullWidth(top = if (list.childCount == 0) 4 else 12))
                }
                val row = pickerRow(item, selected.isEmpty() || item.id in selected)
                list.addView(row.first, fullWidth())
                row.second
            }
        val maxHeight = (resources.displayMetrics.heightPixels * DIALOG_HEIGHT).toInt()
        val scroller =
            object : ScrollView(this) {
                override fun onMeasure(
                    widthMeasureSpec: Int,
                    heightMeasureSpec: Int,
                ) {
                    super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
                }
            }.apply {
                isVerticalScrollBarEnabled = true
                isScrollbarFadingEnabled = false
                addView(list)
            }
        AlertDialog
            .Builder(this)
            .setTitle(title)
            .setView(scroller)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val ids = items.filterIndexed { i, _ -> boxes[i].isChecked }.map { it.id }.toSet()
                // Nothing or everything ticked = all (also covers entries added later).
                onDone(if (ids.isEmpty() || ids.size == items.size) emptySet() else ids)
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun accountHeader(text: String) =
        TextView(this).apply {
            this.text = text
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(getColor(R.color.md_primary))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(px(4), 0, 0, px(2))
        }

    /** Row: checkbox, colour marker, single-line name. Tapping anywhere on the row toggles it. */
    private fun pickerRow(
        item: PickItem,
        checked: Boolean,
    ): Pair<View, CheckBox> {
        val box =
            CheckBox(this).apply {
                buttonTintList = ColorStateList.valueOf(getColor(R.color.md_primary))
                isChecked = checked
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        val row =
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = px(48)
                background = rippleBackground()
                isClickable = true
                contentDescription = item.name
                setOnClickListener { box.isChecked = !box.isChecked }
                addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(
                    ImageView(context).apply {
                        setImageResource(item.marker)
                        setColorFilter(item.color or OPAQUE)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    LinearLayout.LayoutParams(px(12), px(12)).apply { marginStart = px(8) },
                )
                addView(
                    TextView(context).apply {
                        text = item.name
                        textSize = 16f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(12) },
                )
            }
        return row to box
    }

    /** Some vendors (e.g. Xiaomi) store resource keys like "calendar_displayname_birthday" as names. */
    private fun prettyName(raw: String): String {
        val key = raw.removePrefix(XIAOMI_NAME_PREFIX)
        if (key == raw) return raw
        return when (key) {
            "birthday" -> getString(R.string.cal_birthdays)
            "xiaomi" -> "Xiaomi"
            else -> key.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
    }

    private fun prettyAccount(raw: String): String =
        when {
            raw == XIAOMI_LOCAL_ACCOUNT || raw.isBlank() -> getString(R.string.cal_local)
            raw.all { it.isDigit() } -> getString(R.string.cal_xiaomi_account, raw)
            else -> raw
        }

    private fun rippleBackground() =
        obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).let { attrs ->
            attrs.getDrawable(0).also { attrs.recycle() }
        }

    // ---- preview ------------------------------------------------------------------------

    private fun renderPreview() {
        if (!::previewBox.isInitialized) return
        val pal = WidgetRenderer.palette(prefs)
        previewBox.setBackgroundResource(pal.bg)
        previewBox.removeAllViews()
        val labels = DateLabels(this)
        val cols = WidgetRenderer.columns(prefs, labels)
        val size = prefs.fontSizeSp.toFloat()
        val (padH, padV) = WidgetRenderer.paddingDp(prefs)
        previewBox.setPadding(px(padH), px(padV), px(padH), px(padV))
        for (event in previewEvents.ifEmpty { sampleEvents() }) {
            val row = layoutInflater.inflate(prefs.font.rowLayout, previewBox, false)
            val label = labels.label(event)
            val location = if (prefs.showLocation) event.location else ""
            row.findViewById<ImageView>(R.id.dot).apply {
                setImageResource(WidgetRenderer.markerFor(event))
                setColorFilter(event.color or OPAQUE)
            }

            fun text(
                id: Int,
                value: String,
                color: Int,
                widthDp: Float? = null,
            ) = row.findViewById<TextView>(id).apply {
                text = value
                textSize = size
                setTextColor(getColor(color))
                if (widthDp != null) layoutParams = layoutParams.apply { width = (widthDp * dp).toInt() }
            }
            val dayColor = if (labels.isOverdue(event)) R.color.w_overdue else pal.text2
            text(R.id.date, label.day, dayColor, if (label.span) cols.day + cols.time else cols.day)
            text(R.id.time, label.time, pal.text2, cols.time).visibility = if (label.span) View.GONE else View.VISIBLE
            text(R.id.title, event.title, pal.text)
            text(R.id.location, location, pal.text2).visibility = if (location.isEmpty()) View.GONE else View.VISIBLE
            previewBox.addView(row)
        }
    }

    private fun sampleEvents(): List<Event> {
        val now = System.currentTimeMillis()
        val hour = 3_600_000L
        val utcMidnight = (now / DAY_MS + 3) * DAY_MS
        return listOf(
            Event(1, 0, now + 2 * hour, now + 3 * hour, false, getString(R.string.preview_title_1), "", SAMPLE_GREEN),
            Event(
                2,
                0,
                now + 20 * hour,
                now + 21 * hour,
                false,
                getString(R.string.preview_title_2),
                getString(R.string.preview_location_2),
                SAMPLE_BLUE,
            ),
            Event(3, 0, utcMidnight, utcMidnight + DAY_MS, true, getString(R.string.preview_title_3), "", SAMPLE_RED),
        )
    }

    // ---- actions ------------------------------------------------------------------------

    private fun apply() {
        WidgetPrefs.save(this, widgetId, prefs)
        refreshWidgets()
        val id = widgetId
        if (id != null) {
            setResult(RESULT_OK, resultData(id))
            finish()
        } else {
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        }
    }

    private fun pinWidget() {
        // A pinned widget starts with the defaults, so store the current settings first.
        WidgetPrefs.save(this, null, prefs)
        AppWidgetManager
            .getInstance(this)
            .requestPinAppWidget(ComponentName(this, CalendarWidgetProvider::class.java), null, null)
    }

    private fun refreshWidgets() {
        val app = applicationContext
        Thread { WidgetUpdater.updateAll(app) }.start()
    }

    private fun chooseWidget(ids: IntArray) {
        val labels = ids.indices.map { getString(R.string.widget_n, it + 1) }.toTypedArray<CharSequence>()
        AlertDialog
            .Builder(this)
            .setTitle(R.string.choose_widget)
            .setItems(labels) { _, which -> show(ids[which]) }
            .show()
    }

    private fun installedWidgetIds(): IntArray =
        AppWidgetManager
            .getInstance(this)
            .getAppWidgetIds(ComponentName(this, CalendarWidgetProvider::class.java))

    private fun resultData(id: Int) = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)

    // ---- widgets ------------------------------------------------------------------------

    private fun seekRow(
        label: Int,
        range: IntRange,
        value: Int,
        unit: Int,
        onChange: (Int) -> Unit,
    ): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this)
        head.addView(
            TextView(this).apply { text = getString(label) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val valueView =
            TextView(this).apply {
                text = getString(unit, value)
                setTextColor(getColor(R.color.md_primary))
                setTypeface(typeface, Typeface.BOLD)
            }
        head.addView(valueView)
        box.addView(head, fullWidth())
        val primary = ColorStateList.valueOf(getColor(R.color.md_primary))
        box.addView(
            SeekBar(this).apply {
                min = range.first
                max = range.last
                progress = value.coerceIn(range)
                progressTintList = primary
                thumbTintList = primary
                contentDescription = getString(label)
                setOnSeekBarChangeListener(
                    object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(
                            bar: SeekBar,
                            progress: Int,
                            fromUser: Boolean,
                        ) {
                            valueView.text = getString(unit, progress)
                            if (fromUser) onChange(progress)
                        }

                        override fun onStartTrackingTouch(bar: SeekBar) = Unit

                        override fun onStopTrackingTouch(bar: SeekBar) = Unit
                    },
                )
            },
            fullWidth(),
        )
        return box
    }

    private fun switchRow(
        label: Int,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ): CompoundButton =
        Switch(this).apply {
            text = getString(label)
            isChecked = checked
            minHeight = px(48)
            setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
        }

    private fun card() =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            clipToOutline = true
            setPadding(px(16), px(12), px(16), px(16))
        }

    private fun header(text: Int) =
        TextView(this).apply {
            setText(text)
            textSize = 13f
            setTextColor(getColor(R.color.md_primary))
            setTypeface(typeface, Typeface.BOLD)
        }

    private fun button(
        text: Int,
        onClick: () -> Unit,
    ) = Button(this).apply {
        setText(text)
        setOnClickListener { onClick() }
        style(this, R.color.md_container, R.color.md_on_container)
    }

    private fun style(
        b: Button,
        fill: Int,
        text: Int,
    ) {
        b.setBackgroundResource(R.drawable.bg_btn)
        b.backgroundTintList = ColorStateList.valueOf(getColor(fill))
        b.setTextColor(getColor(text))
        b.isAllCaps = false
        b.stateListAnimator = null
        b.minHeight = 0
        b.minimumHeight = px(44)
        b.setPadding(px(16), px(8), px(16), px(8))
    }

    private fun fullWidth(top: Int = 0) =
        LinearLayout
            .LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = px(top) }

    companion object {
        private const val REQUEST_CALENDAR = 1
        private const val REQUEST_CONTACTS = 2
        private const val REQUEST_TASKS = 3
        private const val DISABLED_ALPHA = 0.5f
        private const val STATE_WIDGET = "widget_id"
        private const val PREVIEW_ROWS = 4
        private const val DIALOG_HEIGHT = 0.6f
        private const val XIAOMI_NAME_PREFIX = "calendar_displayname_"
        private const val XIAOMI_LOCAL_ACCOUNT = "account_name_local"
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val OPAQUE = 0xFF000000.toInt()
        private const val SAMPLE_GREEN = 0xFF43A047.toInt()
        private const val SAMPLE_BLUE = 0xFF1E88E5.toInt()
        private const val SAMPLE_RED = 0xFFE53935.toInt()
    }
}
