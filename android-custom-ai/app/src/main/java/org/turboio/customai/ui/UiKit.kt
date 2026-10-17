package org.turboio.customai.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.turboio.customai.R

/** Colors of the dark "glass" look (same values as res/values/colors.xml). */
object Palette {
    const val BG = 0xFF080B0A.toInt()
    const val SURFACE = 0xFF121816.toInt()
    const val SURFACE_HIGH = 0xFF1A221F.toInt()
    const val STROKE = 0xFF25302B.toInt()
    const val PRIMARY = 0xFF3DDC97.toInt()
    const val ON_PRIMARY = 0xFF04150D.toInt()
    const val PRIMARY_CONTAINER = 0xFF113A29.toInt()
    const val TEXT = 0xFFEEF3F0.toInt()
    const val MUTED = 0xFF8D9A93.toInt()
    const val DANGER = 0xFFF87171.toInt()
    const val WARNING = 0xFFFBBF24.toInt()
}

/** A shortcut tile: icon, name and a short state ("Ligado", "Gravando"…). */
data class Tile(val icon: Int, val title: String, val status: String, val on: Boolean, val onClick: () -> Unit)

/** Tiny programmatic layout helpers so each screen stays a single readable Kotlin file. */
class Column(val context: Context) {
    val root: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(18); setPadding(p, dp(8), p, dp(40))
    }

    fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    fun scroll(): ScrollView = ScrollView(context).apply { isFillViewport = true; clipToPadding = false; addView(root) }

    private fun <T : View> add(view: T, top: Int = 8): T {
        root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) })
        return view
    }

    private fun rounded(color: Int, radius: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); if (stroke != 0) setStroke(dp(1), stroke)
    }

    /** Screen title (large) with an optional line below. */
    fun header(title: String, subtitle: String = ""): TextView {
        add(TextView(context).apply {
            text = title; textSize = 28f; setTextColor(Palette.TEXT); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = -0.01f
        }, top = 18)
        return add(TextView(context).apply {
            text = subtitle; textSize = 14f; setTextColor(Palette.MUTED); visibility = if (subtitle.isEmpty()) View.GONE else View.VISIBLE
        }, top = 2)
    }

    /** Small label above a group of cards. */
    fun section(text: String) = add(TextView(context).apply {
        this.text = text.uppercase(); textSize = 12f; setTextColor(Palette.MUTED); letterSpacing = 0.08f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }, top = 26)

    fun title(text: String) = add(TextView(context).apply {
        this.text = text; textSize = 17f; setTextColor(Palette.TEXT); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }, top = 20)

    fun text(text: String = "", size: Float = 14f, mono: Boolean = false) = add(TextView(context).apply {
        this.text = text; textSize = size; setTextIsSelectable(true); setTextColor(Palette.TEXT); setLineSpacing(0f, 1.15f)
        if (mono) typeface = Typeface.MONOSPACE
    })

    fun hint(text: String) = add(TextView(context).apply { this.text = text; textSize = 13f; setTextColor(Palette.MUTED); setLineSpacing(0f, 1.15f) }, top = 4)

    /** Rounded "glass" card; returns a Column to fill. */
    fun card(): Column {
        val card = MaterialCardView(context).apply {
            radius = dp(20).toFloat(); cardElevation = 0f
            setCardBackgroundColor(Palette.SURFACE); strokeColor = Palette.STROKE; strokeWidth = dp(1)
        }
        val inner = Column(context)
        inner.root.setPadding(dp(16), dp(6), dp(16), dp(16))
        card.addView(inner.root)
        add(card, top = 12)
        return inner
    }

    private fun styled(button: MaterialButton, filled: Boolean) = button.apply {
        isAllCaps = false; letterSpacing = 0f; cornerRadius = dp(24); minHeight = dp(48); insetTop = 0; insetBottom = 0
        if (filled) {
            backgroundTintList = ColorStateList.valueOf(Palette.PRIMARY); setTextColor(Palette.ON_PRIMARY)
        } else {
            backgroundTintList = ColorStateList.valueOf(Palette.SURFACE_HIGH); setTextColor(Palette.TEXT)
            strokeColor = ColorStateList.valueOf(Palette.STROKE); strokeWidth = dp(1)
        }
    }

    fun button(label: String, filled: Boolean = true, onClick: () -> Unit) = add(
        styled(MaterialButton(context), filled).apply { text = label; setOnClickListener { onClick() } }, top = 8)

    fun field(label: String, value: String = "", multiline: Boolean = false, password: Boolean = false, number: Boolean = false): TextInputEditText {
        val layout = TextInputLayout(context, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = label
            if (password) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            val r = dp(14).toFloat(); setBoxCornerRadii(r, r, r, r)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxBackgroundColor = Palette.SURFACE_HIGH
            boxStrokeColor = Palette.PRIMARY
            defaultHintTextColor = ColorStateList.valueOf(Palette.MUTED)
            hintTextColor = ColorStateList.valueOf(Palette.PRIMARY)
        }
        val edit = TextInputEditText(layout.context).apply {
            setText(value); setTextColor(Palette.TEXT)
            inputType = when {
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                number -> InputType.TYPE_CLASS_NUMBER
                multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            if (multiline) { minLines = 3; maxLines = 12; isSingleLine = false }
            if (password) {
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                typeface = Typeface.DEFAULT // password inputType switches to monospace otherwise
            }
        }
        layout.addView(edit)
        add(layout, top = 10)
        return edit
    }

    fun switch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = add(MaterialSwitch(context).apply {
        text = label; isChecked = checked; textSize = 15f; setTextColor(Palette.TEXT); minHeight = dp(52)
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }, top = 2)

    fun <T> spinner(items: List<T>, selected: Int, label: (T) -> String, onSelect: (T) -> Unit): Spinner {
        val spinner = Spinner(context, Spinner.MODE_DROPDOWN)
        spinner.adapter = darkAdapter(context, items.map(label))
        spinner.setSelection(selected.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { onSelect(items[position]) }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        spinner.background = LayerDrawable(arrayOf(rounded(Palette.SURFACE_HIGH, 14, Palette.STROKE),
            ContextCompat.getDrawable(context, R.drawable.ic_chevron)!!.mutate().apply { setTint(Palette.MUTED) })).apply {
            setLayerGravity(1, Gravity.END or Gravity.CENTER_VERTICAL); setLayerInsetEnd(1, dp(14))
            setLayerSize(1, dp(16), dp(16))
        }
        spinner.setPopupBackgroundDrawable(rounded(Palette.SURFACE_HIGH, 14, Palette.STROKE))
        spinner.setPadding(dp(14), 0, dp(36), 0)
        spinner.minimumHeight = dp(52)
        return add(spinner, top = 10)
    }

    fun row(): LinearLayout = add(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL })

    /** Equal-width pill buttons side by side. */
    fun buttons(vararg actions: Pair<String, () -> Unit>): LinearLayout {
        val row = row()
        actions.forEachIndexed { i, (label, action) ->
            row.addView(styled(MaterialButton(context), false).apply {
                text = label; textSize = 13.5f; setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i < actions.lastIndex) marginEnd = dp(8) })
        }
        (row.layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
        return row
    }

    /** Small rounded status label (green when [ok], red when false, grey otherwise). */
    fun chip(text: String, ok: Boolean? = null): TextView = chipView(context, text, ok).also {
        root.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
    }

    /** A tappable line: icon, title, optional detail and a chevron. */
    fun nav(icon: Int, title: String, detail: String = "", onClick: () -> Unit): LinearLayout {
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(64)
            setPadding(dp(14), dp(10), dp(14), dp(10)); background = rounded(Palette.SURFACE, 18, Palette.STROKE)
            isClickable = true; setOnClickListener { onClick() }
        }
        line.addView(iconBadge(context, icon, false), LinearLayout.LayoutParams(dp(40), dp(40)))
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(8), 0) }
        texts.addView(TextView(context).apply { text = title; textSize = 16f; setTextColor(Palette.TEXT) })
        if (detail.isNotEmpty()) texts.addView(TextView(context).apply { text = detail; textSize = 13f; setTextColor(Palette.MUTED) })
        line.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(ImageView(context).apply { setImageResource(R.drawable.ic_chevron); imageTintList = ColorStateList.valueOf(Palette.MUTED) },
            LinearLayout.LayoutParams(dp(18), dp(18)))
        return add(line, top = 10)
    }

    /** Two-column grid of shortcut tiles. */
    fun tiles(items: List<Tile>): LinearLayout {
        val grid = add(LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }, top = 4)
        items.chunked(2).forEach { pair ->
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, t ->
                line.addView(tileView(t), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i == 0) marginEnd = dp(6) else marginStart = dp(6)
                })
            }
            if (pair.size == 1) line.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(6) })
            grid.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        }
        return grid
    }

    private fun tileView(t: Tile): View {
        val card = MaterialCardView(context).apply {
            radius = dp(20).toFloat(); cardElevation = 0f; strokeWidth = dp(1)
            setCardBackgroundColor(if (t.on) 0xFF12201A.toInt() else Palette.SURFACE)
            strokeColor = if (t.on) 0xFF1F4A37.toInt() else Palette.STROKE
            isClickable = true; isFocusable = true; setOnClickListener { t.onClick() }
        }
        val inner = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        inner.addView(iconBadge(context, t.icon, t.on), LinearLayout.LayoutParams(dp(40), dp(40)))
        inner.addView(TextView(context).apply { text = t.title; textSize = 15.5f; setTextColor(Palette.TEXT); maxLines = 2 },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        inner.addView(TextView(context).apply { text = t.status; textSize = 13f; setTextColor(if (t.on) Palette.PRIMARY else Palette.MUTED); maxLines = 1 },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        card.addView(inner)
        return card
    }

    fun clear() = root.removeAllViews()
}

/** Round icon badge: green when [on]. */
fun iconBadge(context: Context, icon: Int, on: Boolean): ImageView {
    val d = context.resources.displayMetrics.density
    return ImageView(context).apply {
        setImageResource(icon); scaleType = ImageView.ScaleType.CENTER_INSIDE
        val p = (9 * d).toInt(); setPadding(p, p, p, p)
        imageTintList = ColorStateList.valueOf(if (on) Palette.PRIMARY else Palette.TEXT)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (on) Palette.PRIMARY_CONTAINER else Palette.SURFACE_HIGH) }
    }
}

fun chipView(context: Context, text: String, ok: Boolean?): TextView {
    val d = context.resources.displayMetrics.density
    val color = when (ok) { true -> Palette.PRIMARY; false -> Palette.DANGER; null -> Palette.MUTED }
    return TextView(context).apply {
        this.text = "●  $text"; textSize = 12.5f; setTextColor(color)
        setPadding((12 * d).toInt(), (5 * d).toInt(), (12 * d).toInt(), (5 * d).toInt())
        background = GradientDrawable().apply { cornerRadius = 100 * d; setColor((color and 0x00FFFFFF) or 0x1F000000); setStroke((1 * d).toInt(), (color and 0x00FFFFFF) or 0x55000000) }
    }
}

/** Dropdown adapter with light text on the dark surface. */
fun darkAdapter(context: Context, labels: List<String>): ArrayAdapter<String> =
    object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_dropdown_item, labels) {
        private fun style(v: View): View { (v as? TextView)?.apply { setTextColor(Palette.TEXT); textSize = 15f }; return v }
        override fun getView(position: Int, convertView: View?, parent: ViewGroup) = style(super.getView(position, convertView, parent))
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup) = style(super.getDropDownView(position, convertView, parent))
    }

/** Shows or hides a [Column.field] together with its label box. */
fun TextInputEditText.showField(on: Boolean) {
    var v: android.view.ViewParent? = parent
    while (v != null && v !is TextInputLayout) v = v.parent
    ((v as? View) ?: this).visibility = if (on) View.VISIBLE else View.GONE
}

fun statusColor(ok: Boolean?): Int = when (ok) {
    true -> Palette.PRIMARY
    false -> Palette.DANGER
    null -> Palette.MUTED
}
