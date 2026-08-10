package com.moon.videomerger.merger

import com.moon.videomerger.util.VideoMeta
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 照片墙模式合并器 —— 大大小小、错落有致，加白边框和阴影。
 *
 * 核心逻辑移植自 photo_wall_merge.py，包含 logo 截断，简化掉人脸检测（使用偏上裁剪）。
 */
class PhotoWallMerger {

    companion object {
        private const val BORDER_PX = 8
        private const val SHADOW_PX = 12
        private const val ROTATION_MAX = 3.5
        private const val PADDING_GAP = 18
        // 背景色 (BGR -> RGB) = (40,40,45) -> 0x28282d
        private const val BG_R = 0x28
        private const val BG_G = 0x28
        private const val BG_B = 0x2d
    }

    fun buildCommand(
        inputPaths: List<String>,
        durations: List<Double>,
        metas: List<VideoMeta>,
        outputPath: String,
        options: MergeOptions,
        cutTimes: List<Double?> = emptyList()
    ): String {
        val n = inputPaths.size
        val canvasW = options.canvasWidth.toAligned16()
        val canvasH = options.canvasHeight.toAligned16()

        val rng = options.photoWallSeed?.let { Random(it) } ?: Random.Default

        // 1. treemap 布局
        val layout = treemapLayout(canvasW, canvasH, n, rng)
        val jitteredLayout = addWallJitter(layout, canvasW, canvasH, rng)
        val rotations = (0 until n).map { roundTo1dp(rng.nextDouble(-ROTATION_MAX, ROTATION_MAX)) }

        // 2. 计算每个视频的裁剪中心（偏上裁剪，模拟人脸居上）
        val cropCenters = (0 until n).map { i ->
            val (vw, vh) = metas[i].width to metas[i].height
            val (_, _, cellW, cellH) = jitteredLayout[i]
            computeSafeCropCenter(vw, vh, cellW, cellH)
        }

        val maxDur = durations.maxOrNull() ?: 0.0
        val audioMask = metas.map { it.hasAudio }

        // 3. 构建 filter_complex
        val (filterComplex, hasAudio) = buildWallFilter(
            n, jitteredLayout, rotations, cropCenters,
            metas, durations, maxDur, audioMask,
            canvasW, canvasH, cutTimes
        )

        // 4. 构建 ffmpeg 命令
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

    // ---------- treemap 布局 ----------

    private data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)

    private fun treemapLayout(canvasW: Int, canvasH: Int, n: Int, rng: Random): List<Rect> {
        // 权重：前 1/3 大块，其余小块
        val weights = (0 until n).map { i ->
            if (i < max(1, n / 3)) rng.nextDouble(1.8, 3.0)
            else rng.nextDouble(0.8, 1.5)
        }
        val totalWeight = weights.sum()
        val totalArea = canvasW.toDouble() * canvasH
        val areas = weights.map { totalArea * it / totalWeight }

        val result = MutableList<Rect?>(n) { null }
        val shuffledIndices = (0 until n).toMutableList().also { it.shuffle(rng) }

        fun slice(indices: List<Int>, x: Int, y: Int, w: Int, h: Int, horizontal: Boolean) {
            if (indices.isEmpty()) return
            if (indices.size == 1) {
                val idx = indices[0]
                var pw = max(w - 2 * PADDING_GAP, 100)
                var ph = max(h - 2 * PADDING_GAP, 100)
                pw = pw.toEven()
                ph = ph.toEven()
                val px = (x + (w - pw) / 2 + rng.nextDouble(-3.0, 3.0)).toInt()
                val py = (y + (h - ph) / 2 + rng.nextDouble(-3.0, 3.0)).toInt()
                result[idx] = Rect(px, py, pw, ph)
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
                val cols = max(1, sqrt(indices.size.toDouble()).toInt())
                val rows = (indices.size + cols - 1) / cols
                var cellW = max(50, (w - (cols - 1) * PADDING_GAP) / cols)
                var cellH = max(50, (h - (rows - 1) * PADDING_GAP) / rows)
                cellW = cellW.toEven()
                cellH = cellH.toEven()
                for ((idxI, idx) in indices.withIndex()) {
                    val c = idxI % cols
                    val r = idxI / cols
                    val px = x + c * (cellW + PADDING_GAP)
                    val py = y + r * (cellH + PADDING_GAP)
                    result[idx] = Rect(px, py, cellW, cellH)
                }
                return
            }

            if (horizontal) {
                val lw = max(1, (w * leftRatio).toInt())
                slice(leftIndices, x, y, lw, h, !horizontal)
                slice(rightIndices, x + lw, y, w - lw, h, !horizontal)
            } else {
                val lh = max(1, (h * leftRatio).toInt())
                slice(leftIndices, x, y, w, lh, !horizontal)
                slice(rightIndices, x, y + lh, w, h - lh, !horizontal)
            }
        }

        slice(shuffledIndices, 0, 0, canvasW, canvasH, horizontal = true)
        return result.map { it!! }
    }

    private fun addWallJitter(layout: List<Rect>, canvasW: Int, canvasH: Int, rng: Random): List<Rect> {
        return layout.map { (x, y, w, h) ->
            val dx = rng.nextInt(-6, 7)
            val dy = rng.nextInt(-6, 7)
            Rect(
                max(0, min(canvasW - w, x + dx)),
                max(0, min(canvasH - h, y + dy)),
                w, h
            )
        }
    }

    private fun roundTo1dp(v: Double): Double = (v * 10).roundToInt() / 10.0

    // ---------- 裁剪中心 ----------

    private fun computeSafeCropCenter(vw: Int, vh: Int, cellW: Int, cellH: Int): Pair<Double, Double> {
        val targetAr = cellW.toDouble() / cellH
        val videoAr = vw.toDouble() / vh

        val (cropW, cropH) = if (videoAr > targetAr) {
            val ch = vh
            val cw = max(2, (vh * targetAr).toInt()).coerceAtMost(vw)
            cw to ch
        } else {
            val cw = vw
            val ch = max(2, (vw / targetAr).toInt()).coerceAtMost(vh)
            cw to ch
        }

        val halfW = cropW / 2.0
        val halfH = cropH / 2.0

        // 默认偏上 1/3（人物头部通常在画面上方）
        var cx = vw / 2.0
        var cy = vh / 3.0
        cx = max(halfW, min(cx, vw - halfW))
        cy = max(halfH, min(cy, vh - halfH))
        return cx to cy
    }

    // ---------- ffmpeg filter 构建 ----------

    private fun buildWallFilter(
        n: Int,
        layout: List<Rect>,
        rotations: List<Double>,
        cropCenters: List<Pair<Double, Double>>,
        metas: List<VideoMeta>,
        durations: List<Double>,
        maxDur: Double,
        audioMask: List<Boolean>,
        canvasW: Int,
        canvasH: Int,
        cutTimes: List<Double?>
    ): Pair<String, Boolean> {
        val filters = mutableListOf<String>()

        // 背景画布
        val bgHex = String.format("0x%02x%02x%02x", BG_R, BG_G, BG_B)
        filters.add("color=c=$bgHex:s=${canvasW}x${canvasH}:d=${maxDur.fmt()}:rate=30[bg]")

        for (i in 0 until n) {
            val (x, y, cellW, cellH) = layout[i]
            val (vw, vh) = metas[i].width to metas[i].height
            val rot = rotations[i]
            val (ccx, ccy) = cropCenters[i]

            val padDur = (maxDur - durations[i]).coerceAtLeast(0.0)
            val trimPfx = if (i < cutTimes.size && cutTimes[i] != null) "trim=end=${cutTimes[i]!!.fmt(3)},setpts=PTS-STARTPTS," else ""

            // 裁剪
            val targetAr = cellW.toDouble() / cellH
            val videoAr = vw.toDouble() / vh
            val (rawCropW, rawCropH) = if (videoAr > targetAr) {
                max(2, (vh * targetAr).toInt()).coerceAtMost(vw) to vh
            } else {
                vw to max(2, (vw / targetAr).toInt()).coerceAtMost(vh)
            }
            val cropW = rawCropW.toEven()
            val cropH = rawCropH.toEven()
            val cropX = (ccx - cropW / 2.0).toInt().coerceIn(0, vw - cropW)
            val cropY = (ccy - cropH / 2.0).toInt().coerceIn(0, vh - cropH)
            val cropStr = "crop=$cropW:$cropH:$cropX:$cropY,"
            val scaleStr = "scale=$cellW:$cellH:flags=lanczos,"
            val commonSuffix = "${cropStr}${scaleStr}fps=30,setsar=1,format=yuv420p"

            // 时长处理
            if (padDur <= 0.001) {
                filters.add("[$i:v]$trimPfx$commonSuffix[v${i}raw]")
            } else {
                filters.add(
                    "[$i:v]$trimPfx" + "split=2[${i}A][${i}B];" +
                    "[${i}A]trim=end_frame=1,loop=loop=-1:size=1,setpts=PTS-STARTPTS[${i}Fof];" +
                    "[${i}B]setpts=PTS-STARTPTS[${i}M];" +
                    "[${i}M][${i}Fof]concat=n=2:v=1:a=0[${i}C];" +
                    "[${i}C]trim=end=${maxDur.fmt()},setpts=PTS-STARTPTS,$commonSuffix[v${i}raw]"
                )
            }

            // 白色边框 + 阴影
            val borderedW = cellW + 2 * BORDER_PX
            val borderedH = cellH + 2 * BORDER_PX
            val shadowedW = borderedW + SHADOW_PX
            val shadowedH = borderedH + SHADOW_PX

            filters.add(
                "[v${i}raw]pad=$borderedW:$borderedH:-1:-1:color=white," +
                "pad=$shadowedW:$shadowedH:-1:-1:color=0x3c3c3c@0.35[v${i}bordered]"
            )

            // 旋转
            if (abs(rot) > 0.1) {
                filters.add(
                    "[v${i}bordered]rotate=${rot}*PI/180:c=$bgHex" +
                    ":ow=rotw(${rot}*PI/180):oh=roth(${rot}*PI/180)[v${i}rot]"
                )
            } else {
                filters.add("[v${i}bordered]copy[v${i}rot]")
            }

            // overlay
            val overlayX = max(0, x - BORDER_PX)
            val overlayY = max(0, y - BORDER_PX)

            if (i == 0) {
                filters.add("[bg][v${i}rot]overlay=$overlayX:$overlayY:eof_action=repeat[v${i}out]")
            } else {
                val prev = i - 1
                filters.add("[v${prev}out][v${i}rot]overlay=$overlayX:$overlayY:eof_action=repeat[v${i}out]")
            }
        }

        val finalLabel = "v${n - 1}out"

        // 音频
        val audioParts = mutableListOf<String>()
        for (i in 0 until n) {
            if (!audioMask[i]) continue
            val padA = (maxDur - durations[i]).coerceAtLeast(0.0)
            val aTrim = if (i < cutTimes.size && cutTimes[i] != null) "atrim=end=${cutTimes[i]!!.fmt(3)},asetpts=PTS-STARTPTS," else ""
            val apad = if (padA > 0) ",apad=whole_dur=${maxDur.fmt()}" else ""
            audioParts.add("[$i:a]${aTrim}aresample=44100$apad[a$i]")
        }

        var filterStr = filters.joinToString(";")
        if (audioParts.isNotEmpty()) {
            val amixIn = audioMask.indices.filter { audioMask[it] }.joinToString("") { "[a$it]" }
            filterStr += ";" + audioParts.joinToString(";") +
                    ";$amixIn amix=inputs=${audioParts.size}:duration=first:normalize=0[aout]"
        }

        // 把最终标签改名为 vout
        filterStr = filterStr.replace("[$finalLabel]", "[vout]")

        return filterStr to audioParts.isNotEmpty()
    }
}
