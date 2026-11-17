package com.parques.client

import org.json.JSONArray
import org.json.JSONObject

/**
 * Tablero en cruz de la Figura 1 (misma geometría que common/board.py y web/index.html).
 * Lienzo de SIZE x SIZE unidades; BoardView lo escala a la pantalla.
 */
object Board {
    /** Orden antihorario: arriba, izquierda, abajo, derecha. */
    val COLORS = listOf("red", "yellow", "blue", "green")
    val HEX = mapOf("red" to "#e60000", "green" to "#0a7d0a", "blue" to "#0a0ae6", "yellow" to "#ffe600")
    val ES = mapOf("red" to "Rojo", "green" to "Verde", "blue" to "Azul", "yellow" to "Amarillo")

    const val RING = 68
    const val SEGMENT = 17
    const val JAIL = -1
    const val LAST = 63      // último paso del anillo (la punta del propio brazo)
    const val GOAL = 71      // 64..70 es el pasillo de 7 casillas
    private const val START_K = 10

    private val geo = JSONObject(BoardGeometry.JSON)

    val SIZE: Float = geo.getDouble("size").toFloat()
    val JAIL_RADIUS: Float = geo.getDouble("jailRadius").toFloat()

    /** Círculo central: x, y, radio. */
    val CENTER: List<Float> = geo.getJSONArray("center").let { a -> (0 until 3).map { a.getDouble(it).toFloat() } }

    private fun point(a: JSONArray) = Pair(a.getDouble(0).toFloat(), a.getDouble(1).toFloat())

    private fun flat(a: JSONArray): FloatArray {
        val out = FloatArray(a.length() * 2)
        for (i in 0 until a.length()) {
            val p = a.getJSONArray(i)
            out[2 * i] = p.getDouble(0).toFloat()
            out[2 * i + 1] = p.getDouble(1).toFloat()
        }
        return out
    }

    /** Polígonos (x0,y0,x1,y1,...) de las 68 casillas del anillo. */
    val ringPolys: List<FloatArray> = geo.getJSONArray("ring").let { a ->
        (0 until a.length()).map { flat(a.getJSONObject(it).getJSONArray("p")) }
    }
    val ringCenters: List<Pair<Float, Float>> = geo.getJSONArray("ring").let { a ->
        (0 until a.length()).map { point(a.getJSONObject(it).getJSONArray("c")) }
    }

    /** lanePolys[color][j-1]: casilla j (1..7) del pasillo. */
    val lanePolys: List<List<FloatArray>> = geo.getJSONArray("lane").let { a ->
        (0 until a.length()).map { i ->
            val l = a.getJSONArray(i)
            (0 until l.length()).map { flat(l.getJSONObject(it).getJSONArray("p")) }
        }
    }
    val laneCenters: List<List<Pair<Float, Float>>> = geo.getJSONArray("lane").let { a ->
        (0 until a.length()).map { i ->
            val l = a.getJSONArray(i)
            (0 until l.length()).map { point(l.getJSONObject(it).getJSONArray("c")) }
        }
    }

    /** Centros de las 4 fichas en la cárcel de cada color. */
    val jailCenters: List<List<Pair<Float, Float>>> = geo.getJSONArray("jail").let { a ->
        (0 until a.length()).map { i ->
            val l = a.getJSONArray(i)
            (0 until l.length()).map { point(l.getJSONArray(it)) }
        }
    }

    /** 4 salidas + 8 seguros (la salida también protege). */
    val SALIDA: Set<Int> = geo.getJSONArray("salida").let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() }
    val SEGURO: Set<Int> = geo.getJSONArray("seguro").let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() }
    val SAFE: Set<Int> = SALIDA + SEGURO

    fun startIndex(color: String) = SEGMENT * COLORS.indexOf(color) + START_K

    fun absIndex(color: String, p: Int): Int? =
        if (p in 0..LAST) (startIndex(color) + p) % RING else null

    /** Centro (en unidades del lienzo) donde se dibuja una ficha. */
    fun cellOf(color: String, p: Int, slot: Int): Pair<Float, Float> {
        val i = COLORS.indexOf(color)
        return when {
            p == JAIL -> jailCenters[i][slot]
            p <= LAST -> ringCenters[absIndex(color, p)!!]
            p < GOAL -> laneCenters[i][p - LAST - 1]
            else -> Pair(SIZE / 2 + (slot - 1.5f) * 40f, SIZE / 2)
        }
    }
}
