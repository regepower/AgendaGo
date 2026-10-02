package com.regepower.mincalwidget

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
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
import com.regepower.mincalwidget.data.CalendarInfo
import com.regepower.mincalwidget.data.DateLabels
import com.regepower.mincalwidget.data.Event
import com.regepower.mincalwidget.data.EventRepository
import com.regepower.mincalwidget.data.FontStyle
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
            },
            fullWidth(top = 12),
        )

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
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                calendars = cals
                previewEvents = events
                updateCalendarButton()
                renderPreview()
            }
        }.start()
    }

    private fun updateCalendarButton() {
        val chosen = calendars.filter { it.id in prefs.calendarIds }
        calendarBtn.text =
            when {
                prefs.calendarIds.isEmpty() || chosen.isEmpty() -> getString(R.string.calendars_all)
                chosen.size <= 2 -> chosen.joinToString(", ") { it.name }
                else -> resources.getQuantityString(R.plurals.calendars_some, chosen.size, chosen.size)
            }
    }

    /**
     * Own checkbox list in a height-capped ScrollView: the built-in multi-choice dialog list did
     * not scroll on the test phone (HyperOS), so long calendar lists were cut off.
     */
    private fun pickCalendars() {
        if (calendars.isEmpty()) {
            AlertDialog
                .Builder(this)
                .setTitle(R.string.pick_calendars_title)
                .setMessage(if (hasPermission()) R.string.no_calendars else R.string.perm_missing)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val tint = ColorStateList.valueOf(getColor(R.color.md_primary))
        val boxes =
            calendars.map { calendar ->
                CheckBox(this).apply {
                    text = coloredLabel(calendar)
                    buttonTintList = tint
                    minHeight = px(48)
                    isChecked = prefs.calendarIds.isEmpty() || calendar.id in prefs.calendarIds
                }
            }
        val list =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(20), px(8), px(20), px(8))
                boxes.forEach { addView(it, fullWidth()) }
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
            .setTitle(R.string.pick_calendars_title)
            .setView(scroller)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val selected = calendars.filterIndexed { i, _ -> boxes[i].isChecked }.map { it.id }.toSet()
                // Nothing or everything ticked = all calendars (also covers calendars added later).
                prefs =
                    prefs.copy(
                        calendarIds = if (selected.isEmpty() || selected.size == calendars.size) emptySet() else selected,
                    )
                updateCalendarButton()
                loadData()
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun coloredLabel(calendar: CalendarInfo): CharSequence {
        val text = SpannableString(getString(R.string.title_location, DOT, calendar.label))
        text.setSpan(ForegroundColorSpan(calendar.color or OPAQUE), 0, DOT.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return text
    }

    // ---- preview ------------------------------------------------------------------------

    private fun renderPreview() {
        if (!::previewBox.isInitialized) return
        val pal = WidgetRenderer.palette(prefs)
        previewBox.setBackgroundResource(pal.bg)
        previewBox.removeAllViews()
        val labels = DateLabels(this)
        val size = prefs.fontSizeSp.toFloat()
        for (event in previewEvents.ifEmpty { sampleEvents() }) {
            val row = layoutInflater.inflate(prefs.font.rowLayout, previewBox, false)
            row.findViewById<ImageView>(R.id.dot).setColorFilter(event.color or OPAQUE)
            row.findViewById<TextView>(R.id.date).apply {
                text = labels.label(event)
                textSize = size
                setTextColor(getColor(pal.text2))
                layoutParams = layoutParams.apply { width = px(prefs.dateWidthDp) }
            }
            row.findViewById<TextView>(R.id.title).apply {
                text = WidgetRenderer.titleText(this@SettingsActivity, prefs, event)
                textSize = size
                setTextColor(getColor(pal.text))
            }
            previewBox.addView(row)
        }
    }

    private fun sampleEvents(): List<Event> {
        val now = System.currentTimeMillis()
        val hour = 3_600_000L
        val utcMidnight = (now / DAY_MS + 3) * DAY_MS
        return listOf(
            Event(1, 0, now + 2 * hour, now + 3 * hour, false, getString(R.string.preview_title_1), "", SAMPLE_GREEN),
            Event(2, 0, now + 20 * hour, now + 21 * hour, false, getString(R.string.preview_title_2), "", SAMPLE_BLUE),
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
        private const val STATE_WIDGET = "widget_id"
        private const val PREVIEW_ROWS = 4
        private const val DIALOG_HEIGHT = 0.6f
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val OPAQUE = 0xFF000000.toInt()
        private const val DOT = "●"
        private const val SAMPLE_GREEN = 0xFF43A047.toInt()
        private const val SAMPLE_BLUE = 0xFF1E88E5.toInt()
        private const val SAMPLE_RED = 0xFFE53935.toInt()
    }
}
