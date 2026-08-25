package com.moon.videomerger.editor.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.arthenica.ffmpegkit.FFmpegKit
import com.moon.videomerger.editor.data.WatermarkMode
import com.moon.videomerger.editor.data.WatermarkRegion
import com.moon.videomerger.util.MediaUtils
import java.io.File
import kotlin.math.abs

/**
 * 静态水印自动检测（支持移动水印）。
 *
 * 原理：水印在「足够短的时间窗内」是静止的——
 *  1. 把视频按时间切成若干窗口（默认 ≤5 个）
 *  2. 每个窗口内独立抽帧，逐像素算时间亮度标准差：静态区域 std≈0
 *  3. 低方差掩码 → 膨胀 → 连通域 → 外接矩形
 *  4. 过滤误判：
 *     - 环带检验：真水印贴在动态画面上（周围像素在变），
 *       若外扩环带大部分也是静态 → 是静止背景而非水印 → 剔除
 *     - 细长条剔除（上下黑边/字幕条）
 *     - 面积/靠边位置约束
 *  5. 跨窗口合并：位置相近（IoU 大）的框合并时间范围，
 *     位置不同的保留各自时间段 → 覆盖「前几秒左上、后几秒右下」的移动水印
 *
 * 局限：只能检测逐段静止的水印；平滑连续漂移的水印可能被拆成多段区域。
 */
object WatermarkDetector {

    private const val FRAMES_PER_WINDOW = 8
    // ★ 240 而非 180：抖音昵称等小字在 180 宽下降采样后边缘消失，无法通过边缘检验
    private const val ANALYSIS_WIDTH = 240
    private const val MAX_WINDOWS = 5
    private const val MAX_REGIONS = 6

    /**
     * 检测视频中的水印区域（含时间段，源视频秒）。
     * 检测不到返回空列表。
     */
    fun detect(context: Context, videoPath: String): List<WatermarkRegion> {
        val meta = MediaUtils.getVideoMeta(videoPath)
        if (meta.duration <= 0.5) return emptyList()

        // 时间窗：短视频单窗；长视频均分（最多 5 窗，每窗至少 2s）
        val windowCount = if (meta.duration <= 5.0) 1 else minOf(MAX_WINDOWS, (meta.duration / 3.0).toInt().coerceAtLeast(2))
        val windowLen = meta.duration / windowCount

        val frameDir = File(context.cacheDir, "wm_frames_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            // 候选：窗口索引 → 该窗内的框（归一化）
            data class Candidate(val window: Int, val box: DoubleArray)

            val candidates = mutableListOf<Candidate>()
            for (wIdx in 0 until windowCount) {
                val start = wIdx * windowLen
                val files = extractFrames(videoPath, frameDir, wIdx, start, windowLen)
                val bitmaps = files.mapNotNull { BitmapFactory.decodeFile(it.absolutePath) }
                if (bitmaps.size < 4) continue

                val w = bitmaps[0].width
                val h = bitmaps[0].height
                val frames = bitmaps.map { toLumaArray(it) }
                bitmaps.forEach { it.recycle() }

                analyze(frames, w, h).forEach { box ->
                    candidates.add(Candidate(wIdx, box))
                }
            }
            if (candidates.isEmpty()) return emptyList()

            // 跨窗合并：
            //  - 位置相近（IoU>0.5）→ 同一水印，合并时间窗口（并集）
            //  - 位置不同 → 各自独立区域，带各自时间段（覆盖移动水印）
            data class Merged(val box: DoubleArray, val windows: MutableSet<Int>)

            val mergedList = mutableListOf<Merged>()
            // 同窗内先按面积排序去重
            val perWindow = candidates.groupBy { it.window }
            perWindow.toSortedMap().forEach { (_, list) ->
                val sorted = list.sortedByDescending { it.box[2] * it.box[3] }
                for (c in sorted) {
                    val existing = mergedList.firstOrNull { iou(it.box, c.box) > 0.5 }
                    if (existing != null) {
                        existing.windows.add(c.window)
                    } else {
                        mergedList.add(Merged(c.box, mutableSetOf(c.window)))
                    }
                }
            }

            return mergedList
                .sortedByDescending { it.box[2] * it.box[3] }
                .take(MAX_REGIONS)
                .map { m ->
                    // 时间段 = 覆盖窗口的范围，前后各放宽半秒（水印切换有过渡）
                    val wIdxs = m.windows
                    val startW = wIdxs.min()
                    val endW = wIdxs.max()
                    val pad = windowLen * 0.15
                    val startTime = (startW * windowLen - pad).coerceAtLeast(0.0)
                    val endTime = ((endW + 1) * windowLen + pad).coerceAtMost(meta.duration)
                    WatermarkRegion(
                        x = m.box[0], y = m.box[1], w = m.box[2], h = m.box[3],
                        mode = WatermarkMode.BLUR,
                        strength = 14,
                        startTime = startTime,
                        endTime = if (startTime <= 0.01 && endTime >= meta.duration - 0.01) -1.0 else endTime,
                    )
                }
        } finally {
            frameDir.deleteRecursively()
        }
    }

    /** 抽取一个时间窗内的分析帧 */
    private fun extractFrames(
        videoPath: String,
        frameDir: File,
        windowIdx: Int,
        startSec: Double,
        lenSec: Double
    ): List<File> {
        FFmpegKit.execute(
            "-v error -ss ${startSec.fmt(2)} -t ${lenSec.fmt(2)} -i \"$videoPath\" " +
                "-vf \"fps=$FRAMES_PER_WINDOW/${lenSec.fmt(2)},scale=$ANALYSIS_WIDTH:-2\" " +
                "-frames:v $FRAMES_PER_WINDOW -q:v 3 \"${frameDir}/w${windowIdx}_%02d.jpg\""
        )
        return frameDir.listFiles()
            ?.filter { it.name.startsWith("w${windowIdx}_") }
            ?.sortedBy { it.name }
            .orEmpty()
    }

    private fun iou(a: DoubleArray, b: DoubleArray): Double {
        val ix = (a[0] + a[2]).coerceAtMost(b[0] + b[2]) - a[0].coerceAtLeast(b[0])
        val iy = (a[1] + a[3]).coerceAtMost(b[1] + b[3]) - a[1].coerceAtLeast(b[1])
        if (ix <= 0 || iy <= 0) return 0.0
        val inter = ix * iy
        return inter / (a[2] * a[3] + b[2] * b[3] - inter)
    }

    private fun toLumaArray(bmp: Bitmap): FloatArray {
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val out = FloatArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            out[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }
        return out
    }

    /**
     * 纯分析核心（可单元测试）：单时间窗内的静态水印框检测。
     *
     * @param frames 各帧亮度平面（行主序，长度 = w*h）
     * @return 候选区域列表，每项 [x, y, w, h]（归一化 0~1）
     */
    fun analyze(frames: List<FloatArray>, w: Int, h: Int): List<DoubleArray> {
        if (frames.size < 4 || w < 8 || h < 8) return emptyList()
        val n = frames.size
        val total = w * h

        // 1) 逐像素时间均值与标准差
        val mean = FloatArray(total)
        for (f in frames) for (i in 0 until total) mean[i] += f[i]
        for (i in 0 until total) mean[i] = mean[i] / n

        val std = FloatArray(total)
        for (f in frames) {
            for (i in 0 until total) {
                val d = f[i] - mean[i]
                std[i] += d * d
            }
        }
        for (i in 0 until total) std[i] = kotlin.math.sqrt(std[i] / n)

        // 2) 静态掩码；全画面基本静止（冻结帧/纯色片头）→ 放弃
        // ★ 阈值 5.5：半透明 logo 叠在波动水面/动态背景上时，
        //   像素方差会被背景「透」出来，7.0 会漏检；5.5 仍高于压缩噪声（~2-4）
        val staticThresh = 5.5f
        val mask = BooleanArray(total)
        var staticCount = 0
        for (i in 0 until total) {
            if (std[i] < staticThresh) {
                mask[i] = true
                staticCount++
            }
        }
        if (staticCount > total * 0.55) return emptyList()

        // 3) 双通道候选：
        //    A. 静态掩码（不透明水印/静止贴片）
        //    B. 均值图边缘聚集（★ 半透明水印：像素方差被透出的背景抬高，
        //       静态掩码抓不到，但 logo 恒定叠加会在时间均值图里留下「幽灵边缘」）
        val dilated = dilate(mask, w, h, radius = 2)
        val staticBoxes = connectedBoxes(dilated, w, h)

        val edgeMap = BooleanArray(total)
        for (y in 0 until h - 1) {
            for (x in 0 until w - 1) {
                val i = y * w + x
                val gx = abs(mean[i + 1] - mean[i])
                val gy = abs(mean[i + w] - mean[i])
                if (gx > 18 || gy > 18) edgeMap[i] = true
            }
        }
        val edgeBoxes = connectedBoxes(dilate(edgeMap, w, h, radius = 2), w, h)

        // 4) 过滤
        // ★ minArea 0.002：过滤均值图上零星噪声点（真实水印再小也有 0.5% 面积）
        val minArea = total * 0.002
        val maxArea = total * 0.12

        /**
         * 候选打分：通过全部过滤条件返回「内部边缘密度」（文字水印密度最高），
         * 任一条件不满足返回 -1。
         * ★ 排序用密度而非面积：天花板/暗部大片区域面积大但密度低，
         *   按面积排序会把真水印挤出前 N 名（漏检主因）。
         */
        fun scoreBox(b: IntArray, isEdgeChannel: Boolean): Double {
            val bw = b[2] - b[0]
            val bh = b[3] - b[1]
            val area = (bw * bh).toDouble()
            if (area !in minArea..maxArea) return -1.0
            // ★ 角落约束：水印几乎只出现在四角区域（平台 logo/昵称的固定习惯位），
            //   按候选框中心点判断——画面中部的内容（人物/主体）直接排除，大幅减少误报
            val cx = (b[0] + b[2]) / 2.0
            val cy = (b[1] + b[3]) / 2.0
            val inVerticalZone = cy < h * 0.32 || cy > h * 0.68
            val inHorizontalZone = cx < w * 0.62 || cx > w * 0.38
            if (!inVerticalZone || !inHorizontalZone) return -1.0
            // 通栏细长条剔除（上下黑边/字幕条）：横贯整宽且高度很小
            if (bw >= w * 0.95 && bh <= h * 0.08) return -1.0
            // ★ 密度统一在内缩 3px 的内部计算：
            //   框边界与背景的过渡带/静止块轮廓本身就是边缘线，不计入，
            //   这样「静止块的矩形轮廓」会被剔除，而文字水印笔画在内部仍密集
            val shrink = 3
            val inner = intArrayOf(
                b[0] + shrink, b[1] + shrink,
                (b[2] - shrink).coerceAtLeast(b[0] + shrink + 1),
                (b[3] - shrink).coerceAtLeast(b[1] + shrink + 1),
            )
            return if (isEdgeChannel) {
                // 边缘通道：内部边缘密度达到绝对阈值（文字/图形的边缘聚集）
                var inEdge = 0
                var innerTotal = 0
                for (y in inner[1] until inner[3]) for (x in inner[0] until inner[2]) {
                    innerTotal++
                    if (edgeMap[y * w + x]) inEdge++
                }
                val density = if (innerTotal > 0) inEdge.toDouble() / innerTotal else 0.0
                if (density >= 0.06) density else -1.0
            } else {
                // 静态通道：平坦块剔除——水印内部有笔画边缘，纯色墙/天花板/暗部没有
                // ★ 阈值 0.035：暗色天花板的微弱纹理在 0.015 下能蒙混过关
                val frac = edgeFraction(mean, w, h, inner)
                if (frac >= 0.035) frac else -1.0
            }
        }

        val candidates = staticBoxes.map { it to false } + edgeBoxes.map { it to true }
        return candidates
            .mapNotNull { (b, isEdgeChannel) ->
                val score = scoreBox(b, isEdgeChannel)
                if (score >= 0) Triple(b, isEdgeChannel, score) else null
            }
            // 跨通道去重（IoU 大的保留先出现的静态通道结果）
            .fold(mutableListOf<Triple<IntArray, Boolean, Double>>()) { acc, c ->
                if (acc.none { iouNorm(it.first, c.first, w, h) > 0.3 }) acc.add(c)
                acc
            }
            // ★ 按「内部边缘密度」降序——文字水印必然排在暗部纹理/天花板前面
            .sortedByDescending { it.third }
            .take(3)
            .map { (b, _, _) ->
                val x0 = b[0].coerceAtLeast(0)
                val y0 = b[1].coerceAtLeast(0)
                val x1 = b[2].coerceAtMost(w)
                val y1 = b[3].coerceAtMost(h)
                doubleArrayOf(
                    x0.toDouble() / w,
                    y0.toDouble() / h,
                    (x1 - x0).toDouble() / w,
                    (y1 - y0).toDouble() / h,
                )
            }
    }

    /** 像素坐标框的 IoU（用于跨通道去重） */
    private fun iouNorm(a: IntArray, b: IntArray, w: Int, h: Int): Double {
        val ix = (a[2].coerceAtMost(b[2]) - a[0].coerceAtLeast(b[0])).coerceAtLeast(0)
        val iy = (a[3].coerceAtMost(b[3]) - a[1].coerceAtLeast(b[1])).coerceAtLeast(0)
        val inter = (ix * iy).toDouble()
        val areaA = ((a[2] - a[0]) * (a[3] - a[1])).toDouble()
        val areaB = ((b[2] - b[0]) * (b[3] - b[1])).toDouble()
        val union = areaA + areaB - inter
        return if (union <= 0) 0.0 else inter / union
    }

    /**
     * 候选框内「有边缘的像素」占比（基于时间均值图的空间梯度）。
     * 水印（文字/图形）边缘占比高；纯色墙/天空/黑边 ≈ 0。
     * ★ 梯度阈值 9：半透明 logo 经时间均值后边缘变弱，15 会漏检
     */
    private fun edgeFraction(mean: FloatArray, w: Int, h: Int, box: IntArray): Double {
        val gradThresh = 9.0f
        var count = 0
        var total = 0
        for (y in box[1] until box[3]) {
            for (x in box[0] until box[2]) {
                if (x + 1 >= w || y + 1 >= h) continue
                val gx = abs(mean[y * w + x + 1] - mean[y * w + x])
                val gy = abs(mean[(y + 1) * w + x] - mean[y * w + x])
                total++
                if (gx > gradThresh || gy > gradThresh) count++
            }
        }
        if (total == 0) return 0.0
        return count.toDouble() / total
    }

    /** 布尔掩码方形膨胀 */
    internal fun dilate(mask: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray {
        val out = BooleanArray(mask.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!mask[y * w + x]) continue
                for (dy in -radius..radius) {
                    val ny = y + dy
                    if (ny < 0 || ny >= h) continue
                    for (dx in -radius..radius) {
                        val nx = x + dx
                        if (nx < 0 || nx >= w) continue
                        out[ny * w + nx] = true
                    }
                }
            }
        }
        return out
    }

    /** 4 邻接连通域，返回外接框 [x0,y0,x1,y1]（像素坐标）列表 */
    internal fun connectedBoxes(mask: BooleanArray, w: Int, h: Int): List<IntArray> {
        val visited = BooleanArray(mask.size)
        val boxes = mutableListOf<IntArray>()
        val stack = IntArray(mask.size)
        for (start in 0 until mask.size) {
            if (!mask[start] || visited[start]) continue
            var sp = 0
            stack[sp++] = start
            visited[start] = true
            var x0 = w; var y0 = h; var x1 = 0; var y1 = 0
            while (sp > 0) {
                val idx = stack[--sp]
                val x = idx % w
                val y = idx / w
                if (x < x0) x0 = x
                if (y < y0) y0 = y
                if (x > x1) x1 = x
                if (y > y1) y1 = y
                if (x > 0 && mask[idx - 1] && !visited[idx - 1]) { visited[idx - 1] = true; stack[sp++] = idx - 1 }
                if (x < w - 1 && mask[idx + 1] && !visited[idx + 1]) { visited[idx + 1] = true; stack[sp++] = idx + 1 }
                if (y > 0 && mask[idx - w] && !visited[idx - w]) { visited[idx - w] = true; stack[sp++] = idx - w }
                if (y < h - 1 && mask[idx + w] && !visited[idx + w]) { visited[idx + w] = true; stack[sp++] = idx + w }
            }
            boxes.add(intArrayOf(x0, y0, x1 + 1, y1 + 1))
        }
        return boxes
    }
}

