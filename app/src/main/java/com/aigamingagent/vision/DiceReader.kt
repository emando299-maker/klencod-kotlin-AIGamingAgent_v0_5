package com.aigamingagent.vision

import kotlin.math.max
import kotlin.math.min

/**
 * Reads MY dice plate (bottom-left, next to my avatar) by counting round black pips on the white die.
 * When the plate shows the crown icon instead of a die, no round pips exist and null is returned.
 * Verified on real screenshots: 2 -> 2, 6 -> 6, crown -> null.
 */
object DiceReader {
    const val PLATE_COL = 4.66
    const val PLATE_ROW = 17.91
    private const val DARK = 110

    fun read(gray: IntArray, w: Int, h: Int, geo: BoardGeometry): Int? {
        val (px, py) = geo.pointAt(PLATE_COL, PLATE_ROW)
        val half = (0.8 * geo.cell).toInt()
        val x0 = px.toInt() - half
        val y0 = py.toInt() - half
        val side = 2 * half
        if (side <= 0 || x0 < 0 || y0 < 0 || x0 + side > w || y0 + side > h) return null

        val dark = BooleanArray(side * side) { gray[(y0 + it / side) * w + x0 + it % side] < DARK }
        val seen = BooleanArray(side * side)
        val stack = IntArray(side * side)
        var pips = 0
        val minSide = 0.3 * geo.cell
        val maxSide = 0.7 * geo.cell
        for (start in dark.indices) {
            if (!dark[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var area = 0
            var minX = Int.MAX_VALUE; var maxX = -1; var minY = Int.MAX_VALUE; var maxY = -1
            while (sp > 0) {
                val p = stack[--sp]
                val x = p % side
                val y = p / side
                area++
                minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
                if (x > 0 && dark[p - 1] && !seen[p - 1]) { seen[p - 1] = true; stack[sp++] = p - 1 }
                if (x < side - 1 && dark[p + 1] && !seen[p + 1]) { seen[p + 1] = true; stack[sp++] = p + 1 }
                if (y > 0 && dark[p - side] && !seen[p - side]) { seen[p - side] = true; stack[sp++] = p - side }
                if (y < side - 1 && dark[p + side] && !seen[p + side]) { seen[p + side] = true; stack[sp++] = p + side }
            }
            if (area < 4) continue
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            val fill = area.toDouble() / (bw * bh)
            val aspect = max(bw, bh).toDouble() / min(bw, bh)
            val round = min(bw, bh) >= minSide && max(bw, bh) <= maxSide && fill >= 0.65 && aspect <= 1.3
            if (round) pips++
        }
        return if (pips in 1..6) pips else null
    }
}

/** My turn is shown by a green countdown pie covering my avatar (bottom-left). Absent on the opponent's turn. */
object TurnReader {
    const val AVATAR_COL = 1.75
    const val AVATAR_ROW = 17.55
    private const val MIN_GREEN = 0.15

    fun myTurn(cls: ByteArray, w: Int, h: Int, geo: BoardGeometry): Boolean {
        val (px, py) = geo.pointAt(AVATAR_COL, AVATAR_ROW)
        val cx = px.toInt()
        val cy = py.toInt()
        val r = (0.75 * geo.cell).toInt()
        if (cx - r < 0 || cy - r < 0 || cx + r >= w || cy + r >= h) return false
        var total = 0
        var green = 0
        for (y in cy - r..cy + r) for (x in cx - r..cx + r) {
            val dx = x - cx
            val dy = y - cy
            if (dx * dx + dy * dy <= r * r) {
                total++
                if (cls[y * w + x].toInt() == ColorClasses.GREEN) green++
            }
        }
        return total > 0 && green.toDouble() / total >= MIN_GREEN
    }
}
