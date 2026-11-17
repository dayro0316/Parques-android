package com.parques.client

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject
import kotlin.math.hypot

/** Dibuja el tablero a partir del último `state` del servidor. Toda la lógica vive en el servidor. */
class BoardView(context: Context) : View(context) {

    var state: JSONObject? = null
        set(value) { field = value; invalidate() }

    /** Jugada resaltada (origen naranja, destino verde). */
    var selected: JSONObject? = null
        set(value) { field = value; invalidate() }

    var user: String = ""
    var onPieceTap: ((Int) -> Unit)? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#555555")
        strokeWidth = 1.5f
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private var cell = 1f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(s, s)
    }

    private fun px(c: Pair<Float, Float>) = Pair((c.first + 0.5f) * cell, (c.second + 0.5f) * cell)

    private fun square(canvas: Canvas, c: Pair<Float, Float>, color: String) {
        val (x, y) = px(c)
        val h = cell / 2 - 1
        fill.color = Color.parseColor(color)
        canvas.drawRect(x - h, y - h, x + h, y + h, fill)
        canvas.drawRect(x - h, y - h, x + h, y + h, stroke)
    }

    private fun ring(canvas: Canvas, c: Pair<Float, Float>, r: Float, color: Int, width: Float) {
        val (x, y) = px(c)
        stroke.color = color
        stroke.strokeWidth = width
        canvas.drawCircle(x, y, r, stroke)
        stroke.color = Color.parseColor("#555555")
        stroke.strokeWidth = 1.5f
    }

    override fun onDraw(canvas: Canvas) {
        cell = width / Board.GRID.toFloat()
        canvas.drawColor(Color.parseColor("#fde9a9"))

        // meta (círculo central)
        val (gx, gy) = px(Pair(9f, 9f))
        fill.color = Color.parseColor("#00ffff")
        canvas.drawCircle(gx, gy, cell * 1.55f, fill)

        // anillo: casillas blancas; salidas y seguros en marrón
        for (k in 0 until Board.RING) {
            val special = if (k in Board.SALIDA) "SAL" else if (k in Board.SEGURO) "SEG" else ""
            val c = Board.ringCell(k)
            square(canvas, c, if (special.isEmpty()) "#ffffff" else "#5b2c13")
            if (special.isNotEmpty()) {
                val (x, y) = px(c)
                label.color = Color.WHITE
                label.textSize = cell * 0.26f
                canvas.drawText(special, x, y + cell * 0.09f, label)
                label.color = Color.BLACK
            }
        }
        // pasillos de color y cárceles
        Board.COLORS.forEachIndexed { i, c ->
            for (j in 1..8) square(canvas, Board.laneCell(i, j), Board.HEX.getValue(c))
            for (s in 0..3) ring(canvas, Board.jailCell(i, s), cell * 0.8f, Color.parseColor(Board.HEX.getValue(c)), 3f)
        }

        val st = state ?: return
        val players = st.getJSONArray("players")
        val counts = HashMap<Pair<Float, Float>, Int>()
        for (pi in 0 until players.length()) {
            val p = players.getJSONObject(pi)
            val color = p.getString("color")
            val pieces = p.getJSONArray("pieces")
            for (slot in 0 until pieces.length()) {
                val prog = pieces.getInt(slot)
                val jail = prog == Board.JAIL
                val c = Board.cellOf(color, prog, slot)
                val n = counts.getOrDefault(c, 0)
                counts[c] = n + 1
                val (x, y) = px(c)
                val off = if (jail) 0f else n * cell * 0.15f
                val ox = x + off
                val oy = y - off
                val rad = cell * (if (jail) 0.72f else 0.38f)
                fill.color = Color.parseColor(Board.HEX.getValue(color))
                canvas.drawCircle(ox, oy, rad, fill)
                stroke.strokeWidth = 3f
                canvas.drawCircle(ox, oy, rad, stroke)
                stroke.strokeWidth = 1.5f
                label.color = if (color == "yellow") Color.BLACK else Color.WHITE
                label.textSize = cell * (if (jail) 0.7f else 0.36f)
                canvas.drawText("${slot + 1}", ox, oy + label.textSize * 0.35f, label)
                label.color = Color.BLACK
            }
        }

        val sel = selected
        val me = findPlayer(st)
        if (sel != null && me != null) {
            val color = me.getString("color")
            val piece = sel.getInt("piece")
            for ((prog, col) in listOf(sel.getInt("from") to "#ff6f00", sel.getInt("to") to "#00c853")) {
                val r = if (prog == Board.JAIL) cell * 0.85f else cell * 0.55f
                ring(canvas, Board.cellOf(color, prog, piece), r, Color.parseColor(col), 5f)
            }
        }
    }

    private fun findPlayer(st: JSONObject): JSONObject? {
        val players = st.getJSONArray("players")
        for (i in 0 until players.length()) {
            val p = players.getJSONObject(i)
            if (p.getString("name") == user) return p
        }
        return null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val st = state
            val me = if (st != null) findPlayer(st) else null
            if (me != null) {
                val pieces = me.getJSONArray("pieces")
                for (slot in 0 until pieces.length()) {
                    val (x, y) = px(Board.cellOf(me.getString("color"), pieces.getInt(slot), slot))
                    if (hypot(event.x - x, event.y - y) < cell * 0.6f) {
                        onPieceTap?.invoke(slot)
                        break
                    }
                }
            }
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
