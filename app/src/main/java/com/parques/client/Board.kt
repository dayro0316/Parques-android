package com.parques.client

/** Geometría del tablero en cruz (misma que common/board.py y web/index.html). */
object Board {
    /** Orden antihorario: arriba, izquierda, abajo, derecha. */
    val COLORS = listOf("red", "yellow", "blue", "green")
    val HEX = mapOf("red" to "#e60000", "green" to "#0a7d0a", "blue" to "#0a0ae6", "yellow" to "#ffe600")
    val ES = mapOf("red" to "Rojo", "green" to "Verde", "blue" to "Azul", "yellow" to "Amarillo")

    const val RING = 68
    const val SEGMENT = 17
    const val GRID = 19
    const val JAIL = -1
    const val LAST = 60
    const val GOAL = 69
    private const val START_K = 16
    private val SAFE_K = listOf(0, 8, 12)

    /** 4 salidas + 12 seguros. */
    val SALIDA: Set<Int> = (0..3).map { SEGMENT * it + START_K }.toSet()
    val SEGURO: Set<Int> = (0..3).flatMap { i -> SAFE_K.map { SEGMENT * i + it } }.toSet()
    val SAFE: Set<Int> = SALIDA + SEGURO

    fun startIndex(color: String) = SEGMENT * COLORS.indexOf(color) + START_K

    fun absIndex(color: String, p: Int): Int? =
        if (p in 0..LAST) (startIndex(color) + p) % RING else null

    private fun local(k: Int): Pair<Int, Int> = when {
        k <= 7 -> Pair(10, 7 - k)
        k == 8 -> Pair(9, 0)
        else -> Pair(8, k - 9)
    }

    private fun rot(i: Int, x: Int, y: Int): Pair<Float, Float> {
        val n = GRID - 1
        val r = when (i) {
            0 -> Pair(x, y)
            1 -> Pair(y, n - x)
            2 -> Pair(n - x, n - y)
            else -> Pair(n - y, x)
        }
        return Pair(r.first.toFloat(), r.second.toFloat())
    }

    fun ringCell(idx: Int): Pair<Float, Float> {
        val (x, y) = local(idx % SEGMENT)
        return rot(idx / SEGMENT, x, y)
    }

    fun laneCell(i: Int, j: Int): Pair<Float, Float> = rot(i, 9, j)

    private val JAIL_OFFSETS = listOf(2 to 2, 5 to 2, 2 to 5, 5 to 5)

    fun jailCell(i: Int, slot: Int): Pair<Float, Float> {
        val (x, y) = JAIL_OFFSETS[slot]
        val n = GRID - 1
        val c = when (i) {
            0 -> Pair(x, y)
            1 -> Pair(x, n - y)
            2 -> Pair(n - x, n - y)
            else -> Pair(n - x, y)
        }
        return Pair(c.first.toFloat(), c.second.toFloat())
    }

    fun cellOf(color: String, p: Int, slot: Int): Pair<Float, Float> {
        val i = COLORS.indexOf(color)
        return when {
            p == JAIL -> jailCell(i, slot)
            p <= LAST -> ringCell(absIndex(color, p)!!)
            p < GOAL -> laneCell(i, p - LAST)
            else -> Pair(9f + (slot - 1.5f) * 0.45f, 9f)
        }
    }
}
