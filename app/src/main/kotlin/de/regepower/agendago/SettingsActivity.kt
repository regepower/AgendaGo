package de.regepower.agendago

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import de.regepower.agendago.data.Birthdays
import de.regepower.agendago.data.CalendarColors
import de.regepower.agendago.data.CalendarInfo
import de.regepower.agendago.data.DateLabels
import de.regepower.agendago.data.Event
import de.regepower.agendago.data.EventRepository
import de.regepower.agendago.data.FontStyle
import de.regepower.agendago.data.PaletteColor
import de.regepower.agendago.data.TaskList
import de.regepower.agendago.data.Tasks
import de.regepower.agendago.data.WidgetPrefs
import de.regepower.agendago.widget.CalendarWidgetProvider
import de.regepower.agendago.widget.WidgetRenderer
import de.regepower.agendago.widget.WidgetUpdater
import java.util.Locale
import kotlin.math.roundToInt

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
        if (requestCode == REQUEST_WRITE_CALENDAR && CalendarColors.canWrite(this)) openCalendarColors()
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

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        AppShell.onResult(this, requestCode, resultCode, data, WidgetPrefs.store(this), WidgetPrefs::isDeviceKey) {
            refreshWidgets()
            show(widgetId)
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
        AppShell.legacyNames = listOf("ZenDay")
        root.addView(AppShell.header(this, WidgetPrefs.store(this), WidgetPrefs::isDeviceKey))

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
                addView(
                    button(R.string.btn_calendar_colors) { openCalendarColors() }
                        .also { it.tooltipText = getString(R.string.help_calendar_colors) },
                    fullWidth(top = 4),
                )
                addView(
                    switchRow(R.string.restore_colors, CalendarColors.restoreEnabled(context), R.string.help_restore_colors) {
                        CalendarColors.setRestoreEnabled(this@SettingsActivity, it)
                        if (it) refreshWidgets()
                    },
                    fullWidth(top = 4),
                )
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

    // ---- calendar colours ---------------------------------------------------------------

    /** List of all calendars; tapping one opens its colour choice. */
    private fun openCalendarColors() {
        if (!CalendarColors.canWrite(this)) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_CALENDAR), REQUEST_WRITE_CALENDAR)
            return
        }
        if (calendars.isEmpty()) {
            message(R.string.btn_calendar_colors, R.string.no_calendars)
            return
        }
        val list =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(16), px(4), px(16), px(8))
            }
        var account: String? = null
        for (calendar in calendars) {
            val accountLabel = prettyAccount(calendar.account)
            if (accountLabel != account) {
                account = accountLabel
                list.addView(accountHeader(accountLabel), fullWidth(top = if (list.childCount == 0) 4 else 12))
            }
            val dot =
                ImageView(this).apply {
                    setImageResource(R.drawable.dot)
                    setColorFilter(calendar.color or OPAQUE)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
            val row =
                LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = px(48)
                    background = rippleBackground()
                    isClickable = true
                    contentDescription = prettyName(calendar.name)
                    addView(dot, LinearLayout.LayoutParams(px(20), px(20)).apply { marginStart = px(4) })
                    addView(
                        TextView(context).apply {
                            text = prettyName(calendar.name)
                            textSize = 16f
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(16) },
                    )
                    setOnClickListener { pickColor(calendar) { color -> dot.setColorFilter(color or OPAQUE) } }
                }
            list.addView(row, fullWidth())
        }
        AlertDialog
            .Builder(this)
            .setTitle(R.string.btn_calendar_colors)
            .setView(cappedScroller(list))
            .setPositiveButton(R.string.btn_done) { _, _ -> loadData() }
            .setOnDismissListener { loadData() }
            .show()
    }

    /** Palette of the calendar's account (Google) or default palette plus free colour (others). */
    private fun pickColor(
        calendar: CalendarInfo,
        onChanged: (Int) -> Unit,
    ) {
        Thread {
            val own = CalendarColors.palette(this, calendar)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val free = CalendarColors.allowsFreeColor(calendar)
                val palette =
                    when {
                        own.isNotEmpty() -> own
                        free -> CalendarColors.DEFAULT_PALETTE.map { PaletteColor(null, it) }
                        else -> emptyList()
                    }
                showColorDialog(calendar, palette, free, onChanged)
            }
        }.start()
    }

    private fun showColorDialog(
        calendar: CalendarInfo,
        palette: List<PaletteColor>,
        free: Boolean,
        onChanged: (Int) -> Unit,
    ) {
        var dialog: AlertDialog? = null
        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(20), px(8), px(20), px(4))
            }
        if (calendar.accountType == CalendarColors.GOOGLE) {
            content.addView(
                TextView(this).apply {
                    text = getString(R.string.colors_google_hint)
                    textSize = 13f
                },
                fullWidth(),
            )
        }

        fun choose(choice: PaletteColor) {
            val app = applicationContext
            Thread {
                val ok = CalendarColors.apply(app, calendar, choice)
                runOnUiThread {
                    if (ok) {
                        onChanged(choice.color)
                        refreshWidgets()
                    } else {
                        Toast.makeText(this, R.string.color_failed, Toast.LENGTH_LONG).show()
                    }
                    dialog?.dismiss()
                }
            }.start()
        }

        val columns = SWATCH_COLUMNS
        val grid = GridLayout(this).apply { columnCount = columns }
        val size = px(SWATCH_DP)
        val current = calendar.color or OPAQUE
        for (choice in palette) {
            val selected = (choice.color or OPAQUE) == current
            grid.addView(
                ImageView(this).apply {
                    setImageResource(R.drawable.dot)
                    setColorFilter(choice.color or OPAQUE)
                    contentDescription = hex(choice.color)
                    background = if (selected) getDrawable(R.drawable.swatch_ring) else rippleBackground()
                    setPadding(px(4), px(4), px(4), px(4))
                    setOnClickListener { choose(choice) }
                },
                GridLayout.LayoutParams().apply {
                    width = size
                    height = size
                },
            )
        }
        content.addView(
            grid,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin =
                    px(8)
            },
        )

        var freeField: EditText? = null
        if (free) {
            val preview =
                ImageView(this).apply {
                    setImageResource(R.drawable.dot)
                    setColorFilter(current)
                    // Same size and inset as the palette swatches, so the row lines up with the grid.
                    setPadding(px(4), px(4), px(4), px(4))
                }
            val hsv = FloatArray(HSV_PARTS).also { Color.colorToHSV(current, it) }
            val bars = mutableListOf<SeekBar>()
            var syncing = false

            /** Gradients show what each slider does with the other two values fixed. */
            fun paintBars() {
                val hues = IntArray(HUE_STOPS) { Color.HSVToColor(floatArrayOf(it * HUE_MAX / (HUE_STOPS - 1), 1f, 1f)) }
                val sat = intArrayOf(Color.HSVToColor(floatArrayOf(hsv[0], 0f, hsv[2])), Color.HSVToColor(floatArrayOf(hsv[0], 1f, hsv[2])))
                val value = intArrayOf(Color.BLACK, Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f)))
                listOf(hues, sat, value).forEachIndexed { i, colors ->
                    bars[i].progressDrawable =
                        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors).apply { cornerRadius = px(4).toFloat() }
                }
            }
            val field =
                EditText(this).apply {
                    hint = getString(R.string.color_hex_hint)
                    setText(hex(current))
                    minEms = HEX_EMS
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                    filters = arrayOf(InputFilter.LengthFilter(HEX_LENGTH))
                    addTextChangedListener(
                        object : TextWatcher {
                            override fun beforeTextChanged(
                                s: CharSequence?,
                                start: Int,
                                count: Int,
                                after: Int,
                            ) = Unit

                            override fun onTextChanged(
                                s: CharSequence?,
                                start: Int,
                                before: Int,
                                count: Int,
                            ) = Unit

                            override fun afterTextChanged(s: Editable?) {
                                if (syncing) return
                                val color = parseHex(s?.toString()) ?: return
                                preview.setColorFilter(color)
                                Color.colorToHSV(color, hsv)
                                bars.forEachIndexed { i, bar -> bar.progress = (hsv[i] * HSV_SCALE[i]).roundToInt() }
                                paintBars()
                            }
                        },
                    )
                }
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(preview, LinearLayout.LayoutParams(size, size))
            row.addView(
                field,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = px(8)
                },
            )
            row.addView(
                TextView(this).apply { text = getString(R.string.color_free) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(8) },
            )
            content.addView(row, fullWidth(top = 12))

            for ((i, label) in listOf(R.string.color_hue, R.string.color_saturation, R.string.color_brightness).withIndex()) {
                val bar =
                    SeekBar(this).apply {
                        max = HSV_SCALE[i].toInt()
                        progress = (hsv[i] * HSV_SCALE[i]).roundToInt()
                        minHeight = px(BAR_DP)
                        maxHeight = px(BAR_DP)
                        contentDescription = getString(label)
                        tooltipText = getString(label)
                        setOnSeekBarChangeListener(
                            object : SeekBar.OnSeekBarChangeListener {
                                override fun onProgressChanged(
                                    seekBar: SeekBar,
                                    progress: Int,
                                    fromUser: Boolean,
                                ) {
                                    if (!fromUser) return
                                    hsv[i] = progress / HSV_SCALE[i]
                                    val color = Color.HSVToColor(hsv)
                                    preview.setColorFilter(color)
                                    syncing = true
                                    field.setText(hex(color))
                                    field.setSelection(field.length())
                                    syncing = false
                                    paintBars()
                                }

                                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit

                                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                            },
                        )
                    }
                bars += bar
                content.addView(
                    TextView(this).apply {
                        text = getString(label)
                        textSize = 12f
                        setTextColor(getColor(R.color.md_on_surface_variant))
                    },
                    fullWidth(top = 8),
                )
                content.addView(bar, fullWidth(top = 2))
            }
            paintBars()
            freeField = field
        }

        if (palette.isEmpty() && !free) {
            content.addView(TextView(this).apply { text = getString(R.string.colors_none) }, fullWidth(top = 8))
        }

        val builder =
            AlertDialog
                .Builder(this)
                .setTitle(prettyName(calendar.name))
                .setView(cappedScroller(content))
        val field = freeField
        if (field == null) {
            builder.setNegativeButton(android.R.string.cancel, null)
        } else {
            // Dialog button order is [negative][positive]: "Set" sits left of "Cancel".
            builder.setNegativeButton(R.string.btn_apply_color, null).setPositiveButton(android.R.string.cancel, null)
        }
        val shown = builder.show()
        dialog = shown
        // Own listener, so an invalid hex value keeps the dialog open.
        shown.getButton(AlertDialog.BUTTON_NEGATIVE).takeIf { field != null }?.setOnClickListener {
            val color = parseHex(field?.text?.toString())
            if (color == null) {
                field?.error = getString(R.string.color_hex_invalid)
            } else {
                choose(PaletteColor(null, color))
            }
        }
    }

    private fun hex(color: Int) = String.format(Locale.ROOT, "#%06X", color and RGB_MASK)

    private fun parseHex(raw: String?): Int? {
        val hex = raw?.trim()?.removePrefix("#") ?: return null
        if (hex.length != HEX_DIGITS || hex.any { it.digitToIntOrNull(HEX_RADIX) == null }) return null
        return hex.toLong(HEX_RADIX).toInt() or OPAQUE
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
        val scroller = cappedScroller(list)
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

    /** ScrollView capped to a share of the screen height, scrollbar always visible. */
    private fun cappedScroller(content: View): ScrollView {
        val maxHeight = (resources.displayMetrics.heightPixels * DIALOG_HEIGHT).toInt()
        return object : ScrollView(this) {
            override fun onMeasure(
                widthMeasureSpec: Int,
                heightMeasureSpec: Int,
            ) {
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
            }
        }.apply {
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            addView(content)
        }
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

    /** Switch with an optional second line (smaller, secondary colour) that explains it. */
    private fun switchRow(
        label: Int,
        checked: Boolean,
        subtitle: Int? = null,
        onChange: (Boolean) -> Unit,
    ): CompoundButton =
        Switch(this).apply {
            text =
                if (subtitle == null) {
                    getString(label)
                } else {
                    SpannableStringBuilder(getString(label)).append('\n').also { sb ->
                        val start = sb.length
                        sb.append(getString(subtitle))
                        sb.setSpan(RelativeSizeSpan(SUBTITLE_SCALE), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(
                            ForegroundColorSpan(getColor(R.color.md_on_surface_variant)),
                            start,
                            sb.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                }
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
        private const val REQUEST_WRITE_CALENDAR = 4
        private const val SWATCH_COLUMNS = 6
        private const val SWATCH_DP = 44
        private const val RGB_MASK = 0xFFFFFF
        private const val HEX_DIGITS = 6
        private const val HEX_EMS = 5
        private const val SUBTITLE_SCALE = 0.8f
        private const val HSV_PARTS = 3
        private const val HUE_MAX = 360f
        private const val HUE_STOPS = 7
        private const val BAR_DP = 8

        /** Slider steps per HSV component: hue in degrees, saturation and brightness in percent. */
        private val HSV_SCALE = floatArrayOf(HUE_MAX, 100f, 100f)
        private const val HEX_LENGTH = 7
        private const val HEX_RADIX = 16
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
