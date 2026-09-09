package com.moon.videomerger.merger

import com.moon.videomerger.util.VideoMeta
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 照片墙模式合并器 —— 大大小小、错落有致，加白边框和阴影。
 *
 * 核心逻辑移植自 photo_wall_merge.py，包含 logo 截断，简化掉人脸检测（使用偏上裁剪）。
 */
class PhotoWallMerger {

    companion object {
        private const val BORDER_PX = 8
        private const val SHADOW_PX = 12
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

        // 1. treemap 布局 + 抖动 + 旋转（与 UI 预览共用 MergeLayout，保证所见即所得）
        val wall = MergeLayout.photoWallLayout(n, canvasW, canvasH, options.photoWallSeed)
        val jitteredLayout = wall.rects
        val rotations = wall.rotations

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
        layout: List<LayoutRect>,
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
