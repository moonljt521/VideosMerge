package com.moon.videomerger.inpaint

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 内容修复（去水印）纯算法内核 —— 无 Android 依赖，可在 JVM 单测中直接跑。
 *
 * 与 Python/OpenCV 参考实现对应：
 *  1. 在 bbox 外围干净像素上逐通道拟合 2D 三次多项式背景模型 M(x,y)
 *  2. 残差 res = frame - M，亮字形 > bright、暗投影 < -dark（限字形邻域），
 *     连通域过滤 + 膨胀得到静态遮罩
 *  3. 逐帧对遮罩内像素做 Telea 式 FMM 填充（距离序 + 方向/距离/等值线三重权重 + 梯度项）
 *
 * 遮罩只生成一次、全片复用（逐帧重检会把推近后的红/白物体误判为字形）。
 */

data class RectPx(val x: Int, val y: Int, val w: Int, val h: Int) {
    val right get() = x + w
    val bottom get() = y + h
    val area get() = max(w, 0) * max(h, 0)

    fun clampTo(frameW: Int, frameH: Int): RectPx {
        val x0 = x.coerceIn(0, frameW)
        val y0 = y.coerceIn(0, frameH)
        val x1 = right.coerceIn(0, frameW)
        val y1 = bottom.coerceIn(0, frameH)
        return RectPx(x0, y0, max(x1 - x0, 0), max(y1 - y0, 0))
    }

    fun evenAligned(frameW: Int, frameH: Int): RectPx {
        var x0 = (x / 2) * 2
        var y0 = (y / 2) * 2
        var x1 = ((right + 1) / 2) * 2
        var y1 = ((bottom + 1) / 2) * 2
        x0 = x0.coerceIn(0, frameW - 2)
        y0 = y0.coerceIn(0, frameH - 2)
        x1 = x1.coerceIn(x0 + 2, frameW)
        y1 = y1.coerceIn(y0 + 2, frameH)
        return RectPx(x0, y0, x1 - x0, y1 - y0)
    }
}

data class MaskParams(
    val pad: Int = 70,
    val probePad: Int = 30,
    val bright: Float = 16f,
    val dark: Float = 12f,
    val minArea: Int = 25,
    val nearKernel: Int = 17,
    val dilateKernel: Int = 13,
)

data class InpaintMask(
    val roi: RectPx,
    /** ROI 内逐像素遮罩，行主序，长度 = roi.w * roi.h */
    val mask: BooleanArray,
    val pixelCount: Int,
    /** 遮罩外接框（ROI 局部坐标） */
    val maskBox: RectPx?,
)

data class MaskBuildResult(val mask: InpaintMask?, val message: String? = null)

object InpaintMaskBuilder {

    fun roiFor(frameW: Int, frameH: Int, bbox: RectPx, pad: Int): RectPx {
        val expanded = RectPx(
            bbox.x - pad,
            bbox.y - pad,
            bbox.w + pad * 2,
            bbox.h + pad * 2,
        ).clampTo(frameW, frameH)
        return expanded.evenAligned(frameW, frameH)
    }

    /**
     * @param rgb 整帧 RGB 字节（行主序，长度 = frameW*frameH*3），显示方向
     * @param bbox 水印包围盒（显示方向像素坐标，建议略大于字形）
     */
    fun build(
        rgb: ByteArray,
        frameW: Int,
        frameH: Int,
        bbox: RectPx,
        params: MaskParams = MaskParams(),
    ): MaskBuildResult {
        val bb = bbox.clampTo(frameW, frameH)
        if (bb.area < 36) return MaskBuildResult(null, "水印区域太小，请重新框选")
        val roi = roiFor(frameW, frameH, bb, params.pad)
        val rw = roi.w
        val rh = roi.h
        if (rw < 16 || rh < 16) return MaskBuildResult(null, "水印区域超出画面")

        // bbox 在 ROI 内坐标
        val lx0 = bb.x - roi.x
        val ly0 = bb.y - roi.y
        val lx1 = lx0 + bb.w
        val ly1 = ly0 + bb.h

        // ---- ① 背景模型 ----
        val model = fitBackground(rgb, frameW, roi, lx0, ly0, lx1, ly1)
            ?: return MaskBuildResult(null, "水印周围背景过于复杂，无法建立背景模型")

        // ---- ② 残差 ----
        val lumRes = FloatArray(rw * rh)
        for (y in 0 until rh) {
            for (x in 0 until rw) {
                val fi = ((roi.y + y) * frameW + (roi.x + x)) * 3
                val mi = (y * rw + x) * 3
                val r = (rgb[fi].toInt() and 0xFF) - model[mi]
                val g = (rgb[fi + 1].toInt() and 0xFF) - model[mi + 1]
                val b = (rgb[fi + 2].toInt() and 0xFF) - model[mi + 2]
                lumRes[y * rw + x] = (r + g + b) / 3f
            }
        }

        // ---- ③ 遮罩（探测范围 = bbox ± probePad） ----
        val px0 = max(0, lx0 - params.probePad)
        val py0 = max(0, ly0 - params.probePad)
        val px1 = min(rw, lx1 + params.probePad)
        val py1 = min(rh, ly1 + params.probePad)
        if (px1 - px0 < 4 || py1 - py0 < 4) return MaskBuildResult(null, "水印区域超出画面")

        val brightSel = BooleanArray(rw * rh)
        val darkSel = BooleanArray(rw * rh)
        for (y in py0 until py1) {
            for (x in px0 until px1) {
                val v = lumRes[y * rw + x]
                if (v > params.bright) brightSel[y * rw + x] = true
                if (v < -params.dark) darkSel[y * rw + x] = true
            }
        }
        val core = filterComponents(brightSel, rw, rh, params.minArea)
        val shadow = filterComponents(darkSel, rw, rh, params.minArea)
        val near = dilate(core, rw, rh, params.nearKernel)
        val combined = BooleanArray(rw * rh)
        for (i in core.indices) combined[i] = core[i] || (shadow[i] && near[i])
        val mask = dilate(combined, rw, rh, params.dilateKernel)

        var count = 0
        var mx0 = Int.MAX_VALUE; var my0 = Int.MAX_VALUE
        var mx1 = -1; var my1 = -1
        for (y in 0 until rh) {
            for (x in 0 until rw) {
                if (mask[y * rw + x]) {
                    count++
                    if (x < mx0) mx0 = x
                    if (x > mx1) mx1 = x
                    if (y < my0) my0 = y
                    if (y > my1) my1 = y
                }
            }
        }
        if (count == 0) return MaskBuildResult(null, "没有找到可修复的水印像素，请调整水印框或换一帧")
        if (count > frameW * frameH * 0.15) {
            return MaskBuildResult(null, "遮罩异常偏大，水印背景可能过于复杂")
        }
        val box = RectPx(mx0, my0, mx1 - mx0 + 1, my1 - my0 + 1)
        return MaskBuildResult(InpaintMask(roi, mask, count, box))
    }

    // ---------------------------------------------------------------- 背景模型

    private const val TERMS = 10

    private fun fitBackground(
        rgb: ByteArray,
        frameW: Int,
        roi: RectPx,
        lx0: Int, ly0: Int, lx1: Int, ly1: Int,
    ): FloatArray? {
        val rw = roi.w
        val rh = roi.h
        val ata = Array(TERMS) { DoubleArray(TERMS) }
        val atb = Array(3) { DoubleArray(TERMS) }
        var samples = 0
        val basis = DoubleArray(TERMS)

        for (y in 0 until rh) {
            val ny = (y + 0.5) / rh
            for (x in 0 until rw) {
                // 只用 bbox 外的干净像素
                if (x in lx0 until lx1 && y in ly0 until ly1) continue
                val nx = (x + 0.5) / rw
                fillBasis(basis, nx, ny)
                val fi = ((roi.y + y) * frameW + (roi.x + x)) * 3
                val b0 = (rgb[fi].toInt() and 0xFF).toDouble()
                val b1 = (rgb[fi + 1].toInt() and 0xFF).toDouble()
                val b2 = (rgb[fi + 2].toInt() and 0xFF).toDouble()
                for (i in 0 until TERMS) {
                    val bi = basis[i]
                    for (j in i until TERMS) ata[i][j] += bi * basis[j]
                    atb[0][i] += bi * b0
                    atb[1][i] += bi * b1
                    atb[2][i] += bi * b2
                }
                samples++
            }
        }
        if (samples < 300) return null
        // 对称化
        for (i in 0 until TERMS) for (j in 0 until i) ata[i][j] = ata[j][i]

        val out = FloatArray(rw * rh * 3)
        for (c in 0..2) {
            val coef = solve(ata, atb[c]) ?: return null
            for (y in 0 until rh) {
                val ny = (y + 0.5) / rh
                for (x in 0 until rw) {
                    val nx = (x + 0.5) / rw
                    fillBasis(basis, nx, ny)
                    var v = 0.0
                    for (i in 0 until TERMS) v += coef[i] * basis[i]
                    out[(y * rw + x) * 3 + c] = v.toFloat()
                }
            }
        }
        return out
    }

    /** [1, x, y, x², xy, y², x³, x²y, xy², y³] */
    private fun fillBasis(b: DoubleArray, x: Double, y: Double) {
        b[0] = 1.0
        b[1] = x
        b[2] = y
        b[3] = x * x
        b[4] = x * y
        b[5] = y * y
        b[6] = x * x * x
        b[7] = x * x * y
        b[8] = x * y * y
        b[9] = y * y * y
    }

    /** 高斯消元（列主元），失败返回 null */
    private fun solve(a0: Array<DoubleArray>, b0: DoubleArray): DoubleArray? {
        val n = b0.size
        val a = Array(n) { a0[it].copyOf() }
        val b = b0.copyOf()
        val scale = (0 until n).sumOf { abs(a[it][it]) } / n + 1e-9
        for (i in 0 until n) a[i][i] += scale * 1e-9
        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) if (abs(a[r][col]) > abs(a[pivot][col])) pivot = r
            if (abs(a[pivot][col]) < 1e-12) return null
            if (pivot != col) {
                val t = a[pivot]; a[pivot] = a[col]; a[col] = t
                val tb = b[pivot]; b[pivot] = b[col]; b[col] = tb
            }
            val d = a[col][col]
            for (r in col + 1 until n) {
                val f = a[r][col] / d
                if (f == 0.0) continue
                for (c in col until n) a[r][c] -= f * a[col][c]
                b[r] -= f * b[col]
            }
        }
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var s = b[i]
            for (j in i + 1 until n) s -= a[i][j] * x[j]
            x[i] = s / a[i][i]
        }
        return x
    }

    // ---------------------------------------------------------------- 形态学

    private fun filterComponents(sel: BooleanArray, w: Int, h: Int, minArea: Int): BooleanArray {
        val out = BooleanArray(sel.size)
        val visited = BooleanArray(sel.size)
        val stack = IntArray(sel.size)
        val members = IntArray(sel.size)
        for (start in sel.indices) {
            if (!sel[start] || visited[start]) continue
            var sp = 0
            var mc = 0
            stack[sp++] = start
            visited[start] = true
            while (sp > 0) {
                val idx = stack[--sp]
                members[mc++] = idx
                val x = idx % w
                val y = idx / w
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= h) continue
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        if (nx < 0 || nx >= w) continue
                        val ni = ny * w + nx
                        if (sel[ni] && !visited[ni]) {
                            visited[ni] = true
                            stack[sp++] = ni
                        }
                    }
                }
            }
            if (mc >= minArea) for (k in 0 until mc) out[members[k]] = true
        }
        return out
    }

    private fun dilate(src: BooleanArray, w: Int, h: Int, kernel: Int): BooleanArray {
        val r = max(kernel / 2, 0)
        val out = BooleanArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!src[y * w + x]) continue
                val y0 = max(0, y - r); val y1 = min(h - 1, y + r)
                val x0 = max(0, x - r); val x1 = min(w - 1, x + r)
                for (ny in y0..y1) {
                    val row = ny * w
                    for (nx in x0..x1) out[row + nx] = true
                }
            }
        }
        return out
    }
}

/**
 * Telea 式 FMM 修复。遮罩静态，所以距离场、填充顺序、权重与梯度支撑全部预计算一次，
 * 每帧只做加权累加 —— 1080p 短视频单帧通常 < 5ms。
 */
class TeleaInpainter(
    private val w: Int,
    private val h: Int,
    mask: BooleanArray,
) {
    private val n = w * h
    private val maskCount: Int
    private val order: IntArray
    private val srcStart: IntArray
    private val srcIndex: IntArray
    private val srcWeight: FloatArray
    private val srcDx: FloatArray
    private val srcDy: FloatArray
    /** 每个像素四周「原始已知（遮罩外）」标志：bit0 左 bit1 右 bit2 上 bit3 下 */
    private val knownFlags: IntArray
    private val maskRef: BooleanArray

    init {
        require(mask.size == n) { "mask size mismatch" }
        maskRef = mask
        val dist = FloatArray(n)
        var mc = 0
        for (i in 0 until n) {
            if (mask[i]) { dist[i] = 1e9f; mc++ } else dist[i] = 0f
        }
        maskCount = mc
        require(mc > 0) { "empty mask" }

        // 两遍倒角距离（1 / √2），得到到最近已知像素的近似欧氏距离
        val diag = 1.41421356f
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (!mask[i]) continue
                var d = dist[i]
                if (x > 0) d = min(d, dist[i - 1] + 1f)
                if (y > 0) {
                    d = min(d, dist[i - w] + 1f)
                    if (x > 0) d = min(d, dist[i - w - 1] + diag)
                    if (x < w - 1) d = min(d, dist[i - w + 1] + diag)
                }
                dist[i] = d
            }
        }
        for (y in h - 1 downTo 0) {
            for (x in w - 1 downTo 0) {
                val i = y * w + x
                if (!mask[i]) continue
                var d = dist[i]
                if (x < w - 1) d = min(d, dist[i + 1] + 1f)
                if (y < h - 1) {
                    d = min(d, dist[i + w] + 1f)
                    if (x < w - 1) d = min(d, dist[i + w + 1] + diag)
                    if (x > 0) d = min(d, dist[i + w - 1] + diag)
                }
                dist[i] = d
            }
        }

        // 填充顺序：距离升序，同距离按索引稳定
        val keys = LongArray(mc)
        var k = 0
        for (i in 0 until n) if (mask[i]) {
            keys[k++] = ((dist[i] * 1024f).toLong().coerceAtLeast(0)) shl 32 or i.toLong()
        }
        keys.sort()
        order = IntArray(mc) { (keys[it] and 0xFFFFFFFFL).toInt() }
        val orderIndex = IntArray(n) { -1 }
        for (oi in order.indices) orderIndex[order[oi]] = oi

        // 每个像素的原始已知邻居标志
        knownFlags = IntArray(n)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            var f = 0
            if (x > 0 && !mask[i - 1]) f = f or 1
            if (x < w - 1 && !mask[i + 1]) f = f or 2
            if (y > 0 && !mask[i - w]) f = f or 4
            if (y < h - 1 && !mask[i + w]) f = f or 8
            knownFlags[i] = f
        }

        // 权重（静态）
        val counts = IntArray(mc)
        for (oi in 0 until mc) {
            val p = order[oi]
            val px = p % w
            val py = p / w
            var c = 0
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val qx = px + dx
                val qy = py + dy
                if (qx < 0 || qx >= w || qy < 0 || qy >= h) continue
                val q = qy * w + qx
                val usable = !mask[q] || orderIndex[q] < oi
                if (usable) c++
            }
            counts[oi] = c
        }
        srcStart = IntArray(mc + 1)
        for (i in 0 until mc) srcStart[i + 1] = srcStart[i] + counts[i]
        val total = srcStart[mc]
        srcIndex = IntArray(total)
        srcWeight = FloatArray(total)
        srcDx = FloatArray(total)
        srcDy = FloatArray(total)

        var pos = 0
        for (oi in 0 until mc) {
            val p = order[oi]
            val px = p % w
            val py = p / w
            val tp = dist[p]
            // 距离场梯度（指向遮罩内部）
            val gxT = (dist[yClamp(py) * w + xClamp(px + 1)] - dist[yClamp(py) * w + xClamp(px - 1)]) * 0.5f
            val gyT = (dist[yClamp(py + 1) * w + xClamp(px)] - dist[yClamp(py - 1) * w + xClamp(px)]) * 0.5f
            val glen = hypot(gxT, gyT)
            val nx = if (glen > 1e-5f) gxT / glen else 0f
            val ny = if (glen > 1e-5f) gyT / glen else 0f
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val qx = px + dx
                val qy = py + dy
                if (qx < 0 || qx >= w || qy < 0 || qy >= h) continue
                val q = qy * w + qx
                if (mask[q] && orderIndex[q] >= oi) continue
                // Telea 权重：方向 × 距离 × 等值线
                val rx = px - qx
                val ry = py - qy
                val r2 = (rx * rx + ry * ry).toFloat()
                val r = kotlin.math.sqrt(r2)
                val dir = if (glen > 1e-5f) abs(rx * nx + ry * ny) / r else 1f
                val dst = 1f / r2
                val lev = 1f / (1f + abs(tp - dist[q]))
                srcIndex[pos] = q
                srcWeight[pos] = dst * lev * dir.coerceAtLeast(0.05f) + 1e-8f
                srcDx[pos] = rx.toFloat()
                srcDy[pos] = ry.toFloat()
                pos++
            }
        }
        require(pos == total)
    }

    private fun xClamp(x: Int) = x.coerceIn(0, w - 1)
    private fun yClamp(y: Int) = y.coerceIn(0, h - 1)

    /**
     * 就地对 ROI 的 RGB 帧做修复（长度必须 = w*h*3）。
     * ★ 默认先在 5×5 平滑副本上填充再回填遮罩像素：颗粒噪声的单像素梯度
     *   乘以填充距离会放大成彩色条纹；平滑副本只提供低频颜色，避免噪声被“抽丝”放大。
     */
    fun inpaint(rgb: ByteArray, denoise: Boolean = true) {
        if (!denoise) { inpaintRaw(rgb); return }
        val smoothed = boxBlur(rgb, w, h, 2)
        inpaintRaw(smoothed)

        // 用遮罩外的高频残差估计颗粒强度：填充区补同强度静态颗粒，
        // 否则噪声背景上会出现一块「过于干净」的平滑补丁
        var sum0 = 0.0; var sum1 = 0.0; var sum2 = 0.0; var cnt = 0
        for (i in 0 until n) {
            if (maskRef[i]) continue
            sum0 += abs((rgb[i * 3].toInt() and 0xFF) - (smoothed[i * 3].toInt() and 0xFF))
            sum1 += abs((rgb[i * 3 + 1].toInt() and 0xFF) - (smoothed[i * 3 + 1].toInt() and 0xFF))
            sum2 += abs((rgb[i * 3 + 2].toInt() and 0xFF) - (smoothed[i * 3 + 2].toInt() and 0xFF))
            cnt++
        }
        val sigma = if (cnt > 0) floatArrayOf(
            min(sum0 / cnt * 1.25, 12.0).toFloat(),
            min(sum1 / cnt * 1.25, 12.0).toFloat(),
            min(sum2 / cnt * 1.25, 12.0).toFloat(),
        ) else floatArrayOf(0f, 0f, 0f)

        for (i in 0 until n) {
            if (!maskRef[i]) continue
            if (sigma[0] < 0.8f && sigma[1] < 0.8f && sigma[2] < 0.8f) {
                rgb[i * 3] = smoothed[i * 3]
                rgb[i * 3 + 1] = smoothed[i * 3 + 1]
                rgb[i * 3 + 2] = smoothed[i * 3 + 2]
                continue
            }
            val x = i % w; val y = i / w
            for (c in 0..2) {
                val g = grainNoise(x, y, c) * sigma[c] * 2.4f
                rgb[i * 3 + c] = clampByte((smoothed[i * 3 + c].toInt() and 0xFF) + g)
            }
        }
    }

    /** 静态颗粒（坐标哈希，避免逐帧闪动），三角分布 [-1,1] */
    private fun grainNoise(x: Int, y: Int, c: Int): Float {
        var h = (x * 73856093) xor (y * 19349663) xor (c * 83492791)
        h = h xor (h ushr 13); h *= 1274126177; h = h xor (h ushr 16)
        val u1 = (h and 0xFFFF) / 65535f
        h = h * 1664525 + 1013904223; h = h xor (h ushr 15)
        val u2 = (h and 0xFFFF) / 65535f
        return u1 + u2 - 1f
    }

    private fun inpaintRaw(rgb: ByteArray) {
        require(rgb.size == n * 3) { "frame size mismatch" }
        val known = knownFlags
        for (oi in 0 until maskCount) {
            val p = order[oi]
            val start = srcStart[oi]
            val end = srcStart[oi + 1]
            if (start == end) continue
            var ar = 0f; var ag = 0f; var ab = 0f; var ws = 0f
            for (j in start until end) {
                val q = srcIndex[j]
                val wgt = srcWeight[j]
                val dx = srcDx[j]
                val dy = srcDy[j]
                val qb = q * 3
                val qr = (rgb[qb].toInt() and 0xFF).toFloat()
                val qg = (rgb[qb + 1].toInt() and 0xFF).toFloat()
                val qbl = (rgb[qb + 2].toInt() and 0xFF).toFloat()
                // 梯度只用「原始已知（遮罩外）」邻居，避免读到尚未填充的占位值
                val f = known[q]
                var gxR = 0f; var gxG = 0f; var gxB = 0f
                var gyR = 0f; var gyG = 0f; var gyB = 0f
                when {
                    (f and 1) != 0 && (f and 2) != 0 -> {
                        val l = (q - 1) * 3; val r = (q + 1) * 3
                        gxR = ((rgb[r].toInt() and 0xFF) - (rgb[l].toInt() and 0xFF)) * 0.5f
                        gxG = ((rgb[r + 1].toInt() and 0xFF) - (rgb[l + 1].toInt() and 0xFF)) * 0.5f
                        gxB = ((rgb[r + 2].toInt() and 0xFF) - (rgb[l + 2].toInt() and 0xFF)) * 0.5f
                    }
                    (f and 2) != 0 -> {
                        val r = (q + 1) * 3
                        gxR = (rgb[r].toInt() and 0xFF) - qr
                        gxG = (rgb[r + 1].toInt() and 0xFF) - qg
                        gxB = (rgb[r + 2].toInt() and 0xFF) - qbl
                    }
                    (f and 1) != 0 -> {
                        val l = (q - 1) * 3
                        gxR = qr - (rgb[l].toInt() and 0xFF)
                        gxG = qg - (rgb[l + 1].toInt() and 0xFF)
                        gxB = qbl - (rgb[l + 2].toInt() and 0xFF)
                    }
                }
                when {
                    (f and 4) != 0 && (f and 8) != 0 -> {
                        val u = (q - w) * 3; val dn = (q + w) * 3
                        gyR = ((rgb[dn].toInt() and 0xFF) - (rgb[u].toInt() and 0xFF)) * 0.5f
                        gyG = ((rgb[dn + 1].toInt() and 0xFF) - (rgb[u + 1].toInt() and 0xFF)) * 0.5f
                        gyB = ((rgb[dn + 2].toInt() and 0xFF) - (rgb[u + 2].toInt() and 0xFF)) * 0.5f
                    }
                    (f and 8) != 0 -> {
                        val dn = (q + w) * 3
                        gyR = (rgb[dn].toInt() and 0xFF) - qr
                        gyG = (rgb[dn + 1].toInt() and 0xFF) - qg
                        gyB = (rgb[dn + 2].toInt() and 0xFF) - qbl
                    }
                    (f and 4) != 0 -> {
                        val u = (q - w) * 3
                        gyR = qr - (rgb[u].toInt() and 0xFF)
                        gyG = qg - (rgb[u + 1].toInt() and 0xFF)
                        gyB = qbl - (rgb[u + 2].toInt() and 0xFF)
                    }
                }
                // ★ 梯度项限幅：颗粒噪声的单像素梯度乘以填充距离会放大成彩色条纹
                val tr = (gxR * dx + gyR * dy).coerceIn(-12f, 12f)
                val tg = (gxG * dx + gyG * dy).coerceIn(-12f, 12f)
                val tb = (gxB * dx + gyB * dy).coerceIn(-12f, 12f)
                ar += wgt * (qr + tr)
                ag += wgt * (qg + tg)
                ab += wgt * (qbl + tb)
                ws += wgt
            }
            if (ws > 1e-6f) {
                val pb = p * 3
                rgb[pb] = clampByte(ar / ws)
                rgb[pb + 1] = clampByte(ag / ws)
                rgb[pb + 2] = clampByte(ab / ws)
            }
        }
    }

    private fun clampByte(v: Float): Byte {
        val i = v.roundToInt()
        return (if (i < 0) 0 else if (i > 255) 255 else i).toByte()
    }

    /** 可分离 box blur（先横后纵），比二维核快约 5 倍 */
    private fun boxBlur(src: ByteArray, w: Int, h: Int, radius: Int): ByteArray {
        if (radius <= 0) return src.copyOf()
        val tmp = ByteArray(src.size)
        val out = ByteArray(src.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val x0 = max(0, x - radius); val x1 = min(w - 1, x + radius)
                val cnt = x1 - x0 + 1
                var s0 = 0; var s1 = 0; var s2 = 0
                for (xx in x0..x1) {
                    val i = (row + xx) * 3
                    s0 += src[i].toInt() and 0xFF
                    s1 += src[i + 1].toInt() and 0xFF
                    s2 += src[i + 2].toInt() and 0xFF
                }
                val o = (row + x) * 3
                tmp[o] = (s0 / cnt).toByte()
                tmp[o + 1] = (s1 / cnt).toByte()
                tmp[o + 2] = (s2 / cnt).toByte()
            }
        }
        for (y in 0 until h) {
            val y0 = max(0, y - radius); val y1 = min(h - 1, y + radius)
            val cnt = y1 - y0 + 1
            for (x in 0 until w) {
                var s0 = 0; var s1 = 0; var s2 = 0
                for (yy in y0..y1) {
                    val i = (yy * w + x) * 3
                    s0 += tmp[i].toInt() and 0xFF
                    s1 += tmp[i + 1].toInt() and 0xFF
                    s2 += tmp[i + 2].toInt() and 0xFF
                }
                val o = (y * w + x) * 3
                out[o] = (s0 / cnt).toByte()
                out[o + 1] = (s1 / cnt).toByte()
                out[o + 2] = (s2 / cnt).toByte()
            }
        }
        return out
    }
}
