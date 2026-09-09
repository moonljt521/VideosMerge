//
//  MergeLayout.swift
//  VideoMerger
//
//  合并布局计算 —— 预览与导出共用同一套算法，保证「所见即所得」。
//  移植/对齐 Android VideoMerger 的 MergeLayout.kt。
//

import Foundation

/// 布局矩形，坐标基于输出画布像素（左上角原点）。
struct LayoutRect: Equatable {
    let x: Int
    let y: Int
    let w: Int
    let h: Int
}

/// 合并布局纯计算（不碰 ffmpeg），GridMerger / CollageMerger / PhotoWallMerger
/// 与 UI 预览都调用它，避免两边算法漂移。
enum MergeLayout {

    /// 网格布局规格
    struct GridSpec {
        let rows: Int
        let cols: Int
        let cellW: Int
        let cellH: Int

        var outW: Int { cellW * cols }
        var outH: Int { cellH * rows }

        /// 第 i 个视频的格子矩形（行优先）
        func rects() -> [LayoutRect] {
            (0..<(rows * cols)).map { idx in
                let r = idx / cols
                let c = idx % cols
                return LayoutRect(x: c * cellW, y: r * cellH, w: cellW, h: cellH)
            }
        }
    }

    /// 一主多副布局：每个视频的尺寸与位置
    struct CollageLayout {
        let sizes: [(Int, Int)]
        let positions: [(Int, Int)]
        let outW: Int
        let outH: Int

        func rects() -> [LayoutRect] {
            (0..<sizes.count).map { i in
                LayoutRect(x: positions[i].0, y: positions[i].1, w: sizes[i].0, h: sizes[i].1)
            }
        }
    }

    /// 照片墙布局：矩形 + 旋转角（与导出同一次随机序列）
    struct PhotoWallLayout {
        let rects: [LayoutRect]
        let rotations: [Double]
        let outW: Int
        let outH: Int
    }

    // MARK: - Grid

    static func calcGrid(_ n: Int) -> (rows: Int, cols: Int) {
        let cols = Int(ceil(sqrt(Double(n))))
        let rows = Int(ceil(Double(n) / Double(cols)))
        return (rows, cols)
    }

    /// 取出现次数最多的宽高比（按 3 位小数归并）
    static func dominantAspect(_ aspects: [Double]) -> Double {
        guard !aspects.isEmpty else { return 16.0 / 9.0 }
        var counts: [String: Int] = [:]
        for a in aspects {
            counts[String(format: "%.3f", a), default: 0] += 1
        }
        if let top = counts.max(by: { $0.value < $1.value })?.key, let v = Double(top) {
            return v
        }
        return aspects[0]
    }

    static func cellSizeFromAspect(aspect: Double, target: Int) -> (Int, Int) {
        if aspect >= 1 {
            return (target, Int(Double(target) / aspect))
        } else {
            return (Int(Double(target) * aspect), target)
        }
    }

    static func gridSpec(n: Int, aspects: [Double], gridCellSize: Int) -> GridSpec {
        let (rows, cols) = calcGrid(n)
        let aspect = dominantAspect(aspects)
        let (rawW, rawH) = cellSizeFromAspect(aspect: aspect, target: gridCellSize)
        return GridSpec(rows: rows, cols: cols,
                        cellW: rawW.toAligned16, cellH: rawH.toAligned16)
    }

    // MARK: - Collage

    /// 把 total（偶数）切成 parts 个偶数块，精确铺满，块间留 gap
    static func evenDivide(total: Int, parts: Int, gap: Int) -> [Int] {
        let t = total - total % 2
        let g = gap - gap % 2
        var avail = t - (parts - 1) * g
        if avail < parts * 2 { avail = parts * 2 }
        var base = (avail / parts) / 2 * 2
        if base < 2 { base = 2 }
        var sizes = Array(repeating: base, count: parts)
        var rem = avail - sizes.reduce(0, +)
        var i = parts - 1
        while rem >= 2 && i >= 0 {
            sizes[i] += 2
            rem -= 2
            i -= 1
        }
        return sizes
    }

    /// 一主多副布局，严格无空隙
    static func collageLayout(n: Int, canvasW: Int, canvasH: Int, mainRatio: Double,
                              orient: String, gap: Int, mainIdx: Int,
                              subCols: Int? = nil) -> CollageLayout {
        let cw = canvasW.toEven
        let ch = canvasH.toEven
        let gp = gap.toEven
        let subs = n - 1
        let safeMainIdx = max(0, min(mainIdx, n - 1))

        var sizes: [(Int, Int)] = Array(repeating: (0, 0), count: n)
        var positions: [(Int, Int)] = Array(repeating: (0, 0), count: n)

        if subs <= 0 {
            sizes[0] = (cw, ch)
            positions[0] = (0, 0)
            return CollageLayout(sizes: sizes, positions: positions, outW: cw, outH: ch)
        }

        let subIndices = (0..<n).filter { $0 != safeMainIdx }

        switch orient {
        case "left", "right":
            let mainW = Int((Double(cw) * mainRatio).rounded()).toEven
            let subW = cw - mainW

            let sc = subCols ?? (subs >= 6 ? 2 : 1)
            let subRows = Int(ceil(Double(subs) / Double(sc)))
            let rowHs = evenDivide(total: ch, parts: subRows, gap: gp)

            let mainX = orient == "left" ? 0 : subW
            sizes[safeMainIdx] = (mainW, ch)
            positions[safeMainIdx] = (mainX, 0)

            var si = 0
            var y = 0
            for r in 0..<subRows {
                let remaining = subs - si
                let colsThis = min(sc, remaining)
                let colWs = evenDivide(total: subW, parts: colsThis, gap: gp)
                var x = orient == "left" ? mainW : 0
                for c in 0..<colsThis {
                    let w = colWs[c]
                    let h = rowHs[r]
                    let idx = subIndices[si]
                    sizes[idx] = (w, h)
                    positions[idx] = (x, y)
                    x += w + gp
                    si += 1
                }
                y += rowHs[r] + gp
            }

        default: // top / bottom
            let mainH = Int((Double(ch) * mainRatio).rounded()).toEven
            let subH = ch - mainH

            let sr = subCols ?? (subs >= 6 ? 2 : 1)
            let subColsAuto = Int(ceil(Double(subs) / Double(sr)))
            let colWs = evenDivide(total: cw, parts: subColsAuto, gap: gp)

            let mainY = orient == "top" ? 0 : subH
            sizes[safeMainIdx] = (cw, mainH)
            positions[safeMainIdx] = (0, mainY)

            var si = 0
            var x = 0
            for c in 0..<subColsAuto {
                let remaining = subs - si
                let rowsThis = min(sr, remaining)
                let rowHs = evenDivide(total: subH, parts: rowsThis, gap: gp)
                var y = orient == "top" ? mainH : 0
                for r in 0..<rowsThis {
                    let w = colWs[c]
                    let h = rowHs[r]
                    let idx = subIndices[si]
                    sizes[idx] = (w, h)
                    positions[idx] = (x, y)
                    y += h + gp
                    si += 1
                }
                x += colWs[c] + gp
            }
        }

        return CollageLayout(sizes: sizes, positions: positions, outW: cw, outH: ch)
    }

    // MARK: - Photo Wall

    private static let PADDING_GAP = 18
    private static let ROTATION_MAX = 3.5

    /// 照片墙布局。seed 为 nil 时沿用随机布局（导出仍然可用），
    /// 但 UI 预览应始终传入固定 seed，才能与导出结果一致。
    static func photoWallLayout(n: Int, canvasW: Int, canvasH: Int, seed: Int?) -> PhotoWallLayout {
        let rng = SeededRandom(seed: seed)
        let layout = treemapLayout(canvasW: canvasW, canvasH: canvasH, n: n, rng: rng)
        let jittered = addWallJitter(layout: layout, canvasW: canvasW, canvasH: canvasH, rng: rng)
        let rotations = (0..<n).map { _ in roundTo1dp(rng.nextDouble(-ROTATION_MAX, ROTATION_MAX)) }
        return PhotoWallLayout(rects: jittered, rotations: rotations, outW: canvasW, outH: canvasH)
    }

    private static func treemapLayout(canvasW: Int, canvasH: Int, n: Int,
                                      rng: SeededRandom) -> [LayoutRect] {
        // 权重：前 1/3 大块，其余小块
        var weights: [Double] = []
        let bigCount = max(1, n / 3)
        for i in 0..<n {
            if i < bigCount {
                weights.append(rng.nextDouble(1.8, 3.0))
            } else {
                weights.append(rng.nextDouble(0.8, 1.5))
            }
        }

        var result: [LayoutRect?] = Array(repeating: nil, count: n)
        var shuffledIndices = Array(0..<n)
        // 手写 Fisher-Yates（SeededRandom 是 class，不符合标准库的 RandomNumberGenerator）
        if n > 1 {
            for i in stride(from: n - 1, through: 1, by: -1) {
                let j = rng.nextInt(0, i + 1)
                shuffledIndices.swapAt(i, j)
            }
        }

        func slice(indices: [Int], x: Int, y: Int, w: Int, h: Int, horizontal: Bool) {
            if indices.isEmpty { return }
            if indices.count == 1 {
                let idx = indices[0]
                var pw = max(w - 2 * PADDING_GAP, 100)
                var ph = max(h - 2 * PADDING_GAP, 100)
                pw = pw.toEven
                ph = ph.toEven
                let px = Int(Double(x) + Double(w - pw) / 2.0 + rng.nextDouble(-3.0, 3.0))
                let py = Int(Double(y) + Double(h - ph) / 2.0 + rng.nextDouble(-3.0, 3.0))
                result[idx] = LayoutRect(x: px, y: py, w: pw, h: ph)
                return
            }

            let subWeights = indices.map { weights[$0] }
            let subTotal = subWeights.reduce(0, +)

            // 找接近一半的切分点
            var acc = 0.0
            var split = 1
            for k in 0..<(subWeights.count - 1) {
                acc += subWeights[k]
                if acc >= subTotal / 2 {
                    split = k + 1
                    break
                }
            }

            let leftIndices = Array(indices[0..<split])
            let rightIndices = Array(indices[split...])
            let leftRatio = leftIndices.map { weights[$0] }.reduce(0, +) / subTotal

            // 防止递归过深
            if w < 2 * PADDING_GAP + 150 || h < 2 * PADDING_GAP + 150 {
                let cols = max(1, Int(sqrt(Double(indices.count))))
                let rows = (indices.count + cols - 1) / cols
                var cellW = max(50, (w - (cols - 1) * PADDING_GAP) / cols)
                var cellH = max(50, (h - (rows - 1) * PADDING_GAP) / rows)
                cellW = cellW.toEven
                cellH = cellH.toEven
                for (idxI, idx) in indices.enumerated() {
                    let c = idxI % cols
                    let r = idxI / cols
                    let px = x + c * (cellW + PADDING_GAP)
                    let py = y + r * (cellH + PADDING_GAP)
                    result[idx] = LayoutRect(x: px, y: py, w: cellW, h: cellH)
                }
                return
            }

            if horizontal {
                let lw = max(1, Int(Double(w) * leftRatio))
                slice(indices: leftIndices, x: x, y: y, w: lw, h: h, horizontal: !horizontal)
                slice(indices: rightIndices, x: x + lw, y: y, w: w - lw, h: h, horizontal: !horizontal)
            } else {
                let lh = max(1, Int(Double(h) * leftRatio))
                slice(indices: leftIndices, x: x, y: y, w: w, h: lh, horizontal: !horizontal)
                slice(indices: rightIndices, x: x, y: y + lh, w: w, h: h - lh, horizontal: !horizontal)
            }
        }

        slice(indices: shuffledIndices, x: 0, y: 0, w: canvasW, h: canvasH, horizontal: true)
        return result.map { $0! }
    }

    private static func addWallJitter(layout: [LayoutRect], canvasW: Int, canvasH: Int,
                                      rng: SeededRandom) -> [LayoutRect] {
        layout.map { r in
            let dx = rng.nextInt(-6, 6)
            let dy = rng.nextInt(-6, 6)
            return LayoutRect(
                x: max(0, min(canvasW - r.w, r.x + dx)),
                y: max(0, min(canvasH - r.h, r.y + dy)),
                w: r.w, h: r.h
            )
        }
    }

    private static func roundTo1dp(_ v: Double) -> Double {
        (v * 10).rounded() / 10.0
    }
}
