package com.moon.videomerger.merger

import com.moon.videomerger.util.VideoMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 合并布局（预览与导出共用）单元测试。
 *
 * 重点锁两件事：
 * 1. 布局算法本身正确（网格 / 画中画 / 照片墙）；
 * 2. 预览用的 MergeLayout 与 Merger 生成的 ffmpeg 布局字符串一致，防止两边漂移。
 */
class MergeLayoutTest {

    private fun meta(w: Int = 1920, h: Int = 1080) = VideoMeta(w, h, 10.0, true)

    // ---------- Grid ----------

    @Test
    fun 四视频网格为2x2且单元格16对齐() {
        val spec = MergeLayout.gridSpec(4, List(4) { 16.0 / 9.0 }, 720)
        assertEquals(2, spec.rows)
        assertEquals(2, spec.cols)
        // 720x405 → 16 对齐后 720x400
        assertEquals(720, spec.cellW)
        assertEquals(400, spec.cellH)
        assertEquals(1440, spec.outW)
        assertEquals(800, spec.outH)
    }

    @Test
    fun 三视频网格末行留空且矩形行优先() {
        val rects = MergeLayout.gridSpec(3, List(3) { 16.0 / 9.0 }, 720).rects()
        assertEquals(LayoutRect(0, 0, 720, 400), rects[0])
        assertEquals(LayoutRect(720, 0, 720, 400), rects[1])
        assertEquals(LayoutRect(0, 400, 720, 400), rects[2])
    }

    @Test
    fun 主导宽高比取出现次数最多者() {
        val aspect = MergeLayout.dominantAspect(listOf(16.0 / 9.0, 16.0 / 9.0, 1.0))
        assertEquals(1.778, aspect, 1e-9)
    }

    @Test
    fun 竖屏视频单元格也16对齐() {
        val spec = MergeLayout.gridSpec(2, List(2) { 9.0 / 16.0 }, 720)
        // 405x720 → 400x720；2 个视频为 1 行 2 列
        assertEquals(400, spec.cellW)
        assertEquals(720, spec.cellH)
        assertEquals(800, spec.outW)
        assertEquals(720, spec.outH)
    }

    @Test
    fun 网格预览矩形与导出xstack布局一致() {
        val metas = List(3) { meta() }
        val options = MergeOptions(gridCellSize = 720)
        val command = GridMerger().buildCommand(
            inputPaths = List(3) { "/tmp/$it.mp4" },
            durations = List(3) { 10.0 },
            metas = metas,
            outputPath = "/tmp/out.mp4",
            options = options
        )
        val spec = MergeLayout.gridSpec(3, metas.map { 16.0 / 9.0 }, options.gridCellSize)
        val expectedLayout = spec.rects().take(metas.size).joinToString("|") { "${it.x}_${it.y}" }
        assertTrue("命令应包含预览布局 $expectedLayout", command.contains("layout=$expectedLayout"))
    }

    // ---------- Collage ----------

    @Test
    fun 画中画左右布局严格铺满画布() {
        val layout = MergeLayout.collageLayout(
            n = 2, canvasW = 1920, canvasH = 1080, mainRatio = 0.62,
            orient = "left", gap = 0, mainIdx = 0
        )
        assertEquals(LayoutRect(0, 0, 1190, 1080), layout.rects()[0])
        assertEquals(LayoutRect(1190, 0, 730, 1080), layout.rects()[1])
        assertEquals(1920, layout.outW)
        assertEquals(1080, layout.outH)
    }

    @Test
    fun 画中画上下布局主窗口在上() {
        val layout = MergeLayout.collageLayout(
            n = 2, canvasW = 1920, canvasH = 1080, mainRatio = 0.62,
            orient = "top", gap = 0, mainIdx = 0
        )
        assertEquals(LayoutRect(0, 0, 1920, 670), layout.rects()[0])
        assertEquals(LayoutRect(0, 670, 1920, 410), layout.rects()[1])
    }

    @Test
    fun 画中画四视频副窗口纵向均分() {
        val layout = MergeLayout.collageLayout(
            n = 4, canvasW = 1920, canvasH = 1080, mainRatio = 0.62,
            orient = "left", gap = 0, mainIdx = 0
        )
        val rects = layout.rects()
        assertEquals(LayoutRect(0, 0, 1190, 1080), rects[0])
        assertEquals(LayoutRect(1190, 0, 730, 360), rects[1])
        assertEquals(LayoutRect(1190, 360, 730, 360), rects[2])
        assertEquals(LayoutRect(1190, 720, 730, 360), rects[3])
        // 副窗口纵向刚好铺满
        assertEquals(1080, rects[1].h + rects[2].h + rects[3].h)
    }

    @Test
    fun 画中画主窗口索引生效() {
        val layout = MergeLayout.collageLayout(
            n = 2, canvasW = 1920, canvasH = 1080, mainRatio = 0.62,
            orient = "left", gap = 0, mainIdx = 1
        )
        val rects = layout.rects()
        assertEquals(LayoutRect(1190, 0, 730, 1080), rects[0])
        assertEquals(LayoutRect(0, 0, 1190, 1080), rects[1])
    }

    @Test
    fun 画中画预览矩形与导出xstack布局一致() {
        val metas = List(3) { meta() }
        val options = MergeOptions(canvasWidth = 1920, canvasHeight = 1080)
        val command = CollageMerger().buildCommand(
            inputPaths = List(3) { "/tmp/$it.mp4" },
            durations = List(3) { 10.0 },
            metas = metas,
            outputPath = "/tmp/out.mp4",
            options = options
        )
        val layout = MergeLayout.collageLayout(
            3, options.canvasWidth.toAligned16(), options.canvasHeight.toAligned16(),
            options.collageMainRatio, options.collageOrient, options.gap, options.collageMainIndex
        )
        val expectedLayout = layout.rects().joinToString("|") { "${it.x}_${it.y}" }
        assertTrue("命令应包含预览布局 $expectedLayout", command.contains("layout=$expectedLayout"))
    }

    // ---------- Photo Wall ----------

    @Test
    fun 照片墙同种子结果可复现() {
        val a = MergeLayout.photoWallLayout(5, 1920, 1080, 42)
        val b = MergeLayout.photoWallLayout(5, 1920, 1080, 42)
        assertEquals(a.rects, b.rects)
        assertEquals(a.rotations, b.rotations)
    }

    @Test
    fun 照片墙不同种子版式不同() {
        val a = MergeLayout.photoWallLayout(5, 1920, 1080, 42)
        val b = MergeLayout.photoWallLayout(5, 1920, 1080, 43)
        assertNotEquals(a.rects, b.rects)
    }

    @Test
    fun 照片墙矩形不越界且数量正确() {
        val wall = MergeLayout.photoWallLayout(6, 1920, 1080, 7)
        assertEquals(6, wall.rects.size)
        assertEquals(6, wall.rotations.size)
        wall.rects.forEach { r ->
            assertTrue("x=${r.x} w=${r.w}", r.x >= 0 && r.x + r.w <= 1920)
            assertTrue("y=${r.y} h=${r.h}", r.y >= 0 && r.y + r.h <= 1080)
            assertTrue(r.w > 0 && r.h > 0)
        }
    }

    @Test
    fun 照片墙旋转角在正负3点5度内() {
        val wall = MergeLayout.photoWallLayout(4, 1920, 1080, 99)
        wall.rotations.forEach { rot ->
            assertTrue("rotation=$rot", kotlin.math.abs(rot) <= 3.5)
        }
    }
}
