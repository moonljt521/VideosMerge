package com.moon.videomerger.editor.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * 静态水印检测核心算法自测（纯函数，无 Android 依赖）。
 */
class WatermarkAnalyzerTest {

    private val w = 90
    private val h = 160

    /** 生成一帧：随帧号平移的渐变背景 + 可选的固定"水印"方块 */
    private fun frame(index: Int, logo: DoubleArray?): FloatArray {
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                // 背景：随 index 移动的平滑正弦波场（模拟水面/光影，无空间接缝）
                val v = (127 + 90 * sin(x * 0.35 + index * 0.9) * sin(y * 0.23 + index * 0.7)).toFloat()
                out[y * w + x] = v
            }
        }
        if (logo != null) {
            // logo 区域：固定的条纹亮块（模拟文字 logo 的内部笔画边缘）
            val x0 = (logo[0] * w).toInt(); val y0 = (logo[1] * h).toInt()
            val x1 = x0 + (logo[2] * w).toInt(); val y1 = y0 + (logo[3] * h).toInt()
            for (y in y0 until y1) for (x in x0 until x1) {
                out[y * w + x] = if ((x + y * 2) % 8 < 4) 240f else 60f
            }
        }
        return out
    }

    /** 半透明 logo：像素 = 0.5×logo图案 + 0.5×动态背景（时间方差被背景抬高） */
    private fun frameWithTranslucentLogo(index: Int, logo: DoubleArray): FloatArray {
        val bg = frame(index, null)
        val x0 = (logo[0] * w).toInt(); val y0 = (logo[1] * h).toInt()
        val x1 = x0 + (logo[2] * w).toInt(); val y1 = y0 + (logo[3] * h).toInt()
        for (y in y0 until y1) for (x in x0 until x1) {
            val pattern = if ((x + y * 2) % 8 < 4) 240f else 60f
            bg[y * w + x] = 0.5f * pattern + 0.5f * bg[y * w + x]
        }
        return bg
    }

    @Test
    fun 半透明水印经均值图边缘通道检出() {
        // 场景：半透明 logo 叠在持续运动的水面上——
        // 静态掩码抓不到（方差被背景抬高），但均值图会留下 logo 幽灵边缘
        val frames = (0 until 12).map { frameWithTranslucentLogo(it, bottomRightLogo) }
        val found = WatermarkDetector.analyze(frames, w, h)
        assertTrue("半透明水印应被检出: $found", found.isNotEmpty())

        val logo = found.first()
        val cx = logo[0] + logo[2] / 2
        val cy = logo[1] + logo[3] / 2
        assertTrue("检测框中心应落在水印附近 ($cx,$cy)",
            cx in bottomRightLogo[0]..bottomRightLogo[0] + bottomRightLogo[2] + 0.05 &&
            cy in bottomRightLogo[1]..bottomRightLogo[1] + bottomRightLogo[3] + 0.05)
    }

    private val bottomRightLogo = doubleArrayOf(0.68, 0.82, 0.25, 0.12)

    @Test
    fun 能检测到右下角静态水印() {
        val frames = (0 until 12).map { frame(it, bottomRightLogo) }
        val found = WatermarkDetector.analyze(frames, w, h)
        assertTrue("应至少检测到 1 个区域", found.isNotEmpty())

        val logo = found.first()
        // 检测框应覆盖 logo 中心
        val cx = logo[0] + logo[2] / 2
        val cy = logo[1] + logo[3] / 2
        val inX = cx in (bottomRightLogo[0])..(bottomRightLogo[0] + bottomRightLogo[2])
        val inY = cy in (bottomRightLogo[1])..(bottomRightLogo[1] + bottomRightLogo[3])
        assertTrue("检测框中心应落在水印内 ($cx,$cy)", inX && inY)
    }

    @Test
    fun 无静态区时返回空() {
        val frames = (0 until 12).map { frame(it, null) }
        val found = WatermarkDetector.analyze(frames, w, h)
        assertTrue(found.isEmpty())
    }

    @Test
    fun 全画面静止时放弃检测() {
        // 冻结帧：所有帧完全一致 → 满屏低方差，应主动放弃而非满屏误报
        val frozen = frame(0, bottomRightLogo)
        val frames = (0 until 12).map { frozen }
        val found = WatermarkDetector.analyze(frames, w, h)
        assertTrue(found.isEmpty())
    }

    @Test
    fun 检测框面积合理() {
        val frames = (0 until 12).map { frame(it, bottomRightLogo) }
        val found = WatermarkDetector.analyze(frames, w, h)
        found.forEach { r ->
            val area = r[2] * r[3]
            assertTrue("面积应小于 12%（实际 $area）", area < 0.12)
            assertTrue("面积不能过小（实际 $area）", area > 0.0005)
        }
    }

    @Test
    fun 静止背景块被环带检验剔除() {
        // 大块静止区域（如纯色墙壁）：区域本身和周围环带都静止 → 应被剔除
        val frames = (0 until 12).map { idx ->
            val f = frame(idx, null)
            // 右下角一块 20%x20% 的区域完全静止（含周围环带一起静止）
            for (y in (h * 0.7).toInt() until h) {
                for (x in (w * 0.7).toInt() until w) {
                    f[y * w + x] = 128f
                }
            }
            f
        }
        val found = WatermarkDetector.analyze(frames, w, h)
        assertTrue("静止背景块不应被误判为水印: $found", found.isEmpty())
    }

    @Test
    fun 细长条被剔除() {
        // 底部横向静止细条（黑边/字幕条）：长宽比 > 12 → 剔除
        val frames = (0 until 12).map { idx ->
            val f = frame(idx, null)
            for (x in 0 until w) {
                for (y in (h * 0.95).toInt() until h) {
                    f[y * w + x] = 10f
                }
            }
            f
        }
        val found = WatermarkDetector.analyze(frames, w, h)
        assertTrue("细长条不应被误判为水印: $found", found.isEmpty())
    }

    @Test
    fun 膨胀与连通域基本正确() {
        val mask = BooleanArray(w * h)
        mask[10 * w + 10] = true
        mask[10 * w + 12] = true
        val dilated = WatermarkDetector.dilate(mask, w, h, radius = 2)
        // 膨胀后两点应连通为一个连通域
        val boxes = WatermarkDetector.connectedBoxes(dilated, w, h)
        assertEquals(1, boxes.size)
    }
}
