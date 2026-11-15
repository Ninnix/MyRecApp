package org.myrec.app

import android.app.AlertDialog
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.*
import android.widget.*
import kotlin.math.*

class InspectorUi(val a: MainActivity, var scale: Float) {
    fun px(value: Float) = (value * scale).roundToInt()
    fun shape(color: Int = Color.rgb(35, 35, 37), radius: Float = 8f, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        setColor(color); cornerRadius = px(radius).toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(max(1, px(.5f)), stroke)
    }
    fun text(value: String, size: Float = 11f, color: Int = Color.WHITE, bold: Boolean = false) = TextView(a).apply {
        text = value; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_PX, size * scale)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        gravity = Gravity.CENTER_VERTICAL; includeFontPadding = false
    }
    fun column() = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
    fun add(parent: LinearLayout, view: View, height: Float = -2f) {
        parent.addView(view, LinearLayout.LayoutParams(-1, if (height < 0) height.toInt() else px(height)))
    }
    fun gap(parent: LinearLayout, height: Float = 8f) = add(parent, View(a), height)
    fun divider(parent: LinearLayout) {
        gap(parent, 5f); add(parent, View(a).apply { setBackgroundColor(Color.rgb(57, 57, 60)) }, .5f); gap(parent, 8f)
    }
    fun action(title: String, icon: String? = null, color: Int = Panels.RED, background: Int = Color.rgb(35, 35, 37), click: () -> Unit): LinearLayout {
        val line = row().apply {
            this.background = shape(background); gravity = Gravity.CENTER
            contentDescription = title; setOnClickListener { click() }; isFocusable = true
            minimumHeight = px(44f)
        }
        if (icon != null) line.addView(Glyph(a, icon, color), LinearLayout.LayoutParams(px(18f), px(22f)).apply { rightMargin = px(6f) })
        line.addView(text(title, 11f, color))
        return line
    }
    fun icon(title: String, glyph: String, click: () -> Unit) = FrameLayout(a).apply {
        contentDescription = title; isFocusable = true; setOnClickListener { click() }
        addView(Glyph(a, glyph, Panels.RED), FrameLayout.LayoutParams(px(16f), px(18f), Gravity.CENTER))
    }
    fun disclosure(title: String, expanded: Boolean, click: () -> Unit) = row().apply {
        contentDescription = title; setOnClickListener { click() }; isFocusable = true
        addView(text(title, 11f, Panels.RED), LinearLayout.LayoutParams(0, -1, 1f))
        addView(Glyph(a, if (expanded) "Chevron down" else "Chevron right", Color.WHITE), LinearLayout.LayoutParams(px(14f), px(18f)))
    }
    fun segments(parent: LinearLayout, labels: List<String>, selected: String, change: (String) -> Unit) {
        val line = row().apply { background = shape(Color.rgb(43, 43, 46), 16f); setPadding(px(2f), px(2f), px(2f), px(2f)) }
        for (label in labels) {
            val item = text(label, 9f, bold = selected == label).apply {
                gravity = Gravity.CENTER; contentDescription = label; isSelected = selected == label
                if (isSelected) background = shape(Color.rgb(96, 96, 101), 14f)
                setOnClickListener { change(label) }
            }
            line.addView(item, LinearLayout.LayoutParams(0, px(18f), 1f))
        }
        add(parent, line, 22f)
    }
    fun grid(parent: LinearLayout, count: Int, columns: Int, height: Float, item: (Int) -> View) {
        for (start in 0 until count step columns) {
            val line = row()
            for (i in 0 until columns) {
                val child = if (start + i < count) item(start + i) else View(a)
                line.addView(child, LinearLayout.LayoutParams(0, px(height), 1f).apply { if (i < columns - 1) rightMargin = px(5f) })
            }
            add(parent, line); gap(parent, 5f)
        }
    }
    fun slider(parent: LinearLayout, title: String, initial: Float, low: Float, high: Float,
               format: (Float) -> String = { "%.0f%%".format(it * 100) }, enabled: Boolean = true,
               reset: Float? = null, inline: Boolean = false, changed: (Float) -> Unit): SeekBar {
        val line = row()
        val label = text(title, 10f)
        val value = text(format(initial), 10f, if (reset == null) Color.WHITE else Panels.RED).apply { gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL }
        line.addView(label, LinearLayout.LayoutParams(0, px(30f), 1f)); line.addView(value, LinearLayout.LayoutParams(-2, px(30f)))
        val bar = ReferenceSlider(a, scale).apply {
            max = 1000; progress = ((initial - low) / (high - low) * max).roundToInt().coerceIn(0, max)
            contentDescription = title; isEnabled = enabled
            progressTintList = android.content.res.ColorStateList.valueOf(Panels.RED)
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            alpha = if (enabled) 1f else .4f
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(view: SeekBar, progress: Int, user: Boolean) {
                val n = low + progress * (high - low) / 1000
                value.text = format(n)
                if (user) changed(n)
            }
            override fun onStartTrackingTouch(view: SeekBar) {}
            override fun onStopTrackingTouch(view: SeekBar) {}
        })
        if (inline) {
            line.removeAllViews()
            line.addView(label, LinearLayout.LayoutParams(px(42f), px(32f)))
            line.addView(bar, LinearLayout.LayoutParams(0, px(32f), 1f))
            line.addView(value, LinearLayout.LayoutParams(px(34f), px(32f)))
            add(parent, line, 32f)
        } else { add(parent, line); add(parent, bar, 27f) }
        value.contentDescription = if (reset != null) "Reset ${title.lowercase()}" else "Edit $title"
        value.setOnClickListener {
            if (reset != null) { bar.progress = ((reset - low) / (high - low) * 1000).roundToInt(); changed(reset) }
            else number(title, low, high, low + bar.progress * (high - low) / 1000) { n ->
                bar.progress = ((n - low) / (high - low) * 1000).roundToInt(); changed(n)
            }
        }
        return bar
    }
    fun number(title: String, low: Float, high: Float, initial: Float, changed: (Float) -> Unit) {
        val entry = EditText(a).apply { setText(initial.toString()); setSelectAllOnFocus(true); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED }
        val dialog = AlertDialog.Builder(a).setTitle(title).setView(entry).setNegativeButton("Cancel", null).setPositiveButton("Done", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val n = entry.text.toString().replace(',', '.').toFloatOrNull()
            if (n == null || !n.isFinite() || n !in low..high) entry.error = "Enter a value from $low to $high"
            else { changed(n); dialog.dismiss() }
        } }; dialog.show()
    }
    fun toggle(parent: LinearLayout, title: String, checked: Boolean, change: (Boolean) -> Unit) {
        val control = Switch(a).apply {
            text = title; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_PX, 11f * scale)
            isChecked = checked; contentDescription = title
            buttonTintList = android.content.res.ColorStateList.valueOf(Panels.RED)
            setOnCheckedChangeListener { _, enabled -> change(enabled) }
        }
        add(parent, control, 38f)
    }
    fun color(parent: LinearLayout, title: String, initial: Int, change: (Int) -> Unit) {
        val line = row().apply { contentDescription = title; minimumHeight = px(40f) }
        line.addView(text(title, 11f), LinearLayout.LayoutParams(0, -1, 1f))
        val swatch = View(a).apply { background = shape(initial, 6f, Color.GRAY) }
        line.addView(swatch, LinearLayout.LayoutParams(px(26f), px(26f)))
        line.setOnClickListener {
            val body = column().apply { setPadding(px(12f), px(6f), px(12f), px(10f)) }
            val channels = intArrayOf(Color.red(initial), Color.green(initial), Color.blue(initial), Color.alpha(initial))
            val sample = View(a).apply { setBackgroundColor(initial) }; add(body, sample, 30f)
            val hex = EditText(a).apply { setText("#%08x".format(initial)); setTextColor(Color.WHITE); setSingleLine(); contentDescription = "Hex color" }
            add(body, hex, 40f)
            for ((i, name) in listOf("Red", "Green", "Blue", "Opacity").withIndex()) slider(body, name, channels[i] / 255f, 0f, 1f) { value ->
                channels[i] = (value * 255).roundToInt(); val color = Color.argb(channels[3], channels[0], channels[1], channels[2])
                sample.setBackgroundColor(color); hex.setText("#%08x".format(color))
            }
            val dialog = AlertDialog.Builder(a).setTitle(title).setView(body).setNegativeButton("Cancel", null).setPositiveButton("Done", null).create()
            dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val color = runCatching { Color.parseColor(hex.text.toString()) }.getOrNull()
                if (color == null) hex.error = "Use #RRGGBB or #AARRGGBB"
                else { swatch.background = shape(color, 6f, Color.GRAY); change(color); dialog.dismiss() }
            } }; dialog.show()
        }
        add(parent, line, 40f)
    }
}
