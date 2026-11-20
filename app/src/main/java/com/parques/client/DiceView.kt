package com.parques.client

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator
import java.util.Random

/**
 * Dos dados dibujados con sus puntos. `roll` los sacude unos 0,6 s y los deja en el valor real;
 * `show` los pone directamente. El dado cuyo valor ya se usó en una jugada se ve atenuado.
 */
class DiceView(context: Context) : View(context) {

    private val values = intArrayOf(1, 1)
    private val used = booleanArrayOf(false, false)
    private val bounce = floatArrayOf(0f, 0f)
    private val rot = floatArrayOf(0f, 0f)
    private var rolling = false
    private var landScale = 1f
    private var latestPending: List<Int> = emptyList()
    private var finalValues = intArrayOf(1, 1)

    private val handler = Handler(Looper.getMainLooper())
    private val rnd = Random()
    private var landAnim: ValueAnimator? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(dp(200f).toInt(), widthMeasureSpec), dp(84f).toInt())
    }

    // ---------------------------------------------------------------- API
    fun roll(a: Int, b: Int, pending: List<Int>) {
        handler.removeCallbacksAndMessages(null)
        landAnim?.cancel()
        landScale = 1f
        finalValues = intArrayOf(a, b)
        latestPending = pending
        used[0] = false
        used[1] = false
        rolling = true
        tick(0)
    }

    fun show(a: Int, b: Int, pending: List<Int>) {
        latestPending = pending
        if (rolling) return            // al aterrizar se aplican los valores y el "usado" más recientes
        values[0] = a
        values[1] = b
        applyUsed()
        invalidate()
    }

    // ---------------------------------------------------------------- animación
    private fun tick(n: Int) {
        if (n >= 7) {
            land()
            return
        }
        for (i in 0..1) {
            values[i] = 1 + rnd.nextInt(6)
            bounce[i] = -dp(10f) * rnd.nextFloat()
            rot[i] = (rnd.nextFloat() - 0.5f) * 50f
        }
        invalidate()
        handler.postDelayed({ tick(n + 1) }, 90)
    }

    private fun land() {
        rolling = false
        for (i in 0..1) {
            values[i] = finalValues[i]
            bounce[i] = 0f
            rot[i] = 0f
        }
        applyUsed()
        landAnim = ValueAnimator.ofFloat(1.25f, 1f).apply {
            duration = 350
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                landScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun applyUsed() {
        val left = ArrayList(latestPending)
        for (i in 0..1) {
            val k = left.indexOf(values[i])
            used[i] = k < 0
            if (k >= 0) left.removeAt(k)
        }
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        landAnim?.cancel()
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------------- dibujo
    private val pips = arrayOf(
        intArrayOf(), intArrayOf(4), intArrayOf(0, 8), intArrayOf(0, 4, 8),
        intArrayOf(0, 2, 6, 8), intArrayOf(0, 2, 4, 6, 8), intArrayOf(0, 2, 3, 5, 6, 8)
    )

    override fun onDraw(canvas: Canvas) {
        val d = dp(56f)
        val gap = dp(14f)
        val x0 = (width - (2 * d + gap)) / 2f
        for (i in 0..1) {
            val cx = x0 + d / 2f + i * (d + gap)
            val cy = height / 2f + bounce[i]
            val dim = !rolling && used[i]
            val sc = (if (dim) 0.88f else 1f) * (if (rolling) 1f else landScale)
            val alpha = if (dim) 90 else 255
            canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(rot[i])
            canvas.scale(sc, sc)
            val h = d / 2f
            val r = dp(12f)
            // sombra
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(alpha / 4, 0, 0, 0)
            rect.set(-h + dp(2f), -h + dp(4f), h + dp(2f), h + dp(4f))
            canvas.drawRoundRect(rect, r, r, paint)
            // cara
            paint.color = if (Theme.dark) Color.argb(alpha, 233, 238, 248) else Color.argb(alpha, 255, 255, 255)
            rect.set(-h, -h, h, h)
            canvas.drawRoundRect(rect, r, r, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1f)
            paint.color = Color.argb(alpha, 135, 148, 173)
            canvas.drawRoundRect(rect, r, r, paint)
            // puntos
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(alpha, 24, 24, 24)
            val step = d * 0.27f
            for (cell in pips[values[i].coerceIn(1, 6)]) {
                canvas.drawCircle((cell % 3 - 1) * step, (cell / 3 - 1) * step, d * 0.075f, paint)
            }
            canvas.restore()
        }
    }
}
