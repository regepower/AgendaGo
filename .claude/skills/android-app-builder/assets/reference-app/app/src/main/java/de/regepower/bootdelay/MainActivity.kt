package de.regepower.bootdelay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections
import java.util.Locale

class MainActivity : Activity() {
    private class AppItem(val label: String, val pkg: String, val icon: Drawable, var selected: Boolean)

    private lateinit var prefs: Prefs
    private lateinit var overlayBtn: Button
    private lateinit var batteryBtn: Button
    private lateinit var initial: EditText
    private lateinit var gap: EditText
    private lateinit var selectedHeader: TextView
    private val selectedAdapter = AppAdapter()
    private val availableAdapter = AppAdapter()
    private var all: List<AppItem> = emptyList()
    private val order = mutableListOf<String>()
    private var query = ""

    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (prefs.lastBootCount == -1) prefs.markBootHandled(this)

        val pad = (8 * dp).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            fitsSystemWindows = true
        }

        val buttons = LinearLayout(this)
        overlayBtn = button(
            getString(R.string.btn_overlay),
            getString(R.string.help_overlay),
        ) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            )
        }
        batteryBtn = button(
            getString(R.string.btn_battery),
            getString(R.string.help_battery),
        ) {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            )
        }
        buttons.addView(overlayBtn, weight())
        buttons.addView(batteryBtn, weight())
        buttons.addView(
            button(
                getString(R.string.btn_test),
                getString(R.string.help_test),
            ) {
                save()
                LaunchService.start(this, LaunchService.SOURCE_TEST)
            }.also { styleButton(it, R.color.md_primary, R.color.md_on_primary) },
            weight(),
        )
        root.addView(buttons)

        val delays = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        initial = delayField(
            getString(R.string.label_start), prefs.initialDelaySec, delays,
            getString(R.string.help_start),
        )
        delays.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        gap = delayField(
            getString(R.string.label_gap), prefs.gapSec, delays,
            getString(R.string.help_gap),
        )
        root.addView(delays)

        val search = EditText(this).apply {
            hint = getString(R.string.search_hint)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString().orEmpty().trim().lowercase(Locale.getDefault())
                    refreshLists()
                }
            })
        }
        root.addView(search)

        selectedHeader = header()
        root.addView(selectedHeader)
        val selectedList = appList(selectedAdapter)
        root.addView(selectedList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(header().apply { text = getString(R.string.header_available) })
        root.addView(
            appList(availableAdapter),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.4f),
        )
        setContentView(root)

        attachDragSorting(selectedList)

        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        showStatus(overlayBtn, getString(R.string.btn_overlay), Settings.canDrawOverlays(this))
        showStatus(
            batteryBtn,
            getString(R.string.btn_battery),
            getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName),
        )
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    /** Tonal (primary container) when granted, error container when missing. */
    private fun showStatus(b: Button, label: String, ok: Boolean) {
        b.text = if (ok) "$label ✓" else label
        if (ok) {
            styleButton(b, R.color.md_container, R.color.md_on_container)
        } else {
            styleButton(b, R.color.md_error_container, R.color.md_on_error_container)
        }
    }

    private fun styleButton(b: Button, fill: Int, text: Int) {
        b.setBackgroundResource(R.drawable.bg_btn)
        b.backgroundTintList = ColorStateList.valueOf(getColor(fill))
        b.setTextColor(getColor(text))
        b.isAllCaps = false
        b.stateListAnimator = null
        b.minHeight = 0
        b.minimumHeight = (40 * dp).toInt()
    }

    private fun header() = TextView(this).apply {
        textSize = 13f
        setTextColor(getColor(R.color.md_primary))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, (6 * dp).toInt(), 0, (2 * dp).toInt())
    }

    private fun appList(a: AppAdapter) = RecyclerView(this).apply {
        layoutManager = LinearLayoutManager(context)
        adapter = a
        setBackgroundResource(R.drawable.bg_card)
        clipToOutline = true
        setPadding(dp.toInt(), dp.toInt(), dp.toInt(), dp.toInt())
        clipToPadding = true
    }

    private fun attachDragSorting(list: RecyclerView) {
        val callback = object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
                val a = from.bindingAdapterPosition
                val b = to.bindingAdapterPosition
                if (a < 0 || b < 0) return false
                Collections.swap(order, a, b)
                selectedAdapter.move(a, b)
                return true
            }

            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(rv: RecyclerView, holder: RecyclerView.ViewHolder) {
                super.clearView(rv, holder)
                save()
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(list)
    }

    /** Selected apps keep their start order (new ones are appended); the rest is filtered alphabetically. */
    private fun toggle(item: AppItem) {
        item.selected = !item.selected
        order.remove(item.pkg)
        if (item.selected) order.add(item.pkg)
        save()
        refreshLists()
    }

    private fun refreshLists() {
        val byPkg = all.associateBy { it.pkg }
        selectedAdapter.set(order.mapNotNull { byPkg[it] })
        selectedHeader.text = getString(R.string.header_selected, selectedAdapter.itemCount)
        availableAdapter.set(
            all.filter { !it.selected }
                .filter { query.isEmpty() || it.label.lowercase(Locale.getDefault()).contains(query) || it.pkg.contains(query) }
                .sortedBy { it.label.lowercase(Locale.getDefault()) },
        )
    }

    private fun save() {
        prefs.initialDelaySec = initial.text.toString().toIntOrNull() ?: 30
        prefs.gapSec = gap.text.toString().toIntOrNull() ?: 10
        if (all.isNotEmpty()) {
            prefs.packages = order.toList()
        }
    }

    private fun loadApps() {
        val selected = prefs.packages.toSet()
        order.clear()
        order.addAll(prefs.packages)
        Thread {
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val items = packageManager.queryIntentActivities(launcher, 0)
                .filter { it.activityInfo.packageName != packageName }
                .distinctBy { it.activityInfo.packageName }
                .map {
                    AppItem(
                        it.loadLabel(packageManager).toString(),
                        it.activityInfo.packageName,
                        it.loadIcon(packageManager),
                        it.activityInfo.packageName in selected,
                    )
                }
            runOnUiThread {
                all = items
                order.retainAll(items.map { it.pkg }.toSet())
                refreshLists()
            }
        }.start()
    }

    private fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        val m = (3 * dp).toInt()
        setMargins(m, 0, m, 0)
    }

    private fun button(text: String, help: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        tooltipText = help
        setOnClickListener { onClick() }
    }

    /** "Label [ 123 ] Sek" in one row; long-press shows [help] as tooltip. */
    private fun delayField(label: String, value: Int, parent: LinearLayout, help: String): EditText {
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(3))
            setText(value.toString())
            gravity = Gravity.END
            hint = "0"
            minEms = 3
            tooltipText = help
            contentDescription = help
        }
        val labelView = TextView(this).apply { text = label; tooltipText = help }
        val unit = TextView(this).apply { text = getString(R.string.unit_sec) }
        val m = (6 * dp).toInt()
        parent.addView(labelView, LinearLayout.LayoutParams(-2, -2).apply { marginStart = m })
        parent.addView(field)
        parent.addView(unit, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = m * 2 })
        return field
    }

    private inner class AppAdapter : RecyclerView.Adapter<AppAdapter.Holder>() {
        private val items = mutableListOf<AppItem>()

        inner class Holder(
            row: LinearLayout,
            val icon: ImageView,
            val name: TextView,
            val pkg: TextView,
            val check: CheckBox,
        ) : RecyclerView.ViewHolder(row)

        fun set(newItems: List<AppItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        fun move(from: Int, to: Int) {
            Collections.swap(items, from, to)
            notifyItemMoved(from, to)
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = parent.context
            val p = (6 * dp).toInt()
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(p, p, p, p)
                layoutParams = RecyclerView.LayoutParams(-1, -2)
                isClickable = true
                isFocusable = true
                val tv = TypedValue()
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
            }
            val size = (36 * dp).toInt()
            val icon = ImageView(ctx)
            row.addView(icon, LinearLayout.LayoutParams(size, size))
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val name = TextView(ctx).apply { textSize = 15f }
            val pkg = TextView(ctx).apply { textSize = 10f; alpha = 0.6f }
            texts.addView(name)
            texts.addView(pkg)
            row.addView(
                texts,
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = (10 * dp).toInt() },
            )
            val check = CheckBox(ctx).apply { isClickable = false; isFocusable = false }
            row.addView(check)
            val holder = Holder(row, icon, name, pkg, check)
            row.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos >= 0) toggle(items[pos])
            }
            return holder
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.icon.setImageDrawable(item.icon)
            holder.name.text = item.label
            holder.pkg.text = item.pkg
            holder.check.isChecked = item.selected
        }
    }
}
