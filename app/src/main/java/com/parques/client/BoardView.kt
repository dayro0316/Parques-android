package com.parques.client

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject
import kotlin.math.hypot

/**
 * Dibuja el tablero de la Figura 1 a partir del último `state` del servidor.
 * Todo se dibuja en las unidades del lienzo (Board.SIZE) y se escala a la pantalla.
 */
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
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private fun toPath(pts: FloatArray): Path {
        val p = Path()
        p.moveTo(pts[0], pts[1])
        var i = 2
        while (i < pts.size) {
            p.lineTo(pts[i], pts[i + 1])
            i += 2
        }
        p.close()
        return p
    }

    private val ringPaths: List<Path> by lazy { Board.ringPolys.map { toPath(it) } }
    private val lanePaths: List<List<Path>> by lazy { Board.lanePolys.map { l -> l.map { toPath(it) } } }

    /** Píxeles por unidad del lienzo. */
    private var k = 1f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(s, s)
    }

    private fun polygon(canvas: Canvas, path: Path, color: String) {
        fill.color = Color.parseColor(color)
        canvas.drawPath(path, fill)
        stroke.color = Color.parseColor("#555555")
        stroke.strokeWidth = 1.5f / k
        canvas.drawPath(path, stroke)
    }

    private fun ring(canvas: Canvas, c: Pair<Float, Float>, r: Float, color: Int, width: Float) {
        stroke.color = color
        stroke.strokeWidth = width / k
        canvas.drawCircle(c.first, c.second, r, stroke)
    }

    override fun onDraw(canvas: Canvas) {
        k = width / Board.SIZE
        canvas.drawColor(Color.parseColor("#fde9a9"))
        canvas.save()
        canvas.scale(k, k)

        // meta (círculo central)
        val (cx, cy, cr) = Board.CENTER
        fill.color = Color.parseColor("#00ffff")
        canvas.drawCircle(cx, cy, cr, fill)
        stroke.color = Color.parseColor("#006064")
        stroke.strokeWidth = 2f / k
        canvas.drawCircle(cx, cy, cr, stroke)

        // anillo: casillas blancas; salidas y seguros en marrón
        for (idx in 0 until Board.RING) {
            val special = if (idx in Board.SALIDA) "SAL" else if (idx in Board.SEGURO) "SEG" else ""
            polygon(canvas, ringPaths[idx], if (special.isEmpty()) "#ffffff" else "#5b2c13")
            if (special.isNotEmpty()) {
                val (x, y) = Board.ringCenters[idx]
                label.color = Color.WHITE
                label.textSize = 17f
                canvas.drawText(special, x, y + 6f, label)
                label.color = Color.BLACK
            }
        }
        // pasillos de color y cárceles
        Board.COLORS.forEachIndexed { i, c ->
            for (path in lanePaths[i]) polygon(canvas, path, Board.HEX.getValue(c))
            for (s in 0..3) ring(canvas, Board.jailCenters[i][s], Board.JAIL_RADIUS, Color.parseColor(Board.HEX.getValue(c)), 3f)
        }

        val st = state
        if (st != null) {
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
                    val off = if (jail) 0f else n * 8f
                    val ox = c.first + off
                    val oy = c.second - off
                    val rad = if (jail) 34f else 21f
                    fill.color = Color.parseColor(Board.HEX.getValue(color))
                    canvas.drawCircle(ox, oy, rad, fill)
                    stroke.color = Color.BLACK
                    stroke.strokeWidth = 3f / k
                    canvas.drawCircle(ox, oy, rad, stroke)
                    label.color = if (color == "yellow") Color.BLACK else Color.WHITE
                    label.textSize = if (jail) 40f else 26f
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
                    val r = if (prog == Board.JAIL) 46f else 30f
                    ring(canvas, Board.cellOf(color, prog, piece), r, Color.parseColor(col), 5f)
                }
            }
        }
        canvas.restore()
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
            if (me != null && k > 0f) {
                val tx = event.x / k
                val ty = event.y / k
                val pieces = me.getJSONArray("pieces")
                for (slot in 0 until pieces.length()) {
                    val (x, y) = Board.cellOf(me.getString("color"), pieces.getInt(slot), slot)
                    if (hypot(tx - x, ty - y) < 48f) {
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
