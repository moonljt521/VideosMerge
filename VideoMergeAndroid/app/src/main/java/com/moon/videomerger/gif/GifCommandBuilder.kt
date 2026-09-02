package com.moon.videomerger.gif

/**
 * 视频转 GIF 的 ffmpeg 命令构造（纯函数，便于单元测试）。
 *
 * 采用 palettegen/paletteuse 两步法（与根目录 video_to_gif.py 原型一致），
 * 用 split 避免视频被处理两次：
 *   [0:v] → fps → scale → split → [gif] + [palin]
 *   [palin] → palettegen → [palette]
 *   [gif][palette] → paletteuse → [vout]
 */
object GifCommandBuilder {

    /**
     * 按目标宽度等比计算 GIF 宽高。
     * 尺寸取偶数，避免个别播放器/平台对奇数尺寸 GIF 兼容性差。
     */
    fun targetDimensions(sourceWidth: Int, sourceHeight: Int, targetWidth: Int): Pair<Int, Int> {
        require(sourceWidth > 0 && sourceHeight > 0) { "源视频尺寸无效" }
        val w = targetWidth.coerceIn(2, 4096)
        val h = Math.round(sourceHeight.toDouble() * w / sourceWidth).toInt().coerceAtLeast(2)
        return (w - w % 2) to (h - h % 2)
    }

    fun build(
        inputPath: String,
        outputPath: String,
        fps: Int,
        width: Int,
        height: Int,
        quality: GifQuality,
        loop: Boolean
    ): String {
        // 质量档位 → palettegen.stats_mode / paletteuse.dither（与 video_to_gif.py 相同映射）
        val (statsMode, dither) = when (quality) {
            GifQuality.FAST -> "full" to "none"
            GifQuality.NORMAL -> "diff" to "sierra2_4a"
            GifQuality.HIGH -> "diff" to "bayer:bayer_scale=3"
        }
        // ffmpeg GIF muxer 语义：-loop 0 无限循环，-1 只播一次
        val loopParam = if (loop) "0" else "-1"
        return "-y -i \"$inputPath\" " +
            "-filter_complex \"[0:v]fps=$fps,scale=$width:$height:flags=lanczos,split=2[gif][palin];" +
            "[palin]palettegen=stats_mode=$statsMode[palette];" +
            "[gif][palette]paletteuse=dither=$dither[vout]\" " +
            "-map \"[vout]\" -loop $loopParam -f gif \"$outputPath\""
    }
}
