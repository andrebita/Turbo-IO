package org.turboio.customai.ui

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Tiny programmatic layout helpers so each screen stays a single readable Kotlin file. */
class Column(val context: Context) {
    val root: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(16); setPadding(p, p, p, dp(32))
    }

    fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    fun scroll(): ScrollView = ScrollView(context).apply { isFillViewport = true; addView(root) }

    private fun <T : View> add(view: T, top: Int = 8): T {
        root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) })
        return view
    }

    fun title(text: String) = add(TextView(context).apply {
        this.text = text; textSize = 18f; setTypeface(typeface, Typeface.BOLD)
    }, top = 20)

    fun text(text: String = "", size: Float = 14f, mono: Boolean = false) = add(TextView(context).apply {
        this.text = text; textSize = size; setTextIsSelectable(true)
        if (mono) typeface = Typeface.MONOSPACE
    })

    fun hint(text: String) = add(TextView(context).apply { this.text = text; textSize = 12.5f; alpha = 0.75f }, top = 4)

    fun card(): Column {
        val card = MaterialCardView(context).apply { radius = dp(12).toFloat(); cardElevation = dp(1).toFloat() }
        val inner = Column(context)
        inner.root.setPadding(dp(14), dp(10), dp(14), dp(14))
        card.addView(inner.root)
        add(card, top = 10)
        return inner
    }

    fun button(label: String, filled: Boolean = true, onClick: () -> Unit) = add(
        MaterialButton(context, null, if (filled) com.google.android.material.R.attr.materialButtonStyle
            else com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label; isAllCaps = false; setOnClickListener { onClick() }
        }, top = 6)

    fun field(label: String, value: String = "", multiline: Boolean = false, password: Boolean = false, number: Boolean = false): TextInputEditText {
        val layout = TextInputLayout(context).apply {
            hint = label
            if (password) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val edit = TextInputEditText(layout.context).apply {
            setText(value)
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
        add(layout)
        return edit
    }

    fun switch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = add(MaterialSwitch(context).apply {
        text = label; isChecked = checked; setOnCheckedChangeListener { _, v -> onChange(v) }
    })

    fun <T> spinner(items: List<T>, selected: Int, label: (T) -> String, onSelect: (T) -> Unit): Spinner {
        val spinner = Spinner(context)
        spinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, items.map(label))
        spinner.setSelection(selected.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { onSelect(items[position]) }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        return add(spinner)
    }

    fun row(): LinearLayout = add(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL })

    /** Equal-width small buttons side by side. */
    fun buttons(vararg actions: Pair<String, () -> Unit>): LinearLayout {
        val row = row()
        for ((label, action) in actions) row.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label; isAllCaps = false; textSize = 13f; setOnClickListener { action() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        return row
    }

    fun clear() = root.removeAllViews()
}

fun statusColor(ok: Boolean?): Int = when (ok) {
    true -> 0xFF2E7D32.toInt()
    false -> 0xFFC62828.toInt()
    null -> 0xFF6D6D6D.toInt()
}
