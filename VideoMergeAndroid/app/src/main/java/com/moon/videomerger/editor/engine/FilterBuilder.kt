package com.moon.videomerger.editor.engine

import android.content.Context
import com.moon.videomerger.editor.data.*
import java.io.File
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
        val (pre, post) = buildVideoFiltersSegmented(clip, canvasW, canvasH, fps)
        return when {
            pre.isEmpty() -> post
            post.isEmpty() -> pre
            else -> "$pre,$post"
        }
    }

    /**
     * 分段构建单片段视频滤镜链：
     *   pre  = trim/变速/倒放 —— 输出保持【源帧几何】（未旋转未缩放）
     *   post = 旋转/翻转/缩放/调色/帧率/格式
     *
     * ★ 去水印区域按「源帧归一化坐标」定义（与预览所见一致），
     *   因此水印滤镜图必须插在 pre 与 post 之间。
     */
    fun buildVideoFiltersSegmented(clip: Clip, canvasW: Int, canvasH: Int, fps: Int): Pair<String, String> {
        val pre = mutableListOf<String>()

        // 1. 时间裁剪（trim）—— 必须放在最前，裁剪后再做其他处理
        if (clip.trimStart > 0 || (clip.trimEnd > 0 && clip.trimEnd < clip.mediaDuration)) {
            val end = if (clip.trimEnd > 0) clip.trimEnd else clip.mediaDuration
            pre.add("trim=start=${clip.trimStart.fmt()}:end=${end.fmt()}")
            pre.add("setpts=PTS-STARTPTS")
        }

        // 2. 变速（setpts）—— speed>1 加速（PTS 减小），speed<1 减速（PTS 增大）
        if (clip.speed != 1.0) {
            pre.add("setpts=PTS/${clip.speed.fmt()}")
        }

        // 2.5 倒放（对应 reverse_video.py）—— 注意：reverse 会把整段片段载入内存，
        //     放在 trim 之后执行，只对裁剪后的区间倒放，控制内存占用
        if (clip.reversed) {
            pre.add("reverse")
        }

        val post = mutableListOf<String>()

        // 3. 旋转/翻转
        when (clip.rotation) {
            90 -> post.add("transpose=1")
            -90 -> post.add("transpose=0")
            180 -> { post.add("transpose=1"); post.add("transpose=1") }
        }
        if (clip.hflip) post.add("hflip")
        if (clip.vflip) post.add("vflip")

        // 4. 缩放到画布尺寸
        //    - 模糊背景模式：不在这里缩放，由 buildExportCommand 的 split 逻辑处理
        //    - 普通模式：cover（填充画布，可能裁剪边缘）
        if (!clip.blurBgEnabled) {
            // cover 模式：填充画布 + crop 到精确尺寸
            post.add("scale=${canvasW}:${canvasH}:force_original_aspect_ratio=increase:flags=lanczos")
            post.add("crop=${canvasW}:${canvasH}")
            post.add("setsar=1")
        }

        // 5. 滤镜调色（eq + huesaturation，与 color_filter.py 的 eq/hue 参数保持一致）
        appendColorAdjust(clip, post)

        // 5.5 统一帧率：不同来源视频帧率可能不同，xfade/concat 要求一致
        //     ★ min 版 FFmpegKit 不含 fps 滤镜，改用 framerate 滤镜
        post.add("framerate=fps=${fps}")
        //     ★ framerate 只统一帧率，不统一 timebase；xfade/concat 要求 timebase 一致，
        //       这里显式把 timebase 设为 1/fps，并用 setpts=N 重新编号帧，保证时间戳连续。
        post.add("settb=1/${fps}")
        post.add("setpts=N")

        // 6. 格式统一（h264_mediacodec 要求 yuv420p）—— 模糊背景模式由后续 split 逻辑处理
        if (!clip.blurBgEnabled) {
            post.add("format=yuv420p")
        }

        return pre.joinToString(",") to post.joinToString(",")
    }

    /**
     * 构建去水印滤镜图：对 [inputLabel] 的每个水印区域
     *   crop 出区域 → 模糊（avgblur）或马赛克（缩小再放大）→ overlay 回原位
     *
     * 坐标为源帧归一化 0~1，映射到 [frameW]×[frameH]（clip.width/height）。
     * 前置 format=yuv420p 保证 split 出的各路与 overlay 输入像素格式一致。
     */
    fun buildWatermarkGraph(
        inputLabel: String,
        outputLabel: String,
        clip: Clip,
        frameW: Int,
        frameH: Int,
        parts: MutableList<String>
    ) {
        val regions = clip.watermarkRegions.filter { it.w >= 0.005 && it.h >= 0.005 }
        if (regions.isEmpty()) {
            parts.add("$inputLabel,null$outputLabel")
            return
        }

        val base = "[wm_base]"
        // ★ 标签后不能跟逗号（[label]filter 语法），否则 ffmpeg 解析失败
        parts.add("$inputLabel format=yuv420p$base")

        // 统一结构：split 出 N+1 路，每路处理一个区域，依次 overlay 回基路（按各自时间段生效）
        // ★ 不能走「单区域直接 crop 输出」的捷径——那会把整帧变成小区域，丢掉其余画面
        val n = regions.size
        val splitLabels = (0..n).joinToString("") { "[wm_s${it}]" }
        parts.add("$base split=${n + 1}$splitLabels")
        var cur = "[wm_s0]"
        regions.forEachIndexed { k, r ->
            val processed = "[wm_b$k]"
            parts.add("[wm_s${k + 1}]${regionFilterBody(r, frameW, frameH)}$processed")
            val out = if (k == n - 1) outputLabel else "[wm_o$k]"
            parts.add(
                "$cur$processed overlay=${evenPx(r.x * frameW, frameW)}:${evenPx(r.y * frameH, frameH)}" +
                    ":${regionEnableExpr(clip, r)}$out"
            )
            cur = out
        }
    }

    /**
     * 区域生效时间表达式（overlay enable）。
     * 区域时间存的是【源视频秒】，而滤镜链 pre 段已做 trim+变速，
     * t 是片段流时间，需要换算：
     *   正放：streamT = (srcT - trimStart) / speed
     *   倒放：streamT = (srcEnd - srcT) / speed
     * endTime <= startTime 视为整个片段生效。
     */
    private fun regionEnableExpr(clip: Clip, r: WatermarkRegion): String {
        val dur = clip.timelineDuration
        val s = sourceToStreamTime(clip, r.startTime).coerceIn(0.0, dur)
        val e = if (r.endTime <= r.startTime) {
            dur
        } else {
            sourceToStreamTime(clip, r.endTime).coerceIn(0.0, dur)
        }
        // enable 表达式含逗号，单引号包裹避免被滤镜链解析器切分
        return "enable='between(t,${s.fmt(2)},${e.fmt(2)})'"
    }

    /** 源视频时间 → 片段流时间（pre 段 trim/setpts/变速之后的 t） */
    private fun sourceToStreamTime(clip: Clip, srcT: Double): Double {
        val end = if (clip.trimEnd > 0) clip.trimEnd else clip.mediaDuration
        return if (clip.reversed) {
            (end - srcT) / clip.speed
        } else {
            (srcT - clip.trimStart) / clip.speed
        }
    }

    /** 单个水印区域的处理滤镜体（crop → 处理 → 尺寸还原） */
    private fun regionFilterBody(r: WatermarkRegion, frameW: Int, frameH: Int): String {
        val x = evenPx(r.x * frameW, frameW)
        val y = evenPx(r.y * frameH, frameH)
        val w = evenPx(r.w * frameW, frameW - x).coerceAtLeast(8)
        val h = evenPx(r.h * frameH, frameH - y).coerceAtLeast(8)
        return when (r.mode) {
            WatermarkMode.BLUR -> {
                // ★ 模糊半径不能超过区域尺寸（否则 ffmpeg 报错），下限 1
                val radius = minOf(
                    r.strength.coerceIn(1, 40),
                    maxOf(1, w / 2),
                    maxOf(1, h / 2),
                )
                "crop=$w:$h:$x:$y,avgblur=sizeX=$radius:sizeY=$radius,format=yuv420p"
            }
            WatermarkMode.MOSAIC -> {
                val block = r.strength.coerceIn(4, 64)
                val sw = (w / block).coerceAtLeast(2)
                val sh = (h / block).coerceAtLeast(2)
                "crop=$w:$h:$x:$y,scale=$sw:$sh:flags=neighbor,scale=$w:$h:flags=neighbor,format=yuv420p"
            }
        }
    }

    /** 像素值取偶数并钳制（yuv420p/编码器对齐友好） */
    private fun evenPx(v: Double, max: Int): Int {
        val i = kotlin.math.round(v).toInt().coerceIn(0, max.coerceAtLeast(0))
        return if (i % 2 == 0) i else (i - 1).coerceAtLeast(0)
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

        // 降噪（FFT 降噪 afftdn；anlmdn 为非局部均值降噪备选）
        if (clip.noiseReduction) {
            filters.add("afftdn=nf=-25")
        }

        // 变声（asetrate 改音高 + atempo 恢复时长，保持原速改音色）
        if (clip.pitchShift != 1.0) {
            val p = clip.pitchShift.coerceIn(0.5, 2.0)
            val rate = (44100 * p).toInt()
            filters.add("asetrate=$rate")
            filters.add("aresample=44100")
            filters.add("atempo=${(1.0 / p).fmt()}")
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
     * 为画中画（PICTURE 轨）片段构建视频滤镜链。
     *
     * 与 buildVideoFilters 的区别：不缩放到画布，而是缩放到 pipWidth 指定的叠加宽度；
     * 输出 format=rgba 并按 pipOpacity 调整透明通道，供 overlay 叠加。
     */
    fun buildPipVideoFilters(clip: Clip, canvasW: Int, canvasH: Int, fps: Int): String {
        val filters = mutableListOf<String>()

        if (clip.isImage) {
            // 静态图片：loop 填充到指定时长
            val dur = clip.effectiveDuration.coerceAtLeast(0.1)
            filters.add("loop=loop=-1:size=1:start=0")
            filters.add("trim=duration=${dur.fmt()}")
            filters.add("setpts=PTS-STARTPTS")
        } else {
            // 1. 时间裁剪
            if (clip.trimStart > 0 || (clip.trimEnd > 0 && clip.trimEnd < clip.mediaDuration)) {
                val end = if (clip.trimEnd > 0) clip.trimEnd else clip.mediaDuration
                filters.add("trim=start=${clip.trimStart.fmt()}:end=${end.fmt()}")
                filters.add("setpts=PTS-STARTPTS")
            }

            // 2. 变速
            if (clip.speed != 1.0) filters.add("setpts=PTS/${clip.speed.fmt()}")

            // 3. 倒放
            if (clip.reversed) filters.add("reverse")

            // 4. 旋转/翻转
            when (clip.rotation) {
                90 -> filters.add("transpose=1")
                -90 -> filters.add("transpose=0")
                180 -> { filters.add("transpose=1"); filters.add("transpose=1") }
            }
            if (clip.hflip) filters.add("hflip")
            if (clip.vflip) filters.add("vflip")
        }

        // 5. 缩放到叠加尺寸（与蒙版/描边 PNG 尺寸一致，偶数对齐）
        val (pipW, pipH) = pipOutputSize(clip, canvasW)
        filters.add("scale=${pipW}:${pipH}:flags=lanczos")
        filters.add("setsar=1")

        // 6. 统一帧率/timebase，并把 PTS 平移到时间轴位置（timelineStart）
        //    ★ 不能用 overlay 的 enable 做时间窗：enable 在禁用期间仍会消耗叠加帧，
        //      导致延迟叠加层提前 EOF 不显示。改为 setpts 平移 + eof_action=pass。
        filters.add("framerate=fps=${fps}")
        filters.add("settb=1/${fps}")
        filters.add("setpts=PTS+${clip.timelineStart.coerceAtLeast(0.0).fmt()}/TB")

        // 7. 透明通道 + 透明度
        filters.add("format=rgba")
        if (clip.pipOpacity < 1.0) {
            filters.add("colorchannelmixer=aa=${clip.pipOpacity.coerceIn(0.0, 1.0).fmt()}")
        }

        return filters.joinToString(",")
    }

    /**
     * 计算画中画叠加层缩放后的输出尺寸（宽、高均为偶数，与蒙版/描边 PNG 一致）。
     * 旋转 90/270 时宽高互换，保证 scale 后不变形。
     */
    private fun pipOutputSize(clip: Clip, canvasW: Int): Pair<Int, Int> {
        val pipW = (canvasW * clip.pipWidth.coerceIn(0.05, 1.0)).toInt()
            .coerceAtLeast(16).let { it - it % 2 }
        val w = clip.width
        val h = clip.height
        val rot = ((clip.rotation % 360) + 360) % 360
        val swapped = rot == 90 || rot == 270
        val effW = if (swapped) h else w
        val effH = if (swapped) w else h
        val aspect = if (effW > 0 && effH > 0) effH.toDouble() / effW.toDouble() else 1.0
        val pipH = (pipW * aspect).toInt().coerceAtLeast(2).let { it - it % 2 }
        return pipW to pipH
    }

    /**
     * 构建画中画「位置关键帧」的归一化位置表达式（随 t 分段线性插值）。
     * @param axis 'x' 或 'y'
     * @param fallback 无关键帧时的静态位置（0~1）
     */
    private fun buildPipNormExpr(keyframes: List<PipKeyframe>, axis: Char, fallback: Double): String {
        if (keyframes.isEmpty()) return fallback.coerceIn(0.0, 1.0).fmt()
        val sorted = keyframes.sortedBy { it.time }
        if (sorted.size == 1) {
            val v = if (axis == 'x') sorted[0].x else sorted[0].y
            return v.coerceIn(0.0, 1.0).fmt()
        }
        fun valueOf(k: PipKeyframe) = (if (axis == 'x') k.x else k.y).coerceIn(0.0, 1.0)

        var expr = valueOf(sorted.last()).fmt()
        for (i in sorted.size - 2 downTo 0) {
            val a = sorted[i]
            val b = sorted[i + 1]
            val va = valueOf(a)
            val vb = valueOf(b)
            val dt = (b.time - a.time).coerceAtLeast(0.0001)
            val seg = "clip(${va.fmt()}+(${vb.fmt()}-${va.fmt()})*(t-${a.time.fmt()})/${dt.fmt()},${minOf(va, vb).fmt()},${maxOf(va, vb).fmt()})"
            expr = "if(lt(t,${b.time.fmt()}),$seg,$expr)"
        }
        return expr
    }

    /** 字幕位置：水平居中，底部留边距 */
    private fun subtitleOverlayPosition(textW: Int, textH: Int, videoW: Int, videoH: Int): Pair<Int, Int> {
        val margin = (videoH * 0.08).toInt().coerceAtLeast(20)
        return ((videoW - textW) / 2) to (videoH - textH - margin)
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

        // 画中画叠加层（PICTURE 轨，按时间轴顺序）
        val pipClips = project.tracks
            .filter { it.type == TrackType.PICTURE }
            .flatMap { it.clips }
            .filter { it.pipEnabled }
            .sortedBy { it.timelineStart }

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

        // 生成画中画形状蒙版/描边 PNG（如果需要）
        val pipMaskInputIdx = mutableMapOf<String, Int>()   // pip clipId -> mask 输入编号
        val pipBorderInputIdx = mutableMapOf<String, Int>() // pip clipId -> border 输入编号
        val pipMaskFiles = mutableMapOf<String, File>()     // pip clipId -> mask 文件
        val pipBorderFiles = mutableMapOf<String, File>()   // pip clipId -> border 文件
        if (context != null) {
            pipClips.forEach { clip ->
                val (pipW, pipH) = pipOutputSize(clip, canvasW)
                val radius = (pipW * clip.pipCornerRadius.coerceIn(0.0, 0.5)).toFloat()
                if (clip.pipShape != PipShape.RECT) {
                    try {
                        pipMaskFiles[clip.id] = PipMaskRenderer.renderMask(context, pipW, pipH, clip.pipShape, radius)
                    } catch (e: Exception) {
                        android.util.Log.e("FilterBuilder", "生成画中画蒙版失败", e)
                    }
                }
                if (clip.pipBorder) {
                    try {
                        val sw = (pipW * clip.pipBorderWidth.coerceIn(0.0, 0.2)).toFloat().coerceAtLeast(1f)
                        pipBorderFiles[clip.id] = PipMaskRenderer.renderBorder(context, pipW, pipH, clip.pipShape, radius, sw)
                    } catch (e: Exception) {
                        android.util.Log.e("FilterBuilder", "生成画中画描边失败", e)
                    }
                }
            }
        }

        // 生成字幕 PNG（复用文字水印 Canvas 方案，中文可靠；不用 libass subtitles 滤镜避免设备字体问题）
        val subtitlePngs = mutableListOf<Pair<Subtitle, TextPngResult>>()
        if (context != null) {
            project.subtitles.sortedBy { it.startTime }.forEach { sub ->
                try {
                    val png = TextWatermarkRenderer.renderToPng(
                        context = context,
                        text = sub.text,
                        fontSize = 64,
                        colorStr = "white",
                        opacity = 1.0f,
                        border = true,
                    )
                    subtitlePngs.add(sub to png)
                } catch (e: Exception) {
                    android.util.Log.e("FilterBuilder", "生成字幕 PNG 失败", e)
                }
            }
        }

        // 构建 filter_complex
        val parts = mutableListOf<String>()
        val videoLabels = mutableListOf<String>()
        val audioLabels = mutableListOf<String>()

        // 输入流编号：
        //   0..n-1: 主轨视频文件
        //   n..n+m-1: 画中画（PICTURE 轨）文件
        //   之后: 文字水印 PNG、图片水印（按主轨 clip 顺序追加）
        val pipInputIdx = mutableMapOf<String, Int>()  // pip clipId -> 输入流编号
        pipClips.forEachIndexed { i, clip -> pipInputIdx[clip.id] = clips.size + i }
        var nextInputIdx = clips.size + pipClips.size
        val textPngInputIdx = mutableMapOf<Int, Int>()  // clipIndex -> 输入流编号
        val imageWatermarkInputIdx = mutableMapOf<Int, Int>()  // clipIndex -> 输入流编号

        clips.forEachIndexed { i, clip ->
            val (preFilters, postFilters) = buildVideoFiltersSegmented(clip, canvasW, canvasH, project.fps)
            val prePrefix = if (preFilters.isEmpty()) "" else "$preFilters,"
            val postSeg = if (postFilters.isEmpty()) "" else "$postFilters,"

            if (clip.blurBgEnabled) {
                // 模糊背景模式：
                //   pre 段已做 trim/变速/倒放；post 段做旋转/调色（无 scale）
                //   ★ 去水印区域在 pre 与 post 之间应用（源帧几何，与预览一致）
                //   split 两路：
                //     背景路：scale cover（填满画布）+ avgblur（模糊）
                //     前景路：scale contain（保留比例）
                //   overlay 前景居中到背景
                val blurR = clip.blurStrength
                val baseLabel = if (clip.watermarkRegions.isNotEmpty()) {
                    parts.add("[${i}:v]${prePrefix}format=yuv420p[wm_in$i]")
                    buildWatermarkGraph("[wm_in$i]", "[wm_g$i]", clip, clip.width, clip.height, parts)
                    "[wm_g$i]"
                } else {
                    "[${i}:v]"
                }
                parts.add("$baseLabel${postSeg}split=2[bg${i}][fg${i}]")
                // 背景路：cover 缩放 + 模糊
                parts.add("[bg${i}]scale=${canvasW}:${canvasH}:force_original_aspect_ratio=increase,crop=${canvasW}:${canvasH},setsar=1,avgblur=sizeX=${blurR}:sizeY=${blurR},format=yuv420p[bgblur${i}]")
                // 前景路：contain 缩放
                parts.add("[fg${i}]scale=${canvasW}:${canvasH}:force_original_aspect_ratio=decrease,setsar=1,format=yuv420p[fgscaled${i}]")
                // overlay 居中：(W-w)/2, (H-h)/2
                parts.add("[bgblur${i}][fgscaled${i}]overlay=(W-w)/2:(H-h)/2[v$i]")
            } else {
                // 普通模式
                // ★ 去水印区域在 pre（源帧几何）与 post（旋转/缩放/调色）之间应用，
                //   坐标与预览所见完全一致
                if (clip.watermarkRegions.isNotEmpty()) {
                    parts.add("[${i}:v]${prePrefix}format=yuv420p[wm_in$i]")
                    buildWatermarkGraph("[wm_in$i]", "[wm_g$i]", clip, clip.width, clip.height, parts)
                    // ★ post 直接接标签，不能带尾逗号
                    parts.add("[wm_g$i]$postFilters[v$i]")
                } else {
                    val vFilters = buildVideoFilters(clip, canvasW, canvasH, project.fps)
                    parts.add("[${i}:v]${vFilters}[v$i]")
                }
            }
            videoLabels.add("[v$i]")

            // 文字水印 PNG 输入
            textPngs[i]?.let { png ->
                textPngInputIdx[i] = nextInputIdx
                nextInputIdx++
            }

            clip.imageWatermarkPath?.let {
                imageWatermarkInputIdx[i] = nextInputIdx
                nextInputIdx++
            }
        }

        // 画中画蒙版/描边输入编号（在文字/图片水印之后；先所有蒙版，再所有描边）
        pipClips.forEach { clip ->
            if (pipMaskFiles.containsKey(clip.id)) { pipMaskInputIdx[clip.id] = nextInputIdx; nextInputIdx++ }
        }
        pipClips.forEach { clip ->
            if (pipBorderFiles.containsKey(clip.id)) { pipBorderInputIdx[clip.id] = nextInputIdx; nextInputIdx++ }
        }
        // 字幕 PNG 输入编号（在所有蒙版/描边之后）
        val subtitleInputIdx = mutableMapOf<String, Int>()  // subtitle id -> 输入编号
        subtitlePngs.forEach { (sub, _) ->
            subtitleInputIdx[sub.id] = nextInputIdx; nextInputIdx++
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

            clip.imageWatermarkPath?.let { imgPath ->
                val imgIdx = imageWatermarkInputIdx[i]!!
                val targetW = (canvasW * clip.imageWatermarkScale).toInt().coerceAtLeast(16)
                val opacity = clip.imageWatermarkOpacity.coerceIn(0.0, 1.0)
                val pos = imageOverlayPosition(clip.imageWatermarkPosition)
                val newLabel = "[vw$i]"
                parts.add(
                    "[${imgIdx}:v]scale=${targetW}:-1,format=rgba," +
                    "colorchannelmixer=aa=${opacity.fmt()}[wm$i]"
                )
                parts.add("$label[wm$i]overlay=$pos$newLabel")
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
        var finalVideoLabel = if (hasTransition) {
            buildXfadeChain(clips, finalVideoLabels, parts)
        } else {
            // 普通 concat
            val concatLabel = "[vout]"
            parts.add(finalVideoLabels.joinToString("") + "concat=n=${clips.size}:v=1:a=0$concatLabel")
            concatLabel
        }

        // 画中画叠加：把 PICTURE 轨片段逐个 overlay 到主视频之上（时间窗 + 位置/大小/透明度 + 形状/描边）
        pipClips.forEachIndexed { i, clip ->
            val idx = pipInputIdx[clip.id] ?: return@forEachIndexed
            val start = clip.timelineStart.coerceAtLeast(0.0)
            val dur = clip.timelineDuration
            // 位置：pipX/pipY ∈ [0,1]，映射到「可用范围」(W-w)/(H-h)；
            // 有关键帧时用分段线性表达式随时间插值（位移动画）
            // ★ 表达式含逗号时需单引号包裹，否则滤镜解析器按逗号切分滤镜链
            val xNorm = buildPipNormExpr(clip.pipKeyframes, 'x', clip.pipX)
            val yNorm = buildPipNormExpr(clip.pipKeyframes, 'y', clip.pipY)
            val xExpr = "'(W-w)*$xNorm'"
            val yExpr = "'(H-h)*$yNorm'"

            // 画中画视频流（已缩放 + rgba + 透明度 + 已按 timelineStart 平移 PTS）
            var pipLabel = "[pip$i]"
            parts.add("[${idx}:v]${buildPipVideoFilters(clip, canvasW, canvasH, project.fps)}$pipLabel")

            // 形状蒙版：alphamerge 用蒙版亮度替换 alpha，实现圆角/圆形裁剪
            //    ★ 用 fps（保留 alpha）而非 framerate（会丢 alpha）；同样平移 + 限时长
            val maskIdx = pipMaskInputIdx[clip.id]
            if (maskIdx != null) {
                parts.add("[${maskIdx}:v]format=rgba,fps=${project.fps},setpts=PTS+${start.fmt()}/TB,trim=duration=${dur.fmt()}[mask$i]")
                val shaped = "[ps$i]"
                parts.add("$pipLabel[mask$i]alphamerge$shaped")
                pipLabel = shaped
            }

            // 叠加到主视频（PTS 已平移，无需 enable 时间窗，只靠 eof_action=pass 收尾）
            val isLast = i == pipClips.lastIndex
            val hasBorder = pipBorderInputIdx[clip.id] != null
            val afterPipLabel = if (isLast && !hasBorder) "[vout2]" else "[pov$i]"
            parts.add(
                "${finalVideoLabel}$pipLabel" +
                    "overlay=$xExpr:$yExpr:eof_action=pass$afterPipLabel"
            )
            finalVideoLabel = afterPipLabel

            // 描边：白色描边框叠加在同位置（fps 保留 alpha，平移 + 限时长）
            val borderIdx = pipBorderInputIdx[clip.id]
            if (borderIdx != null) {
                parts.add("[${borderIdx}:v]format=rgba,fps=${project.fps},setpts=PTS+${start.fmt()}/TB,trim=duration=${dur.fmt()}[border$i]")
                val afterBorderLabel = if (isLast) "[vout2]" else "[povb$i]"
                parts.add(
                    "${finalVideoLabel}[border$i]" +
                        "overlay=$xExpr:$yExpr:eof_action=pass$afterBorderLabel"
                )
                finalVideoLabel = afterBorderLabel
            }
        }

        // 字幕：每条字幕 PNG 按时间窗 overlay 到最终视频（画中画之上；PTS 平移 + eof_action=pass）
        subtitlePngs.forEachIndexed { i, (sub, png) ->
            val idx = subtitleInputIdx[sub.id] ?: return@forEachIndexed
            val dur = (sub.endTime - sub.startTime).coerceAtLeast(0.1)
            val (ox, oy) = subtitleOverlayPosition(png.width, png.height, canvasW, canvasH)
            val subLabel = if (i == subtitlePngs.lastIndex) "[vsub]" else "[sub$i]"
            parts.add("[${idx}:v]format=rgba,fps=${project.fps},setpts=PTS+${sub.startTime.fmt()}/TB,trim=duration=${dur.fmt()}[subpng$i]")
            parts.add("${finalVideoLabel}[subpng$i]overlay=$ox:$oy:eof_action=pass$subLabel")
            finalVideoLabel = subLabel
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
        // 输入文件：画中画（PICTURE 轨）视频/图片
        pipClips.forEach { clip ->
            cmd.append(" -i \"${clip.mediaPath}\"")
        }
        // 输入文件：文字水印 PNG（按 clip 顺序）
        clips.forEachIndexed { i, _ ->
            textPngs[i]?.let { png ->
                cmd.append(" -i \"${png.file.absolutePath}\"")
            }
        }
        // 输入文件：图片水印（按 clip 顺序）
        clips.forEachIndexed { i, clip ->
            clip.imageWatermarkPath?.let { path ->
                cmd.append(" -i \"$path\"")
            }
        }
        // 输入文件：画中画蒙版 PNG（-loop 1 使其覆盖多帧）
        pipClips.forEach { clip ->
            pipMaskFiles[clip.id]?.let { file ->
                cmd.append(" -loop 1 -i \"${file.absolutePath}\"")
            }
        }
        // 输入文件：画中画描边 PNG
        pipClips.forEach { clip ->
            pipBorderFiles[clip.id]?.let { file ->
                cmd.append(" -loop 1 -i \"${file.absolutePath}\"")
            }
        }
        // 输入文件：字幕 PNG（-loop 1 覆盖时间窗）
        subtitlePngs.forEach { (_, png) ->
            cmd.append(" -loop 1 -i \"${png.file.absolutePath}\"")
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
     * ★ 委托给数据层唯一实现（effectiveTransitionOverlap），
     *   保证导出 xfade 的重叠时长与时间轴布局严格一致。
     */
    fun effectiveTransitionDur(clips: List<Clip>, i: Int): Double =
        effectiveTransitionOverlap(clips[i], clips[i + 1])

    /**
     * 主轨是否存在至少一个非 NONE 且时长大于 0 的转场。
     */
    private fun hasAnyTransition(clips: List<Clip>): Boolean =
        clips.zipWithNext().any { (a, _) ->
            a.transition != TransitionEffect.NONE && a.transitionDuration > 0
        }

    /**
     * 计算导出后的实际输出时长。
     * ★ 时间轴已按「转场 = 重叠」建模（见 relayoutMainTrackClips），
     *   输出时长即最后一个片段的结束位置，与时间轴显示一致。
     */
    fun computeOutputDuration(project: EditorProject): Double =
        project.mainTrack?.duration ?: 0.0

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

    /**
     * 图片水印位置表达式（相对于画布）。
     */
    private fun imageOverlayPosition(position: String): String {
        return when (position) {
            "top-left" -> "10:10"
            "top-right" -> "W-w-10:10"
            "bottom-left" -> "10:H-h-10"
            "bottom-right" -> "W-w-10:H-h-10"
            "center" -> "(W-w)/2:(H-h)/2"
            else -> "W-w-10:H-h-10"
        }
    }
}

/** 浮点数格式化（不受地区影响） */
fun Double.fmt(decimals: Int = 3): String = String.format(Locale.US, "%.${decimals}f", this)
fun Int.toEven(): Int = this - this % 2

/** 向下对齐到 16 的倍数（h264_mediacodec 要求 macroblock 对齐） */
fun Int.toAligned16(): Int = this / 16 * 16
