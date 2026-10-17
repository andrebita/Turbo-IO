package org.turboio.customai.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import org.turboio.customai.R

/** Everything the home screen shows; built from the app state by [HomeFragment] (and from sample data in screenshot tests). */
data class HomeModel(
    val name: String,
    /** true = connected, false = disconnected, null = connecting. */
    val connected: Boolean?,
    val connection: String,
    val battery: Int?,
    val firmware: String,
    val tiles: List<Tile>,
    val todayTitle: String,
    val todayLines: List<String>,
)

class HomeActions(
    val connect: () -> Unit,
    val glasses: () -> Unit,
    val today: () -> Unit,
    val ask: () -> Unit,
)

object HomeView {
    fun build(context: Context, m: HomeModel, a: HomeActions): View {
        val ui = Column(context)
        val d = context.resources.displayMetrics.density
        ui.header(m.name, "Turbo IO · sua IA nos óculos")
        ui.root.addView(heroCard(context, m, a), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (16 * d).toInt() })
        ui.section("Atalhos")
        ui.tiles(m.tiles)
        ui.section("Hoje")
        val today = ui.card()
        today.title(m.todayTitle)
        m.todayLines.forEach { today.hint(it) }
        today.button("Abrir memória", filled = false) { a.today() }
        ui.root.setPadding(ui.root.paddingLeft, ui.root.paddingTop, ui.root.paddingRight, (110 * d).toInt())   // room for the pill
        val frame = FrameLayout(context)
        frame.addView(ui.scroll())
        frame.addView(askPill(context, a.ask), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (56 * d).toInt(), Gravity.BOTTOM).apply {
            val m2 = (18 * d).toInt(); setMargins(m2, 0, m2, (16 * d).toInt())
        })
        return frame
    }

    private fun heroCard(context: Context, m: HomeModel, a: HomeActions): View {
        val d = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val card = MaterialCardView(context).apply {
            radius = dp(24).toFloat(); cardElevation = 0f; strokeWidth = dp(1); strokeColor = 0xFF1F3A2D.toInt()
            background = null
            setCardBackgroundColor(Palette.SURFACE)
        }
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(18))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF153126.toInt(), 0xFF0E1714.toInt(), Palette.SURFACE)).apply { cornerRadius = dp(24).toFloat() }
        }
        val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(chipView(context, m.connection, m.connected), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        top.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        if (m.battery != null) top.addView(ImageView(context).apply { setImageResource(R.drawable.ic_battery); imageTintList = ColorStateList.valueOf(Palette.MUTED) }, LinearLayout.LayoutParams(dp(22), dp(22)))
        inner.addView(top)
        val middle = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(context).apply {
            text = if (m.battery != null) "${m.battery}%" else "Conecte os óculos"; textSize = if (m.battery != null) 44f else 26f
            setTextColor(Palette.TEXT); contentDescription = if (m.battery != null) "Bateria" else null
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        })
        texts.addView(TextView(context).apply { text = m.firmware; textSize = 13f; setTextColor(Palette.MUTED) })
        middle.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        middle.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_glasses_line)
            imageTintList = ColorStateList.valueOf(if (m.connected == true) Palette.PRIMARY else Palette.MUTED)
            alpha = 0.9f
        }, LinearLayout.LayoutParams(dp(96), dp(96)))
        inner.addView(middle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        fun pill(label: String, filled: Boolean, click: () -> Unit) = MaterialButton(context).apply {
            text = label; isAllCaps = false; letterSpacing = 0f; cornerRadius = dp(24); minHeight = dp(46); insetTop = 0; insetBottom = 0; maxLines = 1
            backgroundTintList = ColorStateList.valueOf(if (filled) Palette.PRIMARY else Palette.SURFACE_HIGH)
            setTextColor(if (filled) Palette.ON_PRIMARY else Palette.TEXT)
            if (!filled) { strokeColor = ColorStateList.valueOf(Palette.STROKE); strokeWidth = dp(1) }
            setOnClickListener { click() }
        }
        if (m.connected != true) actions.addView(pill("Conectar", true, a.connect), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        actions.addView(pill(if (m.connected == true) "Ajustes dos óculos" else "Ajustes", m.connected == true, a.glasses), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        inner.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        card.addView(inner)
        return card
    }

    /** Floating "ask the AI" bar at the bottom, glowing green outline. */
    private fun askPill(context: Context, ask: () -> Unit): View {
        val d = context.resources.displayMetrics.density
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding((18 * d).toInt(), 0, (18 * d).toInt(), 0)
            background = GradientDrawable().apply { cornerRadius = 100 * d; setColor(0xF0101613.toInt()); setStroke((1.5f * d).toInt(), 0xFF2E8C63.toInt()) }
            elevation = 8 * d
            isClickable = true; setOnClickListener { ask() }
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_sparkle); imageTintList = ColorStateList.valueOf(Palette.PRIMARY) },
                LinearLayout.LayoutParams((22 * d).toInt(), (22 * d).toInt()))
            addView(TextView(context).apply { text = "Perguntar à IA"; textSize = 16f; setTextColor(Palette.TEXT); setPadding((12 * d).toInt(), 0, 0, 0) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_chevron); imageTintList = ColorStateList.valueOf(Palette.MUTED) },
                LinearLayout.LayoutParams((18 * d).toInt(), (18 * d).toInt()))
        }
    }
}
