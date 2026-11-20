package com.parques.client

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.Button

/**
 * Paleta clara/oscura (los mismos colores que la web). `dark` se decide al abrir la app:
 * ajuste guardado por el usuario («light»/«dark») o, si no hay, el tema del teléfono.
 */
object Theme {
    var dark = false

    private fun pick(light: String, night: String): Int = Color.parseColor(if (dark) night else light)

    val bg: Int get() = pick("#EEF1F7", "#0D1320")
    val surface: Int get() = pick("#FFFFFF", "#161F33")
    val surface2: Int get() = pick("#F3F6FB", "#1D2942")
    val text: Int get() = pick("#1B2433", "#E8EDF6")
    val muted: Int get() = pick("#5D6B82", "#9AA7BF")
    val border: Int get() = pick("#D8DFEB", "#2B3955")
    val accent: Int get() = pick("#2563EB", "#5B8CFF")
    val accentText: Int get() = pick("#FFFFFF", "#0B1020")
    val good: Int get() = pick("#0F8A3C", "#3DDC84")
    val bad: Int get() = pick("#D32F2F", "#FF6B6B")
    val chip: Int get() = pick("#E8EDF6", "#243252")
    val cloud: Int get() = pick("#FFFFFF", "#26334F")
    val cloudMe: Int get() = pick("#DBE7FF", "#2F4A86")

    /** Tablero: fondo, casilla, borde, casilla especial, centro. */
    val boardBg: Int get() = pick("#E6ECF7", "#1B2742")
    val boardCell: Int get() = pick("#FFFFFF", "#E9EEF8")
    val boardStroke: Int get() = pick("#8794AD", "#5A6886")
    val boardSpecial: Int get() = pick("#475569", "#6B7A96")

    /** Decide el tema: ajuste guardado ("dark"/"light") o el del teléfono. */
    fun init(context: Context, saved: String) {
        dark = when (saved) {
            "dark" -> true
            "light" -> false
            else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        }
    }

    /** Mezcla `c` con `base` (frac = cuánto pesa `c`), para fondos pastel de las burbujas de los bots. */
    fun mix(c: Int, base: Int, frac: Float): Int {
        val r = (Color.red(c) * frac + Color.red(base) * (1f - frac)).toInt()
        val g = (Color.green(c) * frac + Color.green(base) * (1f - frac)).toInt()
        val b = (Color.blue(c) * frac + Color.blue(base) * (1f - frac)).toInt()
        return Color.rgb(r, g, b)
    }
}

/** Botón con esquinas redondeadas; deshabilitado se ve atenuado. */
class PillButton(context: Context) : Button(context) {
    init {
        isAllCaps = false
        stateListAnimator = null
        minHeight = 0
        minimumHeight = 0
        setPadding(dpPx(14), dpPx(9), dpPx(14), dpPx(9))
        style(Theme.chip, Theme.text)
    }

    private fun dpPx(v: Int) = (v * resources.displayMetrics.density).toInt()

    fun style(fill: Int, textColor: Int, radiusDp: Int = 12, stroke: Int? = null) {
        background = GradientDrawable().apply {
            cornerRadius = radiusDp * resources.displayMetrics.density
            setColor(fill)
            if (stroke != null) setStroke((2 * resources.displayMetrics.density).toInt(), stroke)
        }
        setTextColor(textColor)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.45f
    }
}
