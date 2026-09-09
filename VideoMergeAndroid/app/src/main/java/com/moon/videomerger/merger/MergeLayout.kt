package com.moon.videomerger.merger

import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 布局矩形，坐标基于输出画布像素（左上角原点）。
 */
data class LayoutRect(val x: Int, val y: Int, val w: Int, val h: Int)

/**
 * 合并布局计算 —— **预览与导出共用同一套算法**，保证「所见即所得」。
 *
 * 原先 GridMerger / CollageMerger / PhotoWallMerger 各自私有实现布局，
 * 预览若另写一份极易与导出结果漂移。这里统一抽出纯计算（不碰 ffmpeg），
 * 合并器与 UI 预览都调用它；单元测试只需锁定这一处。
 */
object MergeLayout {

    /** 网格布局规格 */
    data class GridSpec(
        val rows: Int,
        val cols: Int,
        val cellW: Int,
        val cellH: Int
    ) {
        val outW: Int get() = cellW * cols
        val outH: Int get() = cellH * rows

        /** 第 i 个视频的格子矩形（行优先） */
        fun rects(): List<LayoutRect> = List(rows * cols) { idx ->
            val r = idx / cols
            val c = idx % cols
            LayoutRect(c * cellW, r * cellH, cellW, cellH)
        }
    }

    /** 一主多副布局：每个视频的尺寸与位置 */
    data class CollageLayout(
        val sizes: List<Pair<Int, Int>>,
        val positions: List<Pair<Int, Int>>,
        val outW: Int,
        val outH: Int
    ) {
        fun rects(): List<LayoutRect> = sizes.indices.map { i ->
            val (w, h) = sizes[i]
            val (x, y) = positions[i]
            LayoutRect(x, y, w, h)
        }
    }

    /** 照片墙布局：矩形 + 旋转角（与导出同一次随机序列） */
    data class PhotoWallLayout(
        val rects: List<LayoutRect>,
        val rotations: List<Double>,
        val outW: Int,
        val outH: Int
    )

    // ---------- Grid ----------

    fun calcGrid(n: Int): Pair<Int, Int> {
        val cols = ceil(sqrt(n.toDouble())).toInt()
        val rows = ceil(n.toDouble() / cols).toInt()
        return rows to cols
    }

    /** 取出现次数最多的宽高比（按 3 位小数归并） */
    fun dominantAspect(aspects: List<Double>): Double {
        if (aspects.isEmpty()) return 16.0 / 9.0
        val counts = aspects.groupingBy { "%.3f".format(it) }.eachCount()
        return counts.maxByOrNull { it.value }?.key?.toDouble() ?: aspects[0]
    }

    fun cellSizeFromAspect(aspect: Double, target: Int): Pair<Int, Int> {
        return if (aspect >= 1) {
            target to (target / aspect).toInt()
        } else {
            (target * aspect).toInt() to target
        }
    }

    fun gridSpec(n: Int, aspects: List<Double>, gridCellSize: Int): GridSpec {
        val (rows, cols) = calcGrid(n)
        val aspect = dominantAspect(aspects)
        val (rawW, rawH) = cellSizeFromAspect(aspect, gridCellSize)
        return GridSpec(rows, cols, rawW.toAligned16(), rawH.toAligned16())
    }

    // ---------- Collage ----------

    /**
     * 把 total（偶数）切成 parts 个偶数块，精确铺满，块间留 gap。
     */
    fun evenDivide(total: Int, parts: Int, gap: Int): List<Int> {
        val t = total - total % 2
        val g = gap - gap % 2
        var avail = t - (parts - 1) * g
        if (avail < parts * 2) avail = parts * 2
        var base = (avail / parts) / 2 * 2
        if (base < 2) base = 2
        val sizes = MutableList(parts) { base }
        var rem = avail - sizes.sum()
        var i = parts - 1
        while (rem >= 2 && i >= 0) {
            sizes[i] += 2
            rem -= 2
            i--
        }
        return sizes
    }

    /**
     * 一主多副布局，严格无空隙。
     */
    fun collageLayout(
        n: Int,
        canvasW: Int,
        canvasH: Int,
        mainRatio: Double,
        orient: String,
        gap: Int,
        mainIdx: Int,
        subCols: Int? = null
    ): CollageLayout {
        val cw = canvasW.toEven()
        val ch = canvasH.toEven()
        val gp = gap.toEven()
        val subs = n - 1
        val safeMainIdx = mainIdx.coerceIn(0, (n - 1).coerceAtLeast(0))

        val sizes = MutableList(n) { 0 to 0 }
        val positions = MutableList(n) { 0 to 0 }

        if (subs <= 0) {
            sizes[0] = cw to ch
            positions[0] = 0 to 0
            return CollageLayout(sizes, positions, cw, ch)
        }

        val subIndices = (0 until n).filter { it != safeMainIdx }

        when (orient) {
            "left", "right" -> {
                val mainW = (cw * mainRatio).roundToInt().toEven()
                val subW = cw - mainW

                val sc = subCols ?: if (subs >= 6) 2 else 1
                val subRows = ceil(subs.toDouble() / sc).toInt()
                val rowHs = evenDivide(ch, subRows, gp)

                val mainX = if (orient == "left") 0 else subW
                sizes[safeMainIdx] = mainW to ch
                positions[safeMainIdx] = mainX to 0

                var si = 0
                var y = 0
                for (r in 0 until subRows) {
                    val remaining = subs - si
                    val colsThis = minOf(sc, remaining)
                    val colWs = evenDivide(subW, colsThis, gp)
                    val xBase = if (orient == "left") mainW else 0
                    var x = xBase
                    for (c in 0 until colsThis) {
                        val w = colWs[c]
                        val h = rowHs[r]
                        val idx = subIndices[si]
                        sizes[idx] = w to h
                        positions[idx] = x to y
                        x += w + gp
                        si++
                    }
                    y += rowHs[r] + gp
                }
            }

            else -> { // top / bottom
                val mainH = (ch * mainRatio).roundToInt().toEven()
                val subH = ch - mainH

                val sr = subCols ?: if (subs >= 6) 2 else 1
                val subColsAuto = ceil(subs.toDouble() / sr).toInt()
                val colWs = evenDivide(cw, subColsAuto, gp)

                val mainY = if (orient == "top") 0 else subH
                sizes[safeMainIdx] = cw to mainH
                positions[safeMainIdx] = 0 to mainY

                var si = 0
                var x = 0
                for (c in 0 until subColsAuto) {
                    val remaining = subs - si
                    val rowsThis = minOf(sr, remaining)
                    val rowHs = evenDivide(subH, rowsThis, gp)
                    val yBase = if (orient == "top") mainH else 0
                    var y = yBase
                    for (r in 0 until rowsThis) {
                        val w = colWs[c]
                        val h = rowHs[r]
                        val idx = subIndices[si]
                        sizes[idx] = w to h
                        positions[idx] = x to y
                        y += h + gp
                        si++
                    }
                    x += colWs[c] + gp
                }
            }
        }

        return CollageLayout(sizes, positions, cw, ch)
    }

    // ---------- Photo Wall ----------

    private const val PADDING_GAP = 18
    private const val ROTATION_MAX = 3.5

    /**
     * 照片墙布局。seed 为 null 时沿用随机布局（导出仍然可用），
     * 但 UI 预览应始终传入固定 seed，才能与导出结果一致。
     */
    fun photoWallLayout(n: Int, canvasW: Int, canvasH: Int, seed: Int?): PhotoWallLayout {
        val rng = seed?.let { Random(it) } ?: Random.Default
        val layout = treemapLayout(canvasW, canvasH, n, rng)
        val jittered = addWallJitter(layout, canvasW, canvasH, rng)
        val rotations = (0 until n).map { roundTo1dp(rng.nextDouble(-ROTATION_MAX, ROTATION_MAX)) }
        return PhotoWallLayout(jittered, rotations, canvasW, canvasH)
    }

    private fun treemapLayout(canvasW: Int, canvasH: Int, n: Int, rng: Random): List<LayoutRect> {
        // 权重：前 1/3 大块，其余小块
        val weights = (0 until n).map { i ->
            if (i < maxOf(1, n / 3)) rng.nextDouble(1.8, 3.0)
            else rng.nextDouble(0.8, 1.5)
        }

        val result = MutableList<LayoutRect?>(n) { null }
        val shuffledIndices = (0 until n).toMutableList().also { it.shuffle(rng) }

        fun slice(indices: List<Int>, x: Int, y: Int, w: Int, h: Int, horizontal: Boolean) {
            if (indices.isEmpty()) return
            if (indices.size == 1) {
                val idx = indices[0]
                var pw = maxOf(w - 2 * PADDING_GAP, 100)
                var ph = maxOf(h - 2 * PADDING_GAP, 100)
                pw = pw.toEven()
                ph = ph.toEven()
                val px = (x + (w - pw) / 2 + rng.nextDouble(-3.0, 3.0)).toInt()
                val py = (y + (h - ph) / 2 + rng.nextDouble(-3.0, 3.0)).toInt()
                result[idx] = LayoutRect(px, py, pw, ph)
                return
            }

            val subWeights = indices.map { weights[it] }
            val subTotal = subWeights.sum()

            // 找接近一半的切分点
            var acc = 0.0
            var split = 1
            for (k in 0 until subWeights.size - 1) {
                acc += subWeights[k]
                if (acc >= subTotal / 2) {
                    split = k + 1
                    break
                }
            }

            val leftIndices = indices.subList(0, split)
            val rightIndices = indices.subList(split, indices.size)
            val leftRatio = leftIndices.sumOf { weights[it] } / subTotal

            // 防止递归过深
            if (w < 2 * PADDING_GAP + 150 || h < 2 * PADDING_GAP + 150) {
                val cols = maxOf(1, sqrt(indices.size.toDouble()).toInt())
                val rows = (indices.size + cols - 1) / cols
                var cellW = maxOf(50, (w - (cols - 1) * PADDING_GAP) / cols)
                var cellH = maxOf(50, (h - (rows - 1) * PADDING_GAP) / rows)
                cellW = cellW.toEven()
                cellH = cellH.toEven()
                for ((idxI, idx) in indices.withIndex()) {
                    val c = idxI % cols
                    val r = idxI / cols
                    val px = x + c * (cellW + PADDING_GAP)
                    val py = y + r * (cellH + PADDING_GAP)
                    result[idx] = LayoutRect(px, py, cellW, cellH)
                }
                return
            }

            if (horizontal) {
                val lw = maxOf(1, (w * leftRatio).toInt())
                slice(leftIndices, x, y, lw, h, !horizontal)
                slice(rightIndices, x + lw, y, w - lw, h, !horizontal)
            } else {
                val lh = maxOf(1, (h * leftRatio).toInt())
                slice(leftIndices, x, y, w, lh, !horizontal)
                slice(rightIndices, x, y + lh, w, h - lh, !horizontal)
            }
        }

        slice(shuffledIndices, 0, 0, canvasW, canvasH, horizontal = true)
        return result.map { it!! }
    }

    private fun addWallJitter(
        layout: List<LayoutRect>,
        canvasW: Int,
        canvasH: Int,
        rng: Random
    ): List<LayoutRect> {
        return layout.map { (x, y, w, h) ->
            val dx = rng.nextInt(-6, 7)
            val dy = rng.nextInt(-6, 7)
            LayoutRect(
                maxOf(0, minOf(canvasW - w, x + dx)),
                maxOf(0, minOf(canvasH - h, y + dy)),
                w, h
            )
        }
    }

    private fun roundTo1dp(v: Double): Double = (v * 10).roundToInt() / 10.0
}
