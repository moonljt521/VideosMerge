package com.moon.videomerger.merger

import com.moon.videomerger.util.VideoMeta
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Collage 模式合并器 —— 一个主窗口 + 若干副窗口，严格无空隙铺满画布。
 *
 * 核心逻辑移植自 collage_merge.py，包含 logo 截断，简化掉人脸检测。
 * cover 模式下使用正中裁剪（人脸检测需要 OpenCV，在 Android 上可选集成）。
 */
class CollageMerger {

    fun buildCommand(
        inputPaths: List<String>,
        durations: List<Double>,
        metas: List<VideoMeta>,
        outputPath: String,
        options: MergeOptions,
        cutTimes: List<Double?> = emptyList()
    ): String {
        val n = inputPaths.size
        val mainIdx = options.collageMainIndex.coerceIn(0, n - 1)
        val gap = options.gap.toEven()

        val canvasW = options.canvasWidth.toAligned16()
        val canvasH = options.canvasHeight.toAligned16()

        val (layoutPair, _) = calcMainSub(
            n, canvasW, canvasH, options.collageMainRatio,
            options.collageOrient, null, gap, mainIdx
        )
        val (sizes, positions) = layoutPair

        val maxDur = durations.maxOrNull() ?: 0.0
        val audioMask = metas.map { it.hasAudio }
        val faceXY = List(n) { 0.5 to 0.5 }  // 正中裁剪

        val (filterComplex, hasAudio) = buildFilter(
            n, sizes, positions, durations, maxDur, audioMask,
            options.collageFit, faceXY, cutTimes
        )

        val cmd = StringBuilder().apply {
            append("-y ")
            inputPaths.forEach { append("-i \"$it\" ") }
            append("-filter_complex \"$filterComplex\" ")
            append("-map \"[vout]\" ")
            append("-t ${maxDur.fmt()} ")
            append("-c:v h264_mediacodec -b:v 8M ")
            if (hasAudio) {
                append("-map \"[aout]\" -c:a aac -b:a 192k ")
            }
            append("\"$outputPath\"")
        }

        return cmd.toString()
    }

    // ---------- 布局算法 ----------

    /**
     * 把 total（偶数）切成 parts 个偶数块，精确铺满，块间留 gap。
     */
    private fun evenDivide(total: Int, parts: Int, gap: Int): List<Int> {
        var t = total - total % 2
        var g = gap - gap % 2
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
     * 返回 sizes[(w,h)], positions[(x,y)], out_w, out_h
     */
    private fun calcMainSub(
        n: Int, canvasW: Int, canvasH: Int, mainRatio: Double,
        orient: String, subCols: Int?, gap: Int, mainIdx: Int
    ): Pair<Pair<List<Pair<Int, Int>>, List<Pair<Int, Int>>>, Pair<Int, Int>> {
        val cw = canvasW.toEven()
        val ch = canvasH.toEven()
        val gp = gap.toEven()
        val subs = n - 1

        val sizes = MutableList<Pair<Int, Int>>(n) { 0 to 0 }
        val positions = MutableList<Pair<Int, Int>>(n) { 0 to 0 }

        if (subs == 0) {
            sizes[0] = cw to ch
            positions[0] = 0 to 0
            return (sizes to positions) to (cw to ch)
        }

        val subIndices = (0 until n).filter { it != mainIdx }

        when (orient) {
            "left", "right" -> {
                var mainW = (cw * mainRatio).roundToInt().toEven()
                val subW = cw - mainW

                val sc = subCols ?: if (subs >= 6) 2 else 1
                val subRows = ceil(subs.toDouble() / sc).toInt()
                val rowHs = evenDivide(ch, subRows, gp)

                val mainX = if (orient == "left") 0 else subW
                sizes[mainIdx] = mainW to ch
                positions[mainIdx] = mainX to 0

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
                var mainH = (ch * mainRatio).roundToInt().toEven()
                val subH = ch - mainH

                val sr = subCols ?: if (subs >= 6) 2 else 1
                val subColsAuto = ceil(subs.toDouble() / sr).toInt()
                val colWs = evenDivide(cw, subColsAuto, gp)

                val mainY = if (orient == "top") 0 else subH
                sizes[mainIdx] = cw to mainH
                positions[mainIdx] = 0 to mainY

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

        return (sizes to positions) to (cw to ch)
    }

    // ---------- filter 构建 ----------

    private fun scaleFill(cw: Int, ch: Int, fit: String, fx: Double = 0.5, fy: Double = 0.5): String {
        return if (fit == "contain") {
            "scale=${cw}:${ch}:force_original_aspect_ratio=decrease," +
            "pad=${cw}:${ch}:(ow-iw)/2:(oh-ih)/2:black,fps=30,setsar=1,format=yuv420p"
        } else {
            // cover: crop 偏移
            "scale=${cw}:${ch}:force_original_aspect_ratio=increase," +
            "crop=${cw}:${ch}:${fx.fmt(4)}*(in_w-out_w):${fy.fmt(4)}*(in_h-out_h),fps=30,setsar=1,format=yuv420p"
        }
    }

    private fun buildFilter(
        n: Int,
        sizes: List<Pair<Int, Int>>,
        positions: List<Pair<Int, Int>>,
        durations: List<Double>,
        maxDur: Double,
        audioMask: List<Boolean>,
        fit: String,
        faceXY: List<Pair<Double, Double>>,
        cutTimes: List<Double?>
    ): Pair<String, Boolean> {
        val scaled = mutableListOf<String>()

        for (i in 0 until n) {
            val (cw, ch) = sizes[i]
            val (fx, fy) = faceXY[i]
            val padDur = (maxDur - durations[i]).coerceAtLeast(0.0)
            val trimPfx = if (i < cutTimes.size && cutTimes[i] != null) "trim=end=${cutTimes[i]!!.fmt(3)},setpts=PTS-STARTPTS," else ""
            val common = scaleFill(cw, ch, fit, fx, fy) + "[v$i]"

            if (padDur <= 0.001) {
                scaled.add("[$i:v]$trimPfx$common")
            } else {
                scaled.add(
                    "[$i:v]$trimPfx" + "split=2[${i}A][${i}B];" +
                    "[${i}A]trim=end_frame=1,loop=loop=-1:size=1,setpts=PTS-STARTPTS[${i}Fof];" +
                    "[${i}B]setpts=PTS-STARTPTS[${i}M];" +
                    "[${i}M][${i}Fof]concat=n=2:v=1:a=0[${i}C];" +
                    "[${i}C]trim=end=${maxDur.fmt()},setpts=PTS-STARTPTS,$common"
                )
            }
        }

        val layouts = positions.map { "${it.first}_${it.second}" }
        val inputsConcat = (0 until n).joinToString("") { "[v$it]" }
        val layoutStr = layouts.joinToString("|")

        val parts = mutableListOf<String>()
        parts.add(scaled.joinToString(";"))
        parts.add("$inputsConcat xstack=inputs=$n:layout=$layoutStr:fill=black[vout]")

        // 音频
        val audioParts = mutableListOf<String>()
        for (i in 0 until n) {
            if (!audioMask[i]) continue
            val padA = (maxDur - durations[i]).coerceAtLeast(0.0)
            val aTrim = if (i < cutTimes.size && cutTimes[i] != null) "atrim=end=${cutTimes[i]!!.fmt(3)},asetpts=PTS-STARTPTS," else ""
            val apad = if (padA > 0) ",apad=whole_dur=${maxDur.fmt()}" else ""
            audioParts.add("[$i:a]${aTrim}aresample=44100$apad[a$i]")
        }

        if (audioParts.isNotEmpty()) {
            val amixIn = audioMask.indices.filter { audioMask[it] }.joinToString("") { "[a$it]" }
            parts.add(audioParts.joinToString(";"))
            parts.add("$amixIn amix=inputs=${audioParts.size}:duration=first:normalize=0[aout]")
        }

        return parts.joinToString(";") to audioParts.isNotEmpty()
    }
}
