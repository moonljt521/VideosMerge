package com.moon.videomerger.inpaint

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 去水印算法内核自测（纯 JVM，无 Android 依赖）。
 */
class InpaintCoreTest {

    private val w = 320
    private val h = 200

    /** 平滑渐变背景 + 右下角白色水印块（模拟固定 logo） */
    private fun syntheticFrame(): Pair<ByteArray, RectPx> {
        val rgb = ByteArray(w * h * 3)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = (105 + 55.0 * x / w + 25.0 * y / h).toInt()
                val i = (y * w + x) * 3
                rgb[i] = v.toByte(); rgb[i + 1] = (v + 4).toByte(); rgb[i + 2] = (v + 8).toByte()
            }
        }
        val box = RectPx(208, 138, 84, 34)
        for (y in box.y until box.bottom) {
            for (x in box.x until box.right) {
                // 内部条纹模拟字形边缘
                val on = ((x + y * 2) % 9) < 6
                val v = if (on) 244 else 235
                val i = (y * w + x) * 3
                rgb[i] = v.toByte(); rgb[i + 1] = v.toByte(); rgb[i + 2] = v.toByte()
            }
        }
        return rgb to box
    }

    @Test
    fun maskFindsSyntheticWatermark() {
        val (rgb, box) = syntheticFrame()
        val res = InpaintMaskBuilder.build(rgb, w, h, box)
        val mask = res.mask
        assertNotNull("mask should be found: ${res.message}", mask)
        mask!!
        assertTrue("mask px ${mask.pixelCount}", mask.pixelCount > 200)

        // 遮罩必须覆盖水印块中心，且不越出 bbox 的探测范围太远
        val cx = box.x + box.w / 2
        val cy = box.y + box.h / 2
        val roi = mask.roi
        assertTrue(mask.mask[(cy - roi.y) * roi.w + (cx - roi.x)])

        for (y in 0 until roi.h) {
            for (x in 0 until roi.w) {
                if (!mask.mask[y * roi.w + x]) continue
                val gx = roi.x + x
                val gy = roi.y + y
                assertTrue("mask leaked to ($gx,$gy)", gx in (box.x - 45)..(box.right + 45))
                assertTrue("mask leaked to ($gx,$gy)", gy in (box.y - 45)..(box.bottom + 45))
            }
        }
    }

    @Test
    fun teleaRestoresSmoothBackground() {
        val (rgb, box) = syntheticFrame()
        val mask = InpaintMaskBuilder.build(rgb, w, h, box).mask ?: error("no mask")
        val roi = mask.roi
        val frame = ByteArray(roi.w * roi.h * 3)
        for (y in 0 until roi.h) {
            System.arraycopy(rgb, ((roi.y + y) * w + roi.x) * 3, frame, y * roi.w * 3, roi.w * 3)
        }
        TeleaInpainter(roi.w, roi.h, mask.mask).inpaint(frame)

        var sum = 0.0
        var count = 0
        for (y in 0 until roi.h) {
            for (x in 0 until roi.w) {
                if (!mask.mask[y * roi.w + x]) continue
                val gx = roi.x + x
                val gy = roi.y + y
                val expected = 105 + 55.0 * gx / w + 25.0 * gy / h
                val got = frame[(y * roi.w + x) * 3].toInt() and 0xFF
                sum += abs(got - expected)
                count++
            }
        }
        val mae = sum / count
        assertTrue("inpaint MAE=$mae", mae < 6.0)

        // 遮罩外像素必须原样保留
        for (y in 0 until roi.h) {
            for (x in 0 until roi.w) {
                if (mask.mask[y * roi.w + x]) continue
                val i = (y * roi.w + x) * 3
                val j = ((roi.y + y) * w + roi.x + x) * 3
                assertTrue(frame[i] == rgb[j] && frame[i + 1] == rgb[j + 1] && frame[i + 2] == rgb[j + 2])
            }
        }
    }
}
