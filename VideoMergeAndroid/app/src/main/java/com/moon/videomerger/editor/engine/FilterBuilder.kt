package com.moon.videomerger.editor.engine

import android.content.Context
import com.moon.videomerger.editor.data.*
import java.util.Locale

/**
 * 滤镜链构建器 —— 将时间轴上的 Clip 列表转换为 ffmpeg filter_complex 字符串。
 *
 * ⚠️ 重要：当前 FFmpegKit 为 min 版本（LGPL），不包含以下组件，已用替代方案：
 *   - 视频编码器 libx264 → 使用硬件编码 h264_mediacodec
 *   - 滤镜 eq / hue → 改用 colorbalance（亮度/对比度/饱和度/色调）
 *   - 滤镜 pad → 改用 scale 的 cover 模式 + crop（精确尺寸）
 *   - 滤镜 fps → 移除（编码器自行处理）
 *   - 滤镜 boxblur → 改用 avgblur（模糊背景）
 *   - 滤镜 drawtext → 文字水印改用 PNG overlay（Android Canvas 生成 PNG）
 *
 * 对应 Python 脚本：
 * - trim_video.py → trim/setpts
 * - crop_video.py → crop
 * - rotate_video.py → transpose/hflip/vflip
 * - scale_video.py → scale
 * - speed_up/slow_motion → setpts/atempo
 * - color_filter.py → colorbalance（替代 eq/hue）
 * - volume_adjust.py → volume
 * - text_watermark.py → overlay (PNG，由 TextWatermarkRenderer 生成)
 * - blur_bg.py → split + avgblur + overlay（替代 boxblur）
 * - transition.py → xfade
 * - reverse_video.py → reverse/areverse
 */
class FilterBuilder {

    /**
     * 为单个 Clip 构建视频滤镜链（不含文字水印和模糊背景，这两个需要额外输入流）。
     *
     * @param canvasW 画布宽（必须 16 对齐，h264_mediacodec 要求）
     * @param canvasH 画布高（必须 16 对齐）
     */
    fun buildVideoFilters(clip: Clip, canvasW: Int, canvasH: Int): String {
        val filters = mutableListOf<String>()

        // 1. 时间裁剪（trim）—— 必须放在最前，裁剪后再做其他处理
        if (clip.trimStart > 0 || (clip.trimEnd > 0 && clip.trimEnd < clip.mediaDuration)) {
            val end = if (clip.trimEnd > 0) clip.trimEnd else clip.mediaDuration
            filters.add("trim=start=${clip.trimStart.fmt()}:end=${end.fmt()}")
            filters.add("setpts=PTS-STARTPTS")
        }

        // 2. 变速（setpts）—— speed>1 加速（PTS 减小），speed<1 减速（PTS 增大）
        if (clip.speed != 1.0) {
            filters.add("setpts=PTS/${clip.speed.fmt()}")
        }

        // 3. 旋转/翻转
        when (clip.rotation) {
            90 -> filters.add("transpose=1")
            -90 -> filters.add("transpose=0")
            180 -> { filters.add("transpose=1"); filters.add("transpose=1") }
        }
        if (clip.hflip) filters.add("hflip")
        if (clip.vflip) filters.add("vflip")

        // 4. 缩放到画布尺寸
        //    - 模糊背景模式：不在这里缩放，由 buildExportCommand 的 split 逻辑处理
        //    - 普通模式：cover（填充画布，可能裁剪边缘）
        if (!clip.blurBgEnabled) {
            // cover 模式：填充画布 + crop 到精确尺寸
            filters.add("scale=${canvasW}:${canvasH}:force_original_aspect_ratio=increase:flags=lanczos")
            filters.add("crop=${canvasW}:${canvasH}")
            filters.add("setsar=1")
        }

        // 5. 滤镜调色（min 版没有 eq/hue，用 colorbalance 实现）
        appendColorAdjust(clip, filters)

        // 6. 格式统一（h264_mediacodec 要求 yuv420p）—— 模糊背景模式由后续 split 逻辑处理
        if (!clip.blurBgEnabled) {
            filters.add("format=yuv420p")
        }

        return filters.joinToString(",")
    }

    /**
     * 调色滤镜（colorbalance 替代 eq/hue）
     */
    private fun appendColorAdjust(clip: Clip, filters: MutableList<String>) {
        val preset = clip.filterPreset
        val brightness = preset.brightness + clip.brightness
        val contrast = preset.contrast + clip.contrast
        val saturation = preset.saturation + clip.saturation
        val hue = preset.hue

        val hasColorAdjust = preset != FilterPreset.NONE ||
            clip.brightness != 0.0 || clip.contrast != 0.0 || clip.saturation != 0.0

        if (!hasColorAdjust) return

        // 亮度：阴影和高光同向偏移
        val bShift = brightness.coerceIn(-1.0, 1.0) * 0.3
        // 对比度：阴影反向、高光同向（拉大反差）
        val cShift = contrast.coerceIn(-1.0, 1.0) * 0.2
        // 饱和度：中间调同向偏移
        val sShift = saturation.coerceIn(-1.0, 1.0) * 0.15

        // 色调：暖色（hue>0）R↑B↓，冷色（hue<0）B↑R↓
        val hueR = if (hue > 0) hue / 180.0 * 0.2 else 0.0
        val hueB = if (hue < 0) hue / 180.0 * 0.2 else 0.0

        val rs = bShift - cShift
        val rh = bShift + cShift + hueR
        val bh = bShift + cShift + hueB

        filters.add(
            "colorbalance=" +
            "rs=${rs.fmt()}:gs=${rs.fmt()}:bs=${rs.fmt()}:" +
            "rm=${sShift.fmt()}:gm=${sShift.fmt()}:bm=${sShift.fmt()}:" +
            "rh=${rh.fmt()}:gh=${(bShift + cShift).fmt()}:bh=${bh.fmt()}"
        )
    }

    /**
     * 为单个 Clip 构建音频滤镜链。
     */
    fun buildAudioFilters(clip: Clip): String? {
        if (!clip.hasAudio) return null

        val filters = mutableListOf<String>()

        // 时间裁剪
        if (clip.trimStart > 0 || (clip.trimEnd > 0 && clip.trimEnd < clip.mediaDuration)) {
            val end = if (clip.trimEnd > 0) clip.trimEnd else clip.mediaDuration
            filters.add("atrim=start=${clip.trimStart.fmt()}:end=${end.fmt()}")
            filters.add("asetpts=PTS-STARTPTS")
        }

        // 变速（atempo 链式，范围 0.5~2.0）
        if (clip.speed != 1.0) {
            filters.add(buildAtempoChain(clip.speed))
        }

        // 音量
        if (clip.volume != 1.0) {
            filters.add("volume=${clip.volume.fmt()}")
        }

        // 重采样统一（aac 编码器要求固定采样率）
        filters.add("aresample=44100")

        return if (filters.isEmpty()) null else filters.joinToString(",")
    }

    /**
     * 构建 atempo 链（变速音频，范围 0.5~2.0，超范围链式拆分）。
     *
     * 注意：atempo 的参数是"输出/输入"比率。
     *   - speed=2.0（加速2倍）→ atempo=2.0（音频也快2倍，时长减半）
     *   - speed=0.5（减速到一半）→ atempo=0.5（音频也慢2倍，时长翻倍）
     */
    private fun buildAtempoChain(speed: Double): String {
        if (speed >= 0.5 && speed <= 2.0) {
            return "atempo=${speed.fmt()}"
        }
        val parts = mutableListOf<String>()
        var remaining = speed
        while (remaining > 2.0) {
            parts.add("atempo=2.0")
            remaining /= 2.0
        }
        while (remaining < 0.5) {
            parts.add("atempo=0.5")
            remaining /= 0.5
        }
        parts.add("atempo=${remaining.fmt()}")
        return parts.joinToString(",")
    }

    /**
     * 构建完整的导出命令。
     *
     * 使用 h264_mediacodec（硬件编码）+ aac（内置编码器）。
     * 画布尺寸需 16 对齐（mediacodec 要求 macroblock 对齐）。
     *
     * @param project 编辑项目
     * @param outputPath 输出文件路径
     * @param context Android Context（用于生成文字水印 PNG）
     * @return 完整的 ffmpeg 命令字符串（不含 "ffmpeg" 前缀）
     */
    fun buildExportCommand(
        project: EditorProject,
        outputPath: String,
        context: Context? = null
    ): String {
        val mainTrack = project.mainTrack ?: return ""
        val clips = mainTrack.clips.sortedBy { it.timelineStart }
        if (clips.isEmpty()) return ""

        // 画布尺寸 16 对齐
        val canvasW = project.canvasWidth.toAligned16()
        val canvasH = project.canvasHeight.toAligned16()

        // 是否有转场
        val hasTransition = clips.zipWithNext().any { (a, b) ->
            a.transition != TransitionEffect.NONE && a.transitionDuration > 0
        }

        // 生成文字水印 PNG（如果有）
        val textPngs = mutableMapOf<Int, TextPngResult>()  // clipIndex -> PNG
        if (context != null) {
            clips.forEachIndexed { i, clip ->
                if (!clip.textOverlay.isNullOrBlank()) {
                    try {
                        textPngs[i] = TextWatermarkRenderer.renderToPng(
                            context = context,
                            text = clip.textOverlay,
                            fontSize = clip.textSize,
                            colorStr = clip.textColor,
                            opacity = clip.textOpacity,
                            border = clip.textBorder
                        )
                    } catch (e: Exception) {
                        android.util.Log.e("FilterBuilder", "生成文字水印失败", e)
                    }
                }
            }
        }

        // 构建 filter_complex
        val parts = mutableListOf<String>()
        val videoLabels = mutableListOf<String>()
        val audioLabels = mutableListOf<String>()

        // 输入流编号：
        //   0..n-1: 视频文件
        //   n..n+m-1: 文字水印 PNG（按 clip 顺序追加）
        var nextInputIdx = clips.size
        val textPngInputIdx = mutableMapOf<Int, Int>()  // clipIndex -> 输入流编号

        clips.forEachIndexed { i, clip ->
            if (clip.blurBgEnabled) {
                // 模糊背景模式：
                //   buildVideoFilters 已做 trim/变速/旋转/调色（无 scale）
                //   split 两路：
                //     背景路：scale cover（填满画布）+ avgblur（模糊）
                //     前景路：scale contain（保留比例）
                //   overlay 前景居中到背景
                val blurR = clip.blurStrength
                val baseFilters = buildVideoFilters(clip, canvasW, canvasH)
                parts.add("[${i}:v]${baseFilters},split=2[bg${i}][fg${i}]")
                // 背景路：cover 缩放 + 模糊
                parts.add("[bg${i}]scale=${canvasW}:${canvasH}:force_original_aspect_ratio=increase,crop=${canvasW}:${canvasH},avgblur=sizeX=${blurR}:sizeY=${blurR},format=yuv420p[bgblur${i}]")
                // 前景路：contain 缩放
                parts.add("[fg${i}]scale=${canvasW}:${canvasH}:force_original_aspect_ratio=decrease,setsar=1,format=yuv420p[fgscaled${i}]")
                // overlay 居中：(W-w)/2, (H-h)/2
                parts.add("[bgblur${i}][fgscaled${i}]overlay=(W-w)/2:(H-h)/2[v$i]")
            } else {
                // 普通模式
                val vFilters = buildVideoFilters(clip, canvasW, canvasH)
                parts.add("[${i}:v]${vFilters}[v$i]")
            }
            videoLabels.add("[v$i]")

            // 文字水印 PNG 输入
            textPngs[i]?.let { png ->
                textPngInputIdx[i] = nextInputIdx
                nextInputIdx++
            }
        }

        // 为有文字水印的片段叠加 PNG
        val finalVideoLabels = mutableListOf<String>()
        clips.forEachIndexed { i, clip ->
            var label = "[v$i]"
            // 文字水印 overlay
            textPngs[i]?.let { png ->
                val pngIdx = textPngInputIdx[i]!!
                val (ox, oy) = TextWatermarkRenderer.calcOverlayPosition(
                    clip.textPosition, png.width, png.height, canvasW, canvasH
                )
                // 叠加文字 PNG
                parts.add("[${pngIdx}:v]format=rgba,scale=${png.width}:${png.height}[png$i]")
                val newLabel = "[vt$i]"
                parts.add("$label[png$i]overlay=${ox}:${oy}$newLabel")
                label = newLabel
            }
            finalVideoLabels.add(label)
        }

        // 音频处理
        val anyAudio = clips.any { it.hasAudio }
        if (anyAudio) {
            clips.forEachIndexed { i, clip ->
                val clipDur = clip.timelineDuration
                if (clip.hasAudio) {
                    val aFilters = buildAudioFilters(clip)
                    if (aFilters != null) {
                        parts.add("[${i}:a]${aFilters}[a$i]")
                    } else {
                        parts.add("[${i}:a]aresample=44100[a$i]")
                    }
                } else {
                    // 无音频：生成静音填充
                    parts.add(
                        "anullsrc=channel_layout=stereo:sample_rate=44100," +
                        "atrim=duration=${clipDur.fmt()},asetpts=PTS-STARTPTS[a$i]"
                    )
                }
                audioLabels.add("[a$i]")
            }
        }

        // 视频拼接：转场 or concat
        val finalVideoLabel = if (hasTransition) {
            buildXfadeChain(clips, finalVideoLabels, parts)
        } else {
            // 普通 concat
            val concatLabel = "[vout]"
            parts.add(finalVideoLabels.joinToString("") + "concat=n=${clips.size}:v=1:a=0$concatLabel")
            concatLabel
        }

        // 音频拼接
        val hasAudio = audioLabels.isNotEmpty()
        val finalAudioLabel = if (hasAudio) {
            val aLabel = "[aout]"
            parts.add(audioLabels.joinToString("") + "concat=n=${audioLabels.size}:v=0:a=1$aLabel")
            aLabel
        } else null

        val filterComplex = parts.joinToString(";")

        // 构建 ffmpeg 命令
        val cmd = StringBuilder()
        cmd.append("-y")

        // 输入文件：视频
        clips.forEach { clip ->
            cmd.append(" -i \"${clip.mediaPath}\"")
        }
        // 输入文件：文字水印 PNG（按 clip 顺序）
        clips.forEachIndexed { i, _ ->
            textPngs[i]?.let { png ->
                cmd.append(" -i \"${png.file.absolutePath}\"")
            }
        }

        // filter_complex
        cmd.append(" -filter_complex \"$filterComplex\"")

        // 映射
        cmd.append(" -map \"$finalVideoLabel\"")
        if (finalAudioLabel != null) cmd.append(" -map $finalAudioLabel")

        // 视频编码
        cmd.append(" -c:v h264_mediacodec -b:v 8M")

        // 音频编码
        if (hasAudio) {
            cmd.append(" -c:a aac -b:a 192k")
        }

        // 时长限制
        val totalDur = project.totalDuration
        if (totalDur > 0) {
            cmd.append(" -t ${totalDur.fmt(2)}")
        }

        // 输出
        cmd.append(" -movflags +faststart")
        cmd.append(" \"$outputPath\"")

        val result = cmd.toString()
        android.util.Log.d("FilterBuilder", "Export command: $result")
        return result
    }

    /**
     * 构建 xfade 转场链。
     *
     * xfade 语法：[a][b]xfade=transition=EFFECT:duration=D:offset=O[out]
     *   offset = 前面累计输出时长 - 当前转场时长
     *   每次转场后输出时长 = 前时长 + 当前片段时长 - 转场时长
     *
     * 链式：x0 = xfade(v0, v1), x1 = xfade(x0, v2), ...
     *
     * 注意：只处理 clips[i].transition（即 clip[i] 到 clip[i+1] 的转场）。
     * 如果某个 transition 为 NONE，则该处不做转场（但 xfade 链不能中断，
     * 所以 NONE 的转场用 fade + duration=0.01 近似跳过，或改用 concat 分段）。
     */
    private fun buildXfadeChain(
        clips: List<Clip>,
        videoLabels: List<String>,
        parts: MutableList<String>
    ): String {
        val n = clips.size
        if (n < 2) return videoLabels[0]

        // 计算每个片段的有效时长（timelineDuration 已考虑变速和 trim）
        val durations = clips.map { it.timelineDuration }

        // 累计输出时长（每次转场后会减少转场时长）
        var cumulativeOut = durations[0]
        var prevLabel = videoLabels[0]

        for (i in 0 until n - 1) {
            val effect = clips[i].transition
            val transDur = clips[i].transitionDuration
            val curLabel = videoLabels[i + 1]

            val outLabel = if (i == n - 2) "[vout]" else "[x$i]"

            if (effect == TransitionEffect.NONE || transDur <= 0) {
                // 无转场：用 concat 连接（但这会打断 xfade 链）
                // 简化处理：用极短的 fade（0.01s）近似无转场
                parts.add(
                    "$prevLabel$curLabel" +
                    "xfade=transition=fade:duration=0.01:" +
                    "offset=${(cumulativeOut - 0.01).fmt(3)}$outLabel"
                )
                cumulativeOut += durations[i + 1] - 0.01
            } else {
                // 正常转场
                val offset = cumulativeOut - transDur
                parts.add(
                    "$prevLabel$curLabel" +
                    "xfade=transition=${effect.key}:duration=${transDur.fmt()}:" +
                    "offset=${offset.fmt(3)}$outLabel"
                )
                cumulativeOut += durations[i + 1] - transDur
            }
            prevLabel = outLabel
        }

        return if (n == 2) "[vout]" else prevLabel
    }
}

/** 浮点数格式化（不受地区影响） */
fun Double.fmt(decimals: Int = 3): String = String.format(Locale.US, "%.${decimals}f", this)
fun Int.toEven(): Int = this - this % 2

/** 向下对齐到 16 的倍数（h264_mediacodec 要求 macroblock 对齐） */
fun Int.toAligned16(): Int = this / 16 * 16
