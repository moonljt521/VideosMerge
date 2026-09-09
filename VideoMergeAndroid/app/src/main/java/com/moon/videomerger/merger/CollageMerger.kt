package com.moon.videomerger.merger

import com.moon.videomerger.util.VideoMeta

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

        val canvasW = options.canvasWidth.toAligned16()
        val canvasH = options.canvasHeight.toAligned16()

        // 与 UI 预览共用 MergeLayout，保证所见即所得
        val layout = MergeLayout.collageLayout(
            n, canvasW, canvasH, options.collageMainRatio,
            options.collageOrient, options.gap, mainIdx
        )
        val sizes = layout.sizes
        val positions = layout.positions

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
