package com.moon.videomerger.merger

import com.moon.videomerger.util.VideoMeta

/**
 * Grid 模式合并器 —— 将多个视频排列成均匀网格。
 *
 * 核心逻辑移植自 grid_merge.py，包含 logo 截断功能。
 */
class GridMerger {

    /**
     * 构建完整的 ffmpeg 命令。
     *
     * @param inputPaths    输入视频文件路径列表（需保证顺序一致）
     * @param durations     各视频时长（秒）
     * @param metas         各视频元数据（宽高、是否有音频）
     * @param outputPath    输出文件路径
     * @param options       合并选项
     * @return 完整的 ffmpeg 命令字符串（不含 "ffmpeg" 前缀）
     */
    fun buildCommand(
        inputPaths: List<String>,
        durations: List<Double>,
        metas: List<VideoMeta>,
        outputPath: String,
        options: MergeOptions,
        cutTimes: List<Double?> = emptyList()
    ): String {
        val n = inputPaths.size

        // 1. 计算网格与单元格尺寸（与 UI 预览共用 MergeLayout，保证所见即所得）
        val spec = MergeLayout.gridSpec(
            n,
            metas.map { it.width.toDouble() / it.height.toDouble() },
            options.gridCellSize
        )
        val rows = spec.rows
        val cols = spec.cols
        val cellW = spec.cellW
        val cellH = spec.cellH

        val maxDur = durations.maxOrNull() ?: 0.0
        val audioMask = metas.map { it.hasAudio }

        val (filterComplex, hasAudio) = buildFilterComplex(
            n, cellW, cellH, rows, cols, durations, maxDur, audioMask, cutTimes
        )

        // 构建 ffmpeg 命令
        val cmd = StringBuilder().apply {
            append("-y ")  // 覆盖输出文件
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

    private fun buildFilterComplex(
        n: Int,
        cellW: Int,
        cellH: Int,
        rows: Int,
        cols: Int,
        durations: List<Double>,
        maxDur: Double,
        audioMask: List<Boolean>,
        cutTimes: List<Double?>
    ): Pair<String, Boolean> {
        val scaled = mutableListOf<String>()

        // 缩放 + pad 处理每个视频
        for (i in 0 until n) {
            val padDur = (maxDur - durations[i]).coerceAtLeast(0.0)
            val trimPfx = if (i < cutTimes.size && cutTimes[i] != null) "trim=end=${cutTimes[i]!!.fmt(3)},setpts=PTS-STARTPTS," else ""
            val common = "scale=${cellW}:${cellH}:force_original_aspect_ratio=decrease," +
                    "pad=${cellW}:${cellH}:(ow-iw)/2:(oh-ih)/2:black,fps=30,setsar=1,format=yuv420p[v$i]"

            if (padDur <= 0.001) {
                scaled.add("[$i:v]$trimPfx$common")
            } else {
                // 用首帧循环补齐时长
                scaled.add(
                    "[$i:v]$trimPfx" + "split=2[${i}A][${i}B];" +
                    "[${i}A]trim=end_frame=1,loop=loop=-1:size=1,setpts=PTS-STARTPTS[${i}Fof];" +
                    "[${i}B]setpts=PTS-STARTPTS[${i}M];" +
                    "[${i}M][${i}Fof]concat=n=2:v=1:a=0[${i}C];" +
                    "[${i}C]trim=end=${maxDur.fmt()},setpts=PTS-STARTPTS,$common"
                )
            }
        }

        // xstack 布局
        val layouts = (0 until n).map { idx ->
            val r = idx / cols
            val c = idx % cols
            "${c * cellW}_${r * cellH}"
        }
        val inputsConcat = (0 until n).joinToString("") { "[v$it]" }
        val layoutStr = layouts.joinToString("|")

        val parts = mutableListOf<String>()
        parts.add(scaled.joinToString(";"))
        parts.add("$inputsConcat xstack=inputs=$n:layout=$layoutStr[vout]")

        // 音频混合（amix）
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