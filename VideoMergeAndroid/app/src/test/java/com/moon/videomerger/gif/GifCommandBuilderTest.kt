package com.moon.videomerger.gif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GIF 命令构造的纯逻辑测试：等比尺寸取偶、质量档位参数、循环参数。
 */
class GifCommandBuilderTest {

    // ── targetDimensions ──

    @Test
    fun `等比缩放 16x9 源到 480 宽`() {
        val (w, h) = GifCommandBuilder.targetDimensions(1920, 1080, 480)
        assertEquals(480, w)
        assertEquals(270, h)
    }

    @Test
    fun `竖屏源按宽度等比缩放`() {
        val (w, h) = GifCommandBuilder.targetDimensions(1080, 1920, 360)
        assertEquals(360, w)
        assertEquals(640, h)
    }

    @Test
    fun `奇数高度向下取偶`() {
        // 481 为奇数 → 480
        val (w, h) = GifCommandBuilder.targetDimensions(720, 481, 720)
        assertEquals(720, w)
        assertEquals(480, h)
    }

    @Test
    fun `目标宽度超出范围被收敛`() {
        val (w, _) = GifCommandBuilder.targetDimensions(1920, 1080, 9999)
        assertEquals(4096, w)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `无效源尺寸抛异常`() {
        GifCommandBuilder.targetDimensions(0, 1080, 480)
    }

    // ── build ──

    @Test
    fun `标准档包含 palettegen 与 sierra 抖动`() {
        val cmd = GifCommandBuilder.build(
            inputPath = "/cache/in.mp4", outputPath = "/cache/out.gif",
            fps = 10, width = 480, height = 270, quality = GifQuality.NORMAL, loop = true
        )
        assertTrue(cmd.contains("fps=10,scale=480:270:flags=lanczos,split=2"))
        assertTrue(cmd.contains("palettegen=stats_mode=diff"))
        assertTrue(cmd.contains("paletteuse=dither=sierra2_4a"))
        assertTrue(cmd.contains("-map \"[vout]\""))
        assertTrue(cmd.contains("-f gif \"/cache/out.gif\""))
    }

    @Test
    fun `高质量档使用 bayer 抖动`() {
        val cmd = GifCommandBuilder.build(
            inputPath = "in.mp4", outputPath = "out.gif",
            fps = 15, width = 480, height = 270, quality = GifQuality.HIGH, loop = true
        )
        assertTrue(cmd.contains("paletteuse=dither=bayer:bayer_scale=3"))
        assertTrue(cmd.contains("fps=15"))
    }

    @Test
    fun `快速档使用全帧调色板且不抖动`() {
        val cmd = GifCommandBuilder.build(
            inputPath = "in.mp4", outputPath = "out.gif",
            fps = 8, width = 240, height = 135, quality = GifQuality.FAST, loop = true
        )
        assertTrue(cmd.contains("palettegen=stats_mode=full"))
        assertTrue(cmd.contains("paletteuse=dither=none"))
    }

    @Test
    fun `循环开关映射到 loop 参数`() {
        fun loopOf(loop: Boolean): String = GifCommandBuilder.build(
            inputPath = "in.mp4", outputPath = "out.gif",
            fps = 10, width = 480, height = 270, quality = GifQuality.NORMAL, loop = loop
        ).substringAfter("-loop ").substringBefore(' ')

        // ffmpeg GIF muxer 语义：0 无限循环，-1 只播一次
        assertEquals("0", loopOf(true))
        assertEquals("-1", loopOf(false))
    }
}
