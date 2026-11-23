package com.parques.client

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Dibuja el tablero de la Figura 1 a partir del último `state` del servidor.
 * Todo se dibuja en las unidades del lienzo (Board.SIZE) y se escala a la pantalla.
 * Cuando una ficha cambia de casilla se anima el recorrido; al comer o llegar a la meta hay un destello.
 */
class BoardView(context: Context) : View(context) {

    var state: JSONObject? = null
        set(value) {
            val old = field
            field = value
            if (old != null && value != null) startAnims(old, value)
            invalidate()
        }

    /** Jugada resaltada (origen naranja, destino verde). */
    var selected: JSONObject? = null
        set(value) { field = value; invalidate() }

    /** Fichas que se pueden mover ahora: laten con un aro verde para invitar a tocarlas. */
    var movable: Set<Int> = emptySet()
        set(value) { field = value; invalidate() }

    var user: String = ""
    var onPieceTap: ((Int) -> Unit)? = null

    /** Avisa de lo que pasó en el último cambio de estado: "move", "capture" o "goal" (para los sonidos). */
    var onAnimEvent: ((String) -> Unit)? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val rect = RectF()

    private class Anim(val pts: List<Pair<Float, Float>>, val t0: Long, val dur: Long)
    private class Burst(val x: Float, val y: Float, val t0: Long, val big: Boolean)

    private val anims = HashMap<String, Anim>()
    private val bursts = ArrayList<Burst>()

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
        val w = MeasureSpec.getSize(widthMeasureSpec)
        // cuadrado, pero sin pasar de ~46 % del alto de la pantalla para que quepan las pestañas debajo
        // en pantallas cortas el tablero se achica (hasta 36 %) para dejar sitio a las pestañas y su contenido
        val hDp = resources.displayMetrics.heightPixels / resources.displayMetrics.density
        val frac = (0.36f + (hDp - 640f) / (900f - 640f) * 0.10f).coerceIn(0.36f, 0.46f)
        val maxH = (resources.displayMetrics.heightPixels * frac).toInt()
        val s = min(w, maxH)
        setMeasuredDimension(s, s)
    }

    // ---------------------------------------------------------------- animaciones
    private fun pathFor(color: String, slot: Int, from: Int, to: Int): List<Pair<Float, Float>> {
        if (from == Board.JAIL || to == Board.JAIL || to < from) {
            return listOf(Board.cellOf(color, from, slot), Board.cellOf(color, to, slot))
        }
        val pts = ArrayList<Pair<Float, Float>>()
        for (p in from..to) pts.add(Board.cellOf(color, p, slot))
        return pts
    }

    private fun startAnims(old: JSONObject, cur: JSONObject) {
        val now = SystemClock.uptimeMillis()
        val oldPlayers = old.getJSONArray("players")
        val players = cur.getJSONArray("players")
        var moved = false
        var captured = false
        var goal = false
        for (i in 0 until players.length()) {
            val p = players.getJSONObject(i)
            val name = p.getString("name")
            val color = p.getString("color")
            var before: JSONObject? = null
            for (j in 0 until oldPlayers.length()) {
                val q = oldPlayers.getJSONObject(j)
                if (q.getString("name") == name) before = q
            }
            if (before == null) continue
            val pieces = p.getJSONArray("pieces")
            val oldPieces = before.getJSONArray("pieces")
            for (slot in 0 until pieces.length()) {
                if (slot >= oldPieces.length()) continue
                val prog = pieces.getInt(slot)
                val was = oldPieces.getInt(slot)
                if (prog == was) continue
                val pts = pathFor(color, slot, was, prog)
                val jailMove = was == Board.JAIL || prog == Board.JAIL
                val dur = if (jailMove) 520L else min(1100L, 150L * (pts.size - 1))
                anims["$name:$slot"] = Anim(pts, now, dur)
                moved = true
                if (prog == Board.JAIL && was != Board.JAIL) {
                    captured = true
                    val c = Board.cellOf(color, was, slot)
                    bursts.add(Burst(c.first, c.second, now, false))
                }
                if (prog == Board.GOAL) {
                    goal = true
                    bursts.add(Burst(Board.SIZE / 2f, Board.SIZE / 2f, now, true))
                }
            }
        }
        if (captured) onAnimEvent?.invoke("capture")
        else if (goal) onAnimEvent?.invoke("goal")
        else if (moved) onAnimEvent?.invoke("move")
    }

    private fun ease(u: Float): Float {
        return if (u < 0.5f) 2f * u * u else 1f - (-2f * u + 2f) * (-2f * u + 2f) / 2f
    }

    /** Posición (x, y, escala) de una ficha en movimiento, o null si su animación ya terminó. */
    private fun animPos(a: Anim, now: Long): FloatArray? {
        val u = (now - a.t0).toFloat() / a.dur.toFloat()
        if (u >= 1f) return null
        val seg = ease(u.coerceAtLeast(0f)) * (a.pts.size - 1)
        val i = min(a.pts.size - 2, seg.toInt())
        val f = seg - i
        val p0 = a.pts[i]
        val p1 = a.pts[i + 1]
        val bump = 1f + 0.28f * sin(PI.toFloat() * f)
        return floatArrayOf(p0.first + (p1.first - p0.first) * f, p0.second + (p1.second - p0.second) * f, bump)
    }

    // ---------------------------------------------------------------- dibujo
    private fun polygon(canvas: Canvas, path: Path, color: Int) {
        fill.color = color
        canvas.drawPath(path, fill)
        stroke.color = Theme.boardStroke
        stroke.strokeWidth = 1.5f / k
        canvas.drawPath(path, stroke)
    }

    private fun ring(canvas: Canvas, c: Pair<Float, Float>, r: Float, color: Int, width: Float) {
        stroke.color = color
        stroke.strokeWidth = width / k
        canvas.drawCircle(c.first, c.second, r, stroke)
    }

    private fun drawPiece(canvas: Canvas, x: Float, y: Float, rad: Float, color: String, slot: Int, jail: Boolean) {
        fill.color = Color.argb(70, 0, 0, 0)
        canvas.drawCircle(x + 2f, y + 3f, rad, fill)
        fill.color = Color.parseColor(Board.HEX.getValue(color))
        canvas.drawCircle(x, y, rad, fill)
        stroke.color = Color.parseColor("#101624")
        stroke.strokeWidth = 3f / k
        canvas.drawCircle(x, y, rad, stroke)
        fill.color = Color.argb(90, 255, 255, 255)
        canvas.drawCircle(x - rad * 0.3f, y - rad * 0.32f, rad * 0.38f, fill)
        label.color = if (color == "yellow") Color.BLACK else Color.WHITE
        label.textSize = if (jail) 40f else 26f
        canvas.drawText("${slot + 1}", x, y + label.textSize * 0.35f, label)
        label.color = Color.BLACK
    }

    override fun onDraw(canvas: Canvas) {
        k = width / Board.SIZE
        val now = SystemClock.uptimeMillis()
        val pulse = ((sin(System.currentTimeMillis() / 160.0) + 1.0) / 2.0).toFloat()
        canvas.drawColor(Theme.boardBg)
        canvas.save()
        canvas.scale(k, k)

        // meta (círculo central)
        val (cx, cy, cr) = Board.CENTER
        fill.color = Color.parseColor(if (Theme.dark) "#00BCD4" else "#00E5FF")
        canvas.drawCircle(cx, cy, cr, fill)
        stroke.color = Color.parseColor("#006064")
        stroke.strokeWidth = 2f / k
        canvas.drawCircle(cx, cy, cr, stroke)

        // anillo: casillas claras; salidas y seguros en gris oscuro
        for (idx in 0 until Board.RING) {
            val special = if (idx in Board.SALIDA) "SAL" else if (idx in Board.SEGURO) "SEG" else ""
            polygon(canvas, ringPaths[idx], if (special.isEmpty()) Theme.boardCell else Theme.boardSpecial)
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
            for (path in lanePaths[i]) polygon(canvas, path, Color.parseColor(Board.HEX.getValue(c)))
            for (s in 0..3) ring(canvas, Board.jailCenters[i][s], Board.JAIL_RADIUS, Color.parseColor(Board.HEX.getValue(c)), 3f)
        }

        val st = state
        var animating = anims.isNotEmpty() || bursts.isNotEmpty()
        if (st != null) {
            val players = st.getJSONArray("players")
            val current = if (st.isNull("current")) null else st.getString("current")

            // halo alrededor de la cárcel del jugador en turno
            if (!st.getBoolean("over") && current != null) {
                for (pi in 0 until players.length()) {
                    val p = players.getJSONObject(pi)
                    if (p.getString("name") != current) continue
                    val ci = Board.COLORS.indexOf(p.getString("color"))
                    if (ci < 0) continue
                    var x0 = Float.MAX_VALUE
                    var y0 = Float.MAX_VALUE
                    var x1 = -Float.MAX_VALUE
                    var y1 = -Float.MAX_VALUE
                    for (s in 0..3) {
                        val (jx, jy) = Board.jailCenters[ci][s]
                        x0 = min(x0, jx)
                        y0 = min(y0, jy)
                        if (jx > x1) x1 = jx
                        if (jy > y1) y1 = jy
                    }
                    val pad = Board.JAIL_RADIUS + 16f
                    rect.set(x0 - pad, y0 - pad, x1 + pad, y1 + pad)
                    stroke.color = Color.parseColor(Board.HEX.getValue(p.getString("color")))
                    stroke.alpha = (255 * (0.35f + 0.45f * pulse)).toInt()
                    stroke.strokeWidth = 6f / k
                    canvas.drawRoundRect(rect, 22f, 22f, stroke)
                    stroke.alpha = 255
                }
            }

            // fichas quietas (las que se están moviendo se dibujan al final, encima de todo)
            val counts = HashMap<Pair<Float, Float>, Int>()
            val moving = ArrayList<Array<Any>>()
            for (pi in 0 until players.length()) {
                val p = players.getJSONObject(pi)
                val name = p.getString("name")
                val color = p.getString("color")
                val pieces = p.getJSONArray("pieces")
                for (slot in 0 until pieces.length()) {
                    val prog = pieces.getInt(slot)
                    val key = "$name:$slot"
                    val a = anims[key]
                    if (a != null) {
                        val pos = animPos(a, now)
                        if (pos != null) {
                            moving.add(arrayOf<Any>(color, slot, pos))
                            continue
                        }
                        anims.remove(key)
                    }
                    val jail = prog == Board.JAIL
                    val c = Board.cellOf(color, prog, slot)
                    val n = counts.getOrDefault(c, 0)
                    counts[c] = n + 1
                    val off = if (jail) 0f else n * 8f
                    drawPiece(canvas, c.first + off, c.second - off, if (jail) 34f else 21f, color, slot, jail)
                }
            }

            // resaltes: jugada elegida
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
            // pulso de las fichas que se pueden mover
            if (me != null && movable.isNotEmpty()) {
                val pieces = me.getJSONArray("pieces")
                for (slot in movable) {
                    if (slot < 0 || slot >= pieces.length()) continue
                    val prog = pieces.getInt(slot)
                    val base = if (prog == Board.JAIL) 38f else 25f
                    ring(canvas, Board.cellOf(me.getString("color"), prog, slot), base + pulse * 5f,
                        Color.parseColor("#00c853"), 3f + pulse * 2f)
                }
            }

            // fichas en movimiento
            for (m in moving) {
                val pos = m[2] as FloatArray
                drawPiece(canvas, pos[0], pos[1] - 8f * (pos[2] - 1f) / 0.28f, 21f * pos[2], m[0] as String, m[1] as Int, false)
            }
            animating = animating || moving.isNotEmpty()
            if (!st.getBoolean("over") && current != null) animating = true   // el halo late
        }

        // destellos de captura / llegada
        val bi = bursts.iterator()
        while (bi.hasNext()) {
            val b = bi.next()
            val u = (now - b.t0).toFloat() / (if (b.big) 900f else 600f)
            if (u >= 1f) {
                bi.remove()
                continue
            }
            stroke.alpha = (255 * (1f - u)).toInt()
            stroke.color = Color.parseColor(if (b.big) "#FFD600" else "#FF1744")
            stroke.strokeWidth = (6f * (1f - u) + 1f)
            canvas.drawCircle(b.x, b.y, (if (b.big) 30f else 20f) + u * (if (b.big) 110f else 70f), stroke)
            if (!b.big) {
                stroke.color = Color.WHITE
                stroke.strokeWidth = 3f * (1f - u) + 1f
                canvas.drawCircle(b.x, b.y, 8f + u * 40f, stroke)
            }
            stroke.alpha = 255
        }
        canvas.restore()
        if (animating || movable.isNotEmpty()) postInvalidateOnAnimation()
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
                var best = -1
                var bestDist = Float.MAX_VALUE
                for (slot in 0 until pieces.length()) {
                    val (x, y) = Board.cellOf(me.getString("color"), pieces.getInt(slot), slot)
                    val dist = hypot(tx - x, ty - y)
                    if (dist < 48f && dist < bestDist) {
                        best = slot
                        bestDist = dist
                    }
                }
                if (best >= 0) onPieceTap?.invoke(best)
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
