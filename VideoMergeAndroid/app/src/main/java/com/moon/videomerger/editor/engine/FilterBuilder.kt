package com.moon.videomerger.editor.engine

import android.content.Context
import com.moon.videomerger.editor.data.*
import java.util.Locale

/**
 * 滤镜链构建器 —— 将时间轴上的 Clip 列表转换为 ffmpeg filter_complex 字符串。
 *
 * ⚠️ 重要：当前 FFmpegKit 为 min 版本（LGPL），不包含以下组件，已用替代方案：
 *   - 视频编码器 libx264 → 使用硬件编码 h264_mediacodec
 *   - 滤镜 eq / huesaturation → 亮度/对比度/饱和度/伽马用 eq，色调用 huesaturation
 *   - 滤镜 pad → 改用 scale 的 cover 模式 + crop（精确尺寸）
 *   - 滤镜 fps → 用于统一输入帧率（xfade/concat 要求一致），避免混合帧率素材导出失败
 *   - 滤镜 boxblur → 改用 avgblur（模糊背景）
 *   - 滤镜 drawtext → 文字水印改用 PNG overlay（Android Canvas 生成 PNG）
 *
 * 对应 Python 脚本：
 * - trim_video.py → trim/setpts
 * - crop_video.py → crop
 * - rotate_video.py → transpose/hflip/vflip
 * - scale_video.py → scale
 * - speed_up/slow_motion → setpts/atempo
 * - color_filter.py → eq + huesaturation
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
     * @param fps 输出帧率（统一所有片段帧率，避免 xfade/concat 因输入帧率不同失败）
     */
    fun buildVideoFilters(clip: Clip, canvasW: Int, canvasH: Int, fps: Int): String {
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

        // 2.5 倒放（对应 reverse_video.py）—— 注意：reverse 会把整段片段载入内存，
        //     放在 trim 之后执行，只对裁剪后的区间倒放，控制内存占用
        if (clip.reversed) {
            filters.add("reverse")
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

        // 5. 滤镜调色（eq + huesaturation，与 color_filter.py 的 eq/hue 参数保持一致）
        appendColorAdjust(clip, filters)

        // 5.5 统一帧率：不同来源视频帧率可能不同，xfade/concat 要求一致
        //     ★ min 版 FFmpegKit 不含 fps 滤镜，改用 framerate 滤镜
        filters.add("framerate=fps=${fps}")
        //     ★ framerate 只统一帧率，不统一 timebase；xfade/concat 要求 timebase 一致，
        //       这里显式把 timebase 设为 1/fps，并用 setpts=N 重新编号帧，保证时间戳连续。
        filters.add("settb=1/${fps}")
        filters.add("setpts=N")

        // 6. 格式统一（h264_mediacodec 要求 yuv420p）—— 模糊背景模式由后续 split 逻辑处理
        if (!clip.blurBgEnabled) {
            filters.add("format=yuv420p")
        }

        return filters.joinToString(",")
    }

    /**
     * 调色滤镜：
     *   - 亮度/对比度/饱和度/伽马 → eq
     *   - 色调旋转 → huesaturation
     * 参数语义与 Python color_filter.py 的 eq + hue 保持一致。
     */
    private fun appendColorAdjust(clip: Clip, filters: MutableList<String>) {
        val preset = clip.filterPreset
        val brightness = (preset.brightness + clip.brightness).coerceIn(-1.0, 1.0)
        val contrast = (preset.contrast + clip.contrast).coerceIn(-1.0, 1.0)
        val saturation = (preset.saturation + clip.saturation).coerceIn(-1.0, 1.0)
        val hue = preset.hue
        val gamma = preset.gamma

        val hasColorAdjust = brightness != 0.0 || contrast != 0.0 ||
            saturation != 0.0 || hue != 0.0 || gamma != 1.0

        if (!hasColorAdjust) return

        // ★ min 版 FFmpegKit 不含 eq 滤镜，改用可用的滤镜组合：
        //   - 色相/饱和度 → huesaturation
        //   - 亮度/对比度 → colorchannelmixer（对角缩放 + 通过 alpha 增益加偏移）
        //   - 伽马 → lutrgb（逐通道 pow）
        if (hue != 0.0 || saturation != 0.0) {
            val hs = mutableListOf<String>()
            if (hue != 0.0) hs.add("hue=${hue.fmt()}")
            if (saturation != 0.0) hs.add("saturation=${saturation.fmt()}")
            filters.add("huesaturation=" + hs.joinToString(":"))
        }

        if (brightness != 0.0 || contrast != 0.0) {
            val k = (1.0 + contrast).coerceIn(0.0, 2.0)
            val t = (brightness + 0.5 * (1.0 - k)).coerceIn(-1.5, 1.5)
            filters.add(
                "colorchannelmixer=" +
                "rr=${k.fmt()}:gg=${k.fmt()}:bb=${k.fmt()}:" +
                "ra=${t.fmt()}:ga=${t.fmt()}:ba=${t.fmt()}"
            )
        }

        if (gamma != 1.0) {
            val expr = "clip(255*pow(val/255,${gamma.fmt()}),0,255)"
            filters.add("lutrgb=r='$expr':g='$expr':b='$expr'")
        }
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

        // 倒放音频（对应 reverse_video.py 的 areverse）
        if (clip.reversed) {
            filters.add("areverse")
        }

        // 音量
        if (clip.volume != 1.0) {
            filters.add("volume=${clip.volume.fmt()}")
        }

        // 音频淡入淡出（对应 audio_fade.py 的 afade）
        // 经过 atrim/atempo/areverse 后，音频实际时长 == clip.timelineDuration
        val audioDur = clip.timelineDuration
        if (clip.audioFadeIn > 0 && audioDur > 0.1) {
            val d = clip.audioFadeIn.coerceAtMost(audioDur / 2)
            filters.add("afade=t=in:st=0:d=${d.fmt()}")
        }
        if (clip.audioFadeOut > 0 && audioDur > 0.1) {
            val d = clip.audioFadeOut.coerceAtMost(audioDur / 2)
            filters.add("afade=t=out:st=${(audioDur - d).fmt()}:d=${d.fmt()}")
        }

        // 重采样 + 声道统一（aac 编码器要求固定采样率；acrossfade 要求各输入格式一致）
        filters.add("aresample=44100")
        filters.add("aformat=channel_layouts=stereo")

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
        val hasTransition = hasAnyTransition(clips)

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
                val baseFilters = buildVideoFilters(clip, canvasW, canvasH, project.fps)
                val filterPrefix = if (baseFilters.isEmpty()) "" else "$baseFilters,"
                parts.add("[${i}:v]${filterPrefix}split=2[bg${i}][fg${i}]")
                // 背景路：cover 缩放 + 模糊
                parts.add("[bg${i}]scale=${canvasW}:${canvasH}:force_original_aspect_ratio=increase,crop=${canvasW}:${canvasH},setsar=1,avgblur=sizeX=${blurR}:sizeY=${blurR},format=yuv420p[bgblur${i}]")
                // 前景路：contain 缩放
                parts.add("[fg${i}]scale=${canvasW}:${canvasH}:force_original_aspect_ratio=decrease,setsar=1,format=yuv420p[fgscaled${i}]")
                // overlay 居中：(W-w)/2, (H-h)/2
                parts.add("[bgblur${i}][fgscaled${i}]overlay=(W-w)/2:(H-h)/2[v$i]")
            } else {
                // 普通模式
                val vFilters = buildVideoFilters(clip, canvasW, canvasH, project.fps)
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
                    // 无音频：生成静音填充（声道/采样率与有音频片段保持一致）
                    parts.add(
                        "anullsrc=channel_layout=stereo:sample_rate=44100," +
                        "atrim=duration=${clipDur.fmt()},asetpts=PTS-STARTPTS," +
                        "aformat=channel_layouts=stereo[a$i]"
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

        // 音频拼接：
        //   ★ 有转场时视频用 xfade 会缩短总时长，音频必须用 acrossfade 同步缩短，
        //     否则转场点之后音画不同步。
        val hasAudio = audioLabels.isNotEmpty()
        val finalAudioLabel = if (hasAudio) {
            if (hasTransition) {
                buildAcrossfadeChain(clips, audioLabels, parts)
            } else {
                val aLabel = "[aout]"
                parts.add(audioLabels.joinToString("") + "concat=n=${audioLabels.size}:v=0:a=1$aLabel")
                aLabel
            }
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

        // 时长限制（★ 有转场时输出时长会缩短，必须用实际输出时长，否则 -t 超出实际时长）
        val totalDur = computeOutputDuration(project)
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
     * 相邻片段间的有效转场时长。
     * 限制：不超过较短片段时长的 80%（否则 xfade offset 非法）；
     * NONE 转场用 0.01s 的极短淡入淡出近似（保持 xfade 链连续）。
     */
    fun effectiveTransitionDur(clips: List<Clip>, i: Int): Double {
        if (clips[i].transition == TransitionEffect.NONE) return 0.01
        val maxD = minOf(clips[i].timelineDuration, clips[i + 1].timelineDuration) * 0.8
        return clips[i].transitionDuration.coerceIn(0.01, maxD.coerceAtLeast(0.01))
    }

    /**
     * 主轨是否存在至少一个非 NONE 且时长大于 0 的转场。
     */
    private fun hasAnyTransition(clips: List<Clip>): Boolean =
        clips.zipWithNext().any { (a, _) ->
            a.transition != TransitionEffect.NONE && a.transitionDuration > 0
        }

    /**
     * 计算导出后的实际输出时长（xfade 转场会使总时长缩短）。
     * 用于 ffmpeg -t 限制和进度条百分比计算。
     */
    fun computeOutputDuration(project: EditorProject): Double {
        val mainTrack = project.mainTrack ?: return 0.0
        val clips = mainTrack.clips.sortedBy { it.timelineStart }
        var total = clips.sumOf { it.timelineDuration }
        if (hasAnyTransition(clips)) {
            for (i in 0 until clips.size - 1) {
                total -= effectiveTransitionDur(clips, i)
            }
        }
        return total.coerceAtLeast(0.0)
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
            val curLabel = videoLabels[i + 1]

            val outLabel = if (i == n - 2) "[vout]" else "[x$i]"

            if (effect == TransitionEffect.NONE) {
                // 无转场：用极短的 fade（0.01s）近似，保持 xfade 链连续
                parts.add(
                    "$prevLabel$curLabel" +
                    "xfade=transition=fade:duration=0.01:" +
                    "offset=${(cumulativeOut - 0.01).fmt(3)}$outLabel"
                )
                cumulativeOut += durations[i + 1] - 0.01
            } else {
                // 正常转场（时长钳制在有效范围内，避免 offset 非法）
                val transDur = effectiveTransitionDur(clips, i)
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

    /**
     * 构建 acrossfade 音频转场链 —— 与视频 xfade 链镜像，
     * 保证转场后音画同步（总时长缩短量一致）。
     */
    private fun buildAcrossfadeChain(
        clips: List<Clip>,
        audioLabels: List<String>,
        parts: MutableList<String>
    ): String {
        val n = clips.size
        if (n < 2) return audioLabels[0]

        var prevLabel = audioLabels[0]
        for (i in 0 until n - 1) {
            val curLabel = audioLabels[i + 1]
            val outLabel = if (i == n - 2) "[aout]" else "[ax$i]"
            val d = effectiveTransitionDur(clips, i)
            parts.add(
                "$prevLabel$curLabel" +
                "acrossfade=d=${d.fmt()}:c1=tri:c2=tri$outLabel"
            )
            prevLabel = outLabel
        }
        return if (n == 2) "[aout]" else prevLabel
    }
}

/** 浮点数格式化（不受地区影响） */
fun Double.fmt(decimals: Int = 3): String = String.format(Locale.US, "%.${decimals}f", this)
fun Int.toEven(): Int = this - this % 2

/** 向下对齐到 16 的倍数（h264_mediacodec 要求 macroblock 对齐） */
fun Int.toAligned16(): Int = this / 16 * 16
