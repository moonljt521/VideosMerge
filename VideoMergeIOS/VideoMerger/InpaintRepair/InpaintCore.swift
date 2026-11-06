//
//  InpaintCore.swift
//  VideoMerger
//
//  内容修复（去水印）纯算法内核 —— 与 Android InpaintCore.kt / Python 参考实现同源。
//  1. bbox 外围干净像素逐通道拟合 2D 三次多项式背景模型
//  2. 残差提取亮字形/暗投影 → 连通域过滤 + 膨胀 → 静态遮罩（全片复用）
//  3. Telea 式 FMM 填充：距离序 + 方向/距离/等值线权重 + 梯度项
//

import Foundation

struct InpaintRect: Equatable {
    var x: Int
    var y: Int
    var w: Int
    var h: Int

    var right: Int { x + w }
    var bottom: Int { y + h }
    var area: Int { max(w, 0) * max(h, 0) }

    func clamped(toW frameW: Int, h frameH: Int) -> InpaintRect {
        let x0 = min(max(x, 0), frameW)
        let y0 = min(max(y, 0), frameH)
        let x1 = min(max(right, 0), frameW)
        let y1 = min(max(bottom, 0), frameH)
        return InpaintRect(x: x0, y: y0, w: max(x1 - x0, 0), h: max(y1 - y0, 0))
    }

    func evenAligned(frameW: Int, frameH: Int) -> InpaintRect {
        var x0 = (x / 2) * 2
        var y0 = (y / 2) * 2
        var x1 = ((right + 1) / 2) * 2
        var y1 = ((bottom + 1) / 2) * 2
        x0 = min(max(x0, 0), max(frameW - 2, 0))
        y0 = min(max(y0, 0), max(frameH - 2, 0))
        x1 = min(max(x1, x0 + 2), frameW)
        y1 = min(max(y1, y0 + 2), frameH)
        return InpaintRect(x: x0, y: y0, w: x1 - x0, h: y1 - y0)
    }
}

struct InpaintMaskParams {
    var pad = 70
    var probePad = 30
    var bright: Float = 16
    var dark: Float = 12
    var minArea = 25
    var nearKernel = 17
    var dilateKernel = 13
}

struct InpaintMask {
    let roi: InpaintRect
    /// ROI 行主序，长度 = roi.w * roi.h
    let mask: [Bool]
    let pixelCount: Int
    let maskBox: InpaintRect?
}

struct InpaintMaskResult {
    let mask: InpaintMask?
    let message: String?
}

enum InpaintMaskBuilder {

    static func roiFor(frameW: Int, frameH: Int, bbox: InpaintRect, pad: Int) -> InpaintRect {
        let expanded = InpaintRect(x: bbox.x - pad, y: bbox.y - pad,
                                   w: bbox.w + pad * 2, h: bbox.h + pad * 2)
            .clamped(toW: frameW, h: frameH)
        return expanded.evenAligned(frameW: frameW, frameH: frameH)
    }

    static func build(rgb: [UInt8],
                      frameW: Int,
                      frameH: Int,
                      bbox: InpaintRect,
                      params: InpaintMaskParams = InpaintMaskParams()) -> InpaintMaskResult {
        guard rgb.count >= frameW * frameH * 3 else {
            return InpaintMaskResult(mask: nil, message: "画面数据不完整")
        }
        let bb = bbox.clamped(toW: frameW, h: frameH)
        guard bb.area >= 36 else { return InpaintMaskResult(mask: nil, message: "水印区域太小，请重新框选") }
        let roi = roiFor(frameW: frameW, frameH: frameH, bbox: bb, pad: params.pad)
        let rw = roi.w, rh = roi.h
        guard rw >= 16, rh >= 16 else { return InpaintMaskResult(mask: nil, message: "水印区域超出画面") }

        let lx0 = bb.x - roi.x, ly0 = bb.y - roi.y
        let lx1 = lx0 + bb.w, ly1 = ly0 + bb.h

        guard let model = fitBackground(rgb: rgb, frameW: frameW, roi: roi,
                                        lx0: lx0, ly0: ly0, lx1: lx1, ly1: ly1) else {
            return InpaintMaskResult(mask: nil, message: "水印周围背景过于复杂，无法建立背景模型")
        }

        var lumRes = [Float](repeating: 0, count: rw * rh)
        for y in 0..<rh {
            for x in 0..<rw {
                let fi = ((roi.y + y) * frameW + (roi.x + x)) * 3
                let mi = (y * rw + x) * 3
                let r = Float(rgb[fi]) - model[mi]
                let g = Float(rgb[fi + 1]) - model[mi + 1]
                let b = Float(rgb[fi + 2]) - model[mi + 2]
                lumRes[y * rw + x] = (r + g + b) / 3
            }
        }

        let px0 = max(0, lx0 - params.probePad)
        let py0 = max(0, ly0 - params.probePad)
        let px1 = min(rw, lx1 + params.probePad)
        let py1 = min(rh, ly1 + params.probePad)
        guard px1 - px0 >= 4, py1 - py0 >= 4 else {
            return InpaintMaskResult(mask: nil, message: "水印区域超出画面")
        }

        var brightSel = [Bool](repeating: false, count: rw * rh)
        var darkSel = [Bool](repeating: false, count: rw * rh)
        for y in py0..<py1 {
            for x in px0..<px1 {
                let v = lumRes[y * rw + x]
                if v > params.bright { brightSel[y * rw + x] = true }
                if v < -params.dark { darkSel[y * rw + x] = true }
            }
        }
        let core = filterComponents(brightSel, w: rw, h: rh, minArea: params.minArea)
        let shadow = filterComponents(darkSel, w: rw, h: rh, minArea: params.minArea)
        let near = dilate(core, w: rw, h: rh, kernel: params.nearKernel)
        var combined = [Bool](repeating: false, count: rw * rh)
        for i in 0..<combined.count { combined[i] = core[i] || (shadow[i] && near[i]) }
        let mask = dilate(combined, w: rw, h: rh, kernel: params.dilateKernel)

        var count = 0
        var mx0 = Int.max, my0 = Int.max, mx1 = -1, my1 = -1
        for y in 0..<rh {
            for x in 0..<rw where mask[y * rw + x] {
                count += 1
                if x < mx0 { mx0 = x }
                if x > mx1 { mx1 = x }
                if y < my0 { my0 = y }
                if y > my1 { my1 = y }
            }
        }
        guard count > 0 else {
            return InpaintMaskResult(mask: nil, message: "没有找到可修复的水印像素，请调整水印框或换一帧")
        }
        guard count <= frameW * frameH / 100 * 15 else {
            return InpaintMaskResult(mask: nil, message: "遮罩异常偏大，水印背景可能过于复杂")
        }
        let box = InpaintRect(x: mx0, y: my0, w: mx1 - mx0 + 1, h: my1 - my0 + 1)
        return InpaintMaskResult(mask: InpaintMask(roi: roi, mask: mask, pixelCount: count, maskBox: box),
                                 message: nil)
    }

    // MARK: - 背景模型

    private static let terms = 10

    private static func fitBackground(rgb: [UInt8], frameW: Int, roi: InpaintRect,
                                      lx0: Int, ly0: Int, lx1: Int, ly1: Int) -> [Float]? {
        let rw = roi.w, rh = roi.h
        var ata = [[Double]](repeating: [Double](repeating: 0, count: terms), count: terms)
        var atb = [[Double]](repeating: [Double](repeating: 0, count: terms), count: 3)
        var samples = 0
        var basis = [Double](repeating: 0, count: terms)

        for y in 0..<rh {
            let ny = (Double(y) + 0.5) / Double(rh)
            for x in 0..<rw {
                if x >= lx0 && x < lx1 && y >= ly0 && y < ly1 { continue }
                let nx = (Double(x) + 0.5) / Double(rw)
                fillBasis(&basis, x: nx, y: ny)
                let fi = ((roi.y + y) * frameW + (roi.x + x)) * 3
                let b0 = Double(rgb[fi])
                let b1 = Double(rgb[fi + 1])
                let b2 = Double(rgb[fi + 2])
                for i in 0..<terms {
                    let bi = basis[i]
                    for j in i..<terms { ata[i][j] += bi * basis[j] }
                    atb[0][i] += bi * b0
                    atb[1][i] += bi * b1
                    atb[2][i] += bi * b2
                }
                samples += 1
            }
        }
        guard samples >= 300 else { return nil }
        for i in 0..<terms { for j in 0..<i { ata[i][j] = ata[j][i] } }

        var out = [Float](repeating: 0, count: rw * rh * 3)
        for c in 0..<3 {
            guard let coef = solve(ata, atb[c]) else { return nil }
            for y in 0..<rh {
                let ny = (Double(y) + 0.5) / Double(rh)
                for x in 0..<rw {
                    let nx = (Double(x) + 0.5) / Double(rw)
                    fillBasis(&basis, x: nx, y: ny)
                    var v = 0.0
                    for i in 0..<terms { v += coef[i] * basis[i] }
                    out[(y * rw + x) * 3 + c] = Float(v)
                }
            }
        }
        return out
    }

    private static func fillBasis(_ b: inout [Double], x: Double, y: Double) {
        b[0] = 1
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

    private static func solve(_ a0: [[Double]], _ b0: [Double]) -> [Double]? {
        let n = b0.count
        var a = a0
        var b = b0
        var scale = 0.0
        for i in 0..<n { scale += abs(a[i][i]) }
        scale = scale / Double(n) + 1e-9
        for i in 0..<n { a[i][i] += scale * 1e-9 }
        for col in 0..<n {
            var pivot = col
            for r in (col + 1)..<n where abs(a[r][col]) > abs(a[pivot][col]) { pivot = r }
            if abs(a[pivot][col]) < 1e-12 { return nil }
            if pivot != col {
                a.swapAt(pivot, col)
                b.swapAt(pivot, col)
            }
            let d = a[col][col]
            for r in (col + 1)..<n {
                let f = a[r][col] / d
                if f == 0 { continue }
                for c in col..<n { a[r][c] -= f * a[col][c] }
                b[r] -= f * b[col]
            }
        }
        var x = [Double](repeating: 0, count: n)
        for i in stride(from: n - 1, through: 0, by: -1) {
            var s = b[i]
            for j in (i + 1)..<n { s -= a[i][j] * x[j] }
            x[i] = s / a[i][i]
        }
        return x
    }

    // MARK: - 形态学

    private static func filterComponents(_ sel: [Bool], w: Int, h: Int, minArea: Int) -> [Bool] {
        var out = [Bool](repeating: false, count: sel.count)
        var visited = [Bool](repeating: false, count: sel.count)
        var stack = [Int](repeating: 0, count: sel.count)
        var members = [Int](repeating: 0, count: sel.count)
        for start in 0..<sel.count {
            if !sel[start] || visited[start] { continue }
            var sp = 0, mc = 0
            stack[sp] = start; sp += 1
            visited[start] = true
            while sp > 0 {
                sp -= 1
                let idx = stack[sp]
                members[mc] = idx; mc += 1
                let x = idx % w, y = idx / w
                for dy in -1...1 {
                    let ny = y + dy
                    if ny < 0 || ny >= h { continue }
                    for dx in -1...1 {
                        if dx == 0 && dy == 0 { continue }
                        let nx = x + dx
                        if nx < 0 || nx >= w { continue }
                        let ni = ny * w + nx
                        if sel[ni] && !visited[ni] {
                            visited[ni] = true
                            stack[sp] = ni; sp += 1
                        }
                    }
                }
            }
            if mc >= minArea { for k in 0..<mc { out[members[k]] = true } }
        }
        return out
    }

    private static func dilate(_ src: [Bool], w: Int, h: Int, kernel: Int) -> [Bool] {
        let r = max(kernel / 2, 0)
        var out = [Bool](repeating: false, count: src.count)
        for y in 0..<h {
            for x in 0..<w where src[y * w + x] {
                let y0 = max(0, y - r), y1 = min(h - 1, y + r)
                let x0 = max(0, x - r), x1 = min(w - 1, x + r)
                for ny in y0...y1 {
                    let row = ny * w
                    for nx in x0...x1 { out[row + nx] = true }
                }
            }
        }
        return out
    }
}

/// Telea 式 FMM 修复（遮罩静态：距离场/顺序/权重/梯度支撑只算一次）
final class TeleaInpainter {

    private let w: Int
    private let h: Int
    private let n: Int
    private let maskCount: Int
    private let order: [Int]
    private let srcStart: [Int]
    private let srcIndex: [Int32]
    private let srcWeight: [Float]
    private let srcDx: [Float]
    private let srcDy: [Float]
    /// bit0 左 / bit1 右 / bit2 上 / bit3 下（仅遮罩外原始像素）
    private let knownFlags: [UInt8]
    private let maskRef: [Bool]

    init(w: Int, h: Int, mask: [Bool]) {
        precondition(mask.count == w * h)
        self.w = w; self.h = h; self.n = w * h
        self.maskRef = mask
        var dist = [Float](repeating: 0, count: n)
        var mc = 0
        for i in 0..<n {
            if mask[i] { dist[i] = 1e9; mc += 1 }
        }
        precondition(mc > 0)
        maskCount = mc

        let diag: Float = 1.41421356
        for y in 0..<h {
            for x in 0..<w {
                let i = y * w + x
                if !mask[i] { continue }
                var d = dist[i]
                if x > 0 { d = min(d, dist[i - 1] + 1) }
                if y > 0 {
                    d = min(d, dist[i - w] + 1)
                    if x > 0 { d = min(d, dist[i - w - 1] + diag) }
                    if x < w - 1 { d = min(d, dist[i - w + 1] + diag) }
                }
                dist[i] = d
            }
        }
        for y in stride(from: h - 1, through: 0, by: -1) {
            for x in stride(from: w - 1, through: 0, by: -1) {
                let i = y * w + x
                if !mask[i] { continue }
                var d = dist[i]
                if x < w - 1 { d = min(d, dist[i + 1] + 1) }
                if y < h - 1 {
                    d = min(d, dist[i + w] + 1)
                    if x < w - 1 { d = min(d, dist[i + w + 1] + diag) }
                    if x > 0 { d = min(d, dist[i + w - 1] + diag) }
                }
                dist[i] = d
            }
        }

        var keys = [Int64](repeating: 0, count: mc)
        var k = 0
        for i in 0..<n where mask[i] {
            keys[k] = (Int64(max(dist[i], 0) * 1024) << 32) | Int64(i)
            k += 1
        }
        keys.sort()
        order = keys.map { Int($0 & 0xFFFF_FFFF) }

        var orderIndex = [Int](repeating: -1, count: n)
        for oi in 0..<order.count { orderIndex[order[oi]] = oi }

        var flags = [UInt8](repeating: 0, count: n)
        for y in 0..<h {
            for x in 0..<w {
                let i = y * w + x
                var f: UInt8 = 0
                if x > 0 && !mask[i - 1] { f |= 1 }
                if x < w - 1 && !mask[i + 1] { f |= 2 }
                if y > 0 && !mask[i - w] { f |= 4 }
                if y < h - 1 && !mask[i + w] { f |= 8 }
                flags[i] = f
            }
        }
        knownFlags = flags

        var counts = [Int](repeating: 0, count: mc)
        for oi in 0..<mc {
            let p = order[oi]
            let px = p % w, py = p / w
            var c = 0
            for dy in -1...1 {
                for dx in -1...1 {
                    if dx == 0 && dy == 0 { continue }
                    let qx = px + dx, qy = py + dy
                    if qx < 0 || qx >= w || qy < 0 || qy >= h { continue }
                    let q = qy * w + qx
                    if !mask[q] || orderIndex[q] < oi { c += 1 }
                }
            }
            counts[oi] = c
        }
        var start = [Int](repeating: 0, count: mc + 1)
        for i in 0..<mc { start[i + 1] = start[i] + counts[i] }
        let total = start[mc]
        var sIndex = [Int32](repeating: 0, count: total)
        var sWeight = [Float](repeating: 0, count: total)
        var sDx = [Float](repeating: 0, count: total)
        var sDy = [Float](repeating: 0, count: total)

        var pos = 0
        for oi in 0..<mc {
            let p = order[oi]
            let px = p % w, py = p / w
            let tp = dist[p]
            let gxT = (dist[min(py, h - 1) * w + min(px + 1, w - 1)] - dist[min(py, h - 1) * w + max(px - 1, 0)]) * 0.5
            let gyT = (dist[min(py + 1, h - 1) * w + min(px, w - 1)] - dist[max(py - 1, 0) * w + min(px, w - 1)]) * 0.5
            let glen = (gxT * gxT + gyT * gyT).squareRoot()
            let nx = glen > 1e-5 ? gxT / glen : 0
            let ny = glen > 1e-5 ? gyT / glen : 0
            for dy in -1...1 {
                for dx in -1...1 {
                    if dx == 0 && dy == 0 { continue }
                    let qx = px + dx, qy = py + dy
                    if qx < 0 || qx >= w || qy < 0 || qy >= h { continue }
                    let q = qy * w + qx
                    if mask[q] && orderIndex[q] >= oi { continue }
                    let rx = Float(px - qx), ry = Float(py - qy)
                    let r2 = rx * rx + ry * ry
                    let r = r2.squareRoot()
                    let dir = glen > 1e-5 ? abs(rx * nx + ry * ny) / r : 1
                    let dst = 1 / r2
                    let lev = 1 / (1 + abs(tp - dist[q]))
                    sIndex[pos] = Int32(q)
                    sWeight[pos] = dst * lev * max(dir, 0.05) + 1e-8
                    sDx[pos] = rx
                    sDy[pos] = ry
                    pos += 1
                }
            }
        }
        precondition(pos == total)
        srcStart = start
        srcIndex = sIndex
        srcWeight = sWeight
        srcDx = sDx
        srcDy = sDy
    }

    /// 就地对 ROI 的 RGB 帧做修复（长度必须 = w*h*3）。
    /// ★ 默认先在 5×5 平滑副本上填充再回填遮罩像素：颗粒噪声的单像素梯度
    ///   乘以填充距离会放大成彩色条纹；平滑副本只提供低频颜色，避免噪声被“抽丝”放大。
    func inpaint(_ rgb: inout [UInt8], denoise: Bool = true) {
        guard denoise else { inpaintRaw(&rgb); return }
        var smoothed = boxBlur(rgb, w: w, h: h, radius: 2)
        inpaintRaw(&smoothed)

        // 用遮罩外的高频残差估计颗粒强度：填充区补同强度静态颗粒，
        // 否则噪声背景上会出现一块「过于干净」的平滑补丁
        var sumAbs0: Double = 0, sumAbs1: Double = 0, sumAbs2: Double = 0
        var cnt = 0
        for i in 0..<n where !maskRef[i] {
            sumAbs0 += abs(Double(rgb[i * 3]) - Double(smoothed[i * 3]))
            sumAbs1 += abs(Double(rgb[i * 3 + 1]) - Double(smoothed[i * 3 + 1]))
            sumAbs2 += abs(Double(rgb[i * 3 + 2]) - Double(smoothed[i * 3 + 2]))
            cnt += 1
        }
        var sigma = [Float](repeating: 0, count: 3)
        if cnt > 0 {
            sigma[0] = Float(min(sumAbs0 / Double(cnt) * 1.25, 12))
            sigma[1] = Float(min(sumAbs1 / Double(cnt) * 1.25, 12))
            sigma[2] = Float(min(sumAbs2 / Double(cnt) * 1.25, 12))
        }
        for i in 0..<n where maskRef[i] {
            if sigma[0] < 0.8 && sigma[1] < 0.8 && sigma[2] < 0.8 {
                rgb[i * 3] = smoothed[i * 3]
                rgb[i * 3 + 1] = smoothed[i * 3 + 1]
                rgb[i * 3 + 2] = smoothed[i * 3 + 2]
                continue
            }
            let x = i % w, y = i / w
            for c in 0..<3 {
                let g = grainNoise(x, y, c) * sigma[c] * 2.4
                rgb[i * 3 + c] = clampByte(Float(smoothed[i * 3 + c]) + g)
            }
        }
    }

    /// 静态颗粒（坐标哈希，避免逐帧闪动），三角分布 [-1,1]
    private func grainNoise(_ x: Int, _ y: Int, _ c: Int) -> Float {
        var h = UInt32(truncatingIfNeeded: x &* 73856093 ^ y &* 19349663 ^ c &* 83492791)
        h ^= h >> 13; h = h &* 1274126177; h ^= h >> 16
        let u1 = Float(h & 0xFFFF) / 65535
        h = h &* 1664525 &+ 1013904223; h ^= h >> 15
        let u2 = Float(h & 0xFFFF) / 65535
        return u1 + u2 - 1
    }

    private func inpaintRaw(_ rgb: inout [UInt8]) {
        precondition(rgb.count == n * 3)
        let flags = knownFlags
        rgb.withUnsafeMutableBufferPointer { buf in
            srcStart.withUnsafeBufferPointer { startBuf in
                srcIndex.withUnsafeBufferPointer { idxBuf in
                    srcWeight.withUnsafeBufferPointer { wBuf in
                        srcDx.withUnsafeBufferPointer { dxBuf in
                            srcDy.withUnsafeBufferPointer { dyBuf in
                                for oi in 0..<maskCount {
                                    let p = order[oi]
                                    let start = startBuf[oi], end = startBuf[oi + 1]
                                    if start == end { continue }
                                    var ar: Float = 0, ag: Float = 0, ab: Float = 0, ws: Float = 0
                                    for j in start..<end {
                                        let q = Int(idxBuf[j])
                                        let wgt = wBuf[j]
                                        let dx = dxBuf[j], dy = dyBuf[j]
                                        let qb = q * 3
                                        let qr = Float(buf[qb])
                                        let qg = Float(buf[qb + 1])
                                        let qbl = Float(buf[qb + 2])
                                        let f = flags[q]
                                        var gxR: Float = 0, gxG: Float = 0, gxB: Float = 0
                                        var gyR: Float = 0, gyG: Float = 0, gyB: Float = 0
                                        if (f & 3) == 3 {
                                            let l = (q - 1) * 3, r = (q + 1) * 3
                                            gxR = (Float(buf[r]) - Float(buf[l])) * 0.5
                                            gxG = (Float(buf[r + 1]) - Float(buf[l + 1])) * 0.5
                                            gxB = (Float(buf[r + 2]) - Float(buf[l + 2])) * 0.5
                                        } else if (f & 2) != 0 {
                                            let r = (q + 1) * 3
                                            gxR = Float(buf[r]) - qr
                                            gxG = Float(buf[r + 1]) - qg
                                            gxB = Float(buf[r + 2]) - qbl
                                        } else if (f & 1) != 0 {
                                            let l = (q - 1) * 3
                                            gxR = qr - Float(buf[l])
                                            gxG = qg - Float(buf[l + 1])
                                            gxB = qbl - Float(buf[l + 2])
                                        }
                                        if (f & 12) == 12 {
                                            let u = (q - w) * 3, dn = (q + w) * 3
                                            gyR = (Float(buf[dn]) - Float(buf[u])) * 0.5
                                            gyG = (Float(buf[dn + 1]) - Float(buf[u + 1])) * 0.5
                                            gyB = (Float(buf[dn + 2]) - Float(buf[u + 2])) * 0.5
                                        } else if (f & 8) != 0 {
                                            let dn = (q + w) * 3
                                            gyR = Float(buf[dn]) - qr
                                            gyG = Float(buf[dn + 1]) - qg
                                            gyB = Float(buf[dn + 2]) - qbl
                                        } else if (f & 4) != 0 {
                                            let u = (q - w) * 3
                                            gyR = qr - Float(buf[u])
                                            gyG = qg - Float(buf[u + 1])
                                            gyB = qbl - Float(buf[u + 2])
                                        }
                                        // ★ 梯度项限幅：颗粒噪声的单像素梯度乘以填充距离会放大成彩色条纹
                                        let tr = min(max(gxR * dx + gyR * dy, -12), 12)
                                        let tg = min(max(gxG * dx + gyG * dy, -12), 12)
                                        let tb = min(max(gxB * dx + gyB * dy, -12), 12)
                                        ar += wgt * (qr + tr)
                                        ag += wgt * (qg + tg)
                                        ab += wgt * (qbl + tb)
                                        ws += wgt
                                    }
                                    if ws > 1e-6 {
                                        let pb = p * 3
                                        buf[pb] = clampByte(ar / ws)
                                        buf[pb + 1] = clampByte(ag / ws)
                                        buf[pb + 2] = clampByte(ab / ws)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private func clampByte(_ v: Float) -> UInt8 {
        let i = v.rounded()
        return UInt8(min(max(i, 0), 255))
    }

    /// 可分离 box blur（先横后纵），比二维核快约 5 倍
    private func boxBlur(_ src: [UInt8], w: Int, h: Int, radius: Int) -> [UInt8] {
        guard radius > 0 else { return src }
        var tmp = [UInt8](repeating: 0, count: src.count)
        var out = [UInt8](repeating: 0, count: src.count)
        src.withUnsafeBufferPointer { si in
            tmp.withUnsafeMutableBufferPointer { ti in
                for y in 0..<h {
                    let row = y * w
                    for x in 0..<w {
                        let x0 = max(0, x - radius), x1 = min(w - 1, x + radius)
                        let cnt = x1 - x0 + 1
                        var s0 = 0, s1 = 0, s2 = 0
                        for xx in x0...x1 {
                            let i = (row + xx) * 3
                            s0 += Int(si[i]); s1 += Int(si[i + 1]); s2 += Int(si[i + 2])
                        }
                        let o = (row + x) * 3
                        ti[o] = UInt8(s0 / cnt)
                        ti[o + 1] = UInt8(s1 / cnt)
                        ti[o + 2] = UInt8(s2 / cnt)
                    }
                }
            }
        }
        tmp.withUnsafeBufferPointer { ti in
            out.withUnsafeMutableBufferPointer { so in
                for y in 0..<h {
                    let y0 = max(0, y - radius), y1 = min(h - 1, y + radius)
                    let cnt = y1 - y0 + 1
                    for x in 0..<w {
                        var s0 = 0, s1 = 0, s2 = 0
                        for yy in y0...y1 {
                            let i = (yy * w + x) * 3
                            s0 += Int(ti[i]); s1 += Int(ti[i + 1]); s2 += Int(ti[i + 2])
                        }
                        let o = (y * w + x) * 3
                        so[o] = UInt8(s0 / cnt)
                        so[o + 1] = UInt8(s1 / cnt)
                        so[o + 2] = UInt8(s2 / cnt)
                    }
                }
            }
        }
        return out
    }
}
