//
//  WatermarkDetector.swift
//  VideoMerger
//
//  iOS 移植自 Android WatermarkDetector.kt
//  静态/移动水印自动检测：分时间窗 + 静态掩码通道 + 均值图边缘通道
//

import Foundation
import UIKit
import ffmpegkit

enum WatermarkDetector {

    private static let framesPerWindow = 8
    private static let analysisWidth = 240
    private static let maxWindows = 5
    private static let maxRegions = 6

    /// 检测视频中的水印区域（含时间段，源视频秒）；检测不到返回空
    static func detect(videoPath: String) -> [WatermarkRegion] {
        guard let meta = MediaUtils.getVideoMeta(path: videoPath), meta.duration > 0.5 else { return [] }
        let duration = meta.duration

        let windowCount = duration <= 5.0 ? 1 : min(maxWindows, max(Int(duration / 3.0), 2))
        let windowLen = duration / Double(windowCount)

        let frameDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("wm_frames_\(Int(Date().timeIntervalSince1970 * 1000))", isDirectory: true)
        try? FileManager.default.createDirectory(at: frameDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: frameDir) }

        struct Candidate { let window: Int; let box: [Double] }
        var candidates: [Candidate] = []

        for wIdx in 0..<windowCount {
            let start = Double(wIdx) * windowLen
            FFmpegKit.execute(
                "-v error -ss \(fmt(start, 2)) -t \(fmt(windowLen, 2)) -i \"\(videoPath)\" " +
                "-vf \"fps=\(framesPerWindow)/\(fmt(windowLen, 2)),scale=\(analysisWidth):-2\" " +
                "-frames:v \(framesPerWindow) -q:v 3 \"\(frameDir.path)/w\(wIdx)_%02d.jpg\""
            )
            let files = (try? FileManager.default.contentsOfDirectory(at: frameDir, includingPropertiesForKeys: nil))?
                .filter { $0.lastPathComponent.hasPrefix("w\(wIdx)_") }
                .sorted { $0.lastPathComponent < $1.lastPathComponent } ?? []
            let bitmaps = files.compactMap { UIImage(contentsOfFile: $0.path) }
            if bitmaps.count < 4 { continue }

            let w = Int(bitmaps[0].size.width * bitmaps[0].scale)
            let h = Int(bitmaps[0].size.height * bitmaps[0].scale)
            let frames = bitmaps.map { toLumaArray($0) }

            for box in analyze(frames: frames, w: w, h: h) {
                candidates.append(Candidate(window: wIdx, box: box))
            }
        }
        if candidates.isEmpty { return [] }

        // 跨窗合并：位置相近（IoU>0.5）合并时间窗；位置不同 → 各自时间段
        final class Merged {
            var box: [Double]
            var windows: Set<Int>
            init(box: [Double], windows: Set<Int>) { self.box = box; self.windows = windows }
        }
        var mergedList: [Merged] = []
        let perWindow = Dictionary(grouping: candidates, by: \.window)
        for wIdx in perWindow.keys.sorted() {
            guard let list = perWindow[wIdx] else { continue }
            for c in list.sorted(by: { $0.box[2] * $0.box[3] > $1.box[2] * $1.box[3] }) {
                if let existing = mergedList.first(where: { iouNorm($0.box, c.box) > 0.5 }) {
                    existing.windows.insert(c.window)
                } else {
                    mergedList.append(Merged(box: c.box, windows: [c.window]))
                }
            }
        }

        let pad = windowLen * 0.15
        return mergedList
            .sorted { $0.box[2] * $0.box[3] > $1.box[2] * $1.box[3] }
            .prefix(maxRegions)
            .map { m -> WatermarkRegion in
                let startW = m.windows.min() ?? 0
                let endW = m.windows.max() ?? 0
                let st = max(Double(startW) * windowLen - pad, 0)
                let et = min(Double(endW + 1) * windowLen + pad, duration)
                return WatermarkRegion(
                    x: m.box[0], y: m.box[1], w: m.box[2], h: m.box[3],
                    mode: .blur, strength: 14,
                    startTime: st,
                    endTime: (st <= 0.01 && et >= duration - 0.01) ? -1.0 : et)
            }
    }

    // MARK: - 亮度平面

    static func toLumaArray(_ bmp: UIImage) -> [Float] {
        guard let cg = bmp.cgImage else { return [] }
        let w = cg.width, h = cg.height
        var pixels = [UInt32](repeating: 0, count: w * h)
        let ctx = CGContext(data: &pixels, width: w, height: h, bitsPerComponent: 8,
                            bytesPerRow: w * 4, space: CGColorSpaceCreateDeviceRGB(),
                            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
        ctx?.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        var out = [Float](repeating: 0, count: w * h)
        for i in 0..<(w * h) {
            let p = pixels[i]
            let r = Float((p >> 16) & 0xFF)
            let g = Float((p >> 8) & 0xFF)
            let b = Float(p & 0xFF)
            out[i] = 0.299 * r + 0.587 * g + 0.114 * b
        }
        return out
    }

    // MARK: - 单时间窗分析（纯函数）

    static func analyze(frames: [[Float]], w: Int, h: Int) -> [[Double]] {
        guard frames.count >= 4, w >= 8, h >= 8 else { return [] }
        let n = frames.count
        let total = w * h

        var mean = [Float](repeating: 0, count: total)
        for f in frames { for i in 0..<total { mean[i] += f[i] } }
        for i in 0..<total { mean[i] /= Float(n) }

        var std = [Float](repeating: 0, count: total)
        for f in frames { for i in 0..<total { let d = f[i] - mean[i]; std[i] += d * d } }
        for i in 0..<total { std[i] = sqrtf(std[i] / Float(n)) }

        // 静态掩码（阈值 5.5：半透明 logo 方差被背景抬高，7 会漏检）
        let staticThresh: Float = 5.5
        var mask = [Bool](repeating: false, count: total)
        var staticCount = 0
        for i in 0..<total where std[i] < staticThresh {
            mask[i] = true
            staticCount += 1
        }
        if Double(staticCount) > Double(total) * 0.55 { return [] }

        // 通道 A：静态掩码连通域
        let dilated = dilate(mask, w: w, h: h, radius: 2)
        let staticBoxes = connectedBoxes(dilated, w: w, h: h)

        // 通道 B：均值图边缘（半透明水印幽灵边缘）
        var edgeMap = [Bool](repeating: false, count: total)
        for y in 0..<(h - 1) {
            for x in 0..<(w - 1) {
                let i = y * w + x
                if abs(mean[i + 1] - mean[i]) > 18 || abs(mean[i + w] - mean[i]) > 18 {
                    edgeMap[i] = true
                }
            }
        }
        let edgeBoxes = connectedBoxes(dilate(edgeMap, w: w, h: h, radius: 2), w: w, h: h)

        let minArea = Double(total) * 0.002
        let maxArea = Double(total) * 0.12

        func scoreBox(_ b: [Int], isEdgeChannel: Bool) -> Double {
            let bw = b[2] - b[0]
            let bh = b[3] - b[1]
            let area = Double(bw * bh)
            if area < minArea || area > maxArea { return -1 }
            // 角落约束（按中心点）：画面中部内容直接排除
            let cx = Double(b[0] + b[2]) / 2
            let cy = Double(b[1] + b[3]) / 2
            let inV = cy < Double(h) * 0.32 || cy > Double(h) * 0.68
            let inH = cx < Double(w) * 0.62 || cx > Double(w) * 0.38
            if !inV || !inH { return -1 }
            // 通栏细长条剔除
            if Double(bw) >= Double(w) * 0.95 && Double(bh) <= Double(h) * 0.08 { return -1 }
            // 内缩 3px（排除框边界过渡带）
            let s = 3
            let inner = [b[0] + s, b[1] + s,
                         max(b[2] - s, b[0] + s + 1),
                         max(b[3] - s, b[1] + s + 1)]
            if isEdgeChannel {
                var inEdge = 0
                var innerTotal = 0
                for y in inner[1]..<inner[3] {
                    for x in inner[0]..<inner[2] {
                        innerTotal += 1
                        if edgeMap[y * w + x] { inEdge += 1 }
                    }
                }
                let density = innerTotal > 0 ? Double(inEdge) / Double(innerTotal) : 0
                return density >= 0.06 ? density : -1
            } else {
                let frac = edgeFraction(mean: mean, w: w, h: h, box: inner)
                return frac >= 0.035 ? frac : -1
            }
        }

        struct Scored { let box: [Int]; let score: Double }
        var scored: [Scored] = []
        for b in staticBoxes {
            let sc = scoreBox(b, isEdgeChannel: false)
            if sc >= 0 { scored.append(Scored(box: b, score: sc)) }
        }
        for b in edgeBoxes {
            let sc = scoreBox(b, isEdgeChannel: true)
            if sc >= 0 { scored.append(Scored(box: b, score: sc)) }
        }
        // 跨通道去重（IoU > 0.3）
        var unique: [Scored] = []
        for c in scored {
            if !unique.contains(where: { iouPx($0.box, c.box, w: w, h: h) > 0.3 }) {
                unique.append(c)
            }
        }

        // ★ 按内部边缘密度降序（文字水印密度最高，防大暗部挤掉真水印）
        return unique
            .sorted { $0.score > $1.score }
            .prefix(3)
            .map { s -> [Double] in
                let b = s.box
                let x0 = max(b[0], 0), y0 = max(b[1], 0)
                let x1 = min(b[2], w), y1 = min(b[3], h)
                return [Double(x0) / Double(w), Double(y0) / Double(h),
                        Double(x1 - x0) / Double(w), Double(y1 - y0) / Double(h)]
            }
    }

    // MARK: - 图像工具

    private static func dilate(_ mask: [Bool], w: Int, h: Int, radius: Int) -> [Bool] {
        var out = [Bool](repeating: false, count: mask.count)
        for y in 0..<h {
            for x in 0..<w where mask[y * w + x] {
                for dy in -radius...radius {
                    let ny = y + dy
                    guard ny >= 0, ny < h else { continue }
                    for dx in -radius...radius {
                        let nx = x + dx
                        guard nx >= 0, nx < w else { continue }
                        out[ny * w + nx] = true
                    }
                }
            }
        }
        return out
    }

    private static func connectedBoxes(_ mask: [Bool], w: Int, h: Int) -> [[Int]] {
        var visited = [Bool](repeating: false, count: mask.count)
        var boxes: [[Int]] = []
        var stack = [Int](repeating: 0, count: mask.count)
        for start in 0..<mask.count {
            if !mask[start] || visited[start] { continue }
            var sp = 0
            stack[sp] = start; sp += 1
            visited[start] = true
            var x0 = w, y0 = h, x1 = 0, y1 = 0
            while sp > 0 {
                sp -= 1
                let idx = stack[sp]
                let x = idx % w
                let y = idx / w
                x0 = min(x0, x); y0 = min(y0, y)
                x1 = max(x1, x); y1 = max(y1, y)
                if x > 0 && mask[idx - 1] && !visited[idx - 1] { visited[idx - 1] = true; stack[sp] = idx - 1; sp += 1 }
                if x < w - 1 && mask[idx + 1] && !visited[idx + 1] { visited[idx + 1] = true; stack[sp] = idx + 1; sp += 1 }
                if y > 0 && mask[idx - w] && !visited[idx - w] { visited[idx - w] = true; stack[sp] = idx - w; sp += 1 }
                if y < h - 1 && mask[idx + w] && !visited[idx + w] { visited[idx + w] = true; stack[sp] = idx + w; sp += 1 }
            }
            boxes.append([x0, y0, x1 + 1, y1 + 1])
        }
        return boxes
    }

    /// 候选框内「有边缘的像素」占比（时间均值图空间梯度，内缩框）
    private static func edgeFraction(mean: [Float], w: Int, h: Int, box: [Int]) -> Double {
        let gradThresh: Float = 9.0
        var count = 0
        var total = 0
        for y in box[1]..<box[3] {
            for x in box[0]..<box[2] {
                if x + 1 >= w || y + 1 >= h { continue }
                let gx = abs(mean[y * w + x + 1] - mean[y * w + x])
                let gy = abs(mean[(y + 1) * w + x] - mean[y * w + x])
                total += 1
                if gx > gradThresh || gy > gradThresh { count += 1 }
            }
        }
        if total == 0 { return 0 }
        return Double(count) / Double(total)
    }

    private static func iouNorm(_ a: [Double], _ b: [Double]) -> Double {
        let ix = min(a[0] + a[2], b[0] + b[2]) - max(a[0], b[0])
        let iy = min(a[1] + a[3], b[1] + b[3]) - max(a[1], b[1])
        guard ix > 0, iy > 0 else { return 0 }
        let inter = ix * iy
        let union = a[2] * a[3] + b[2] * b[3] - inter
        return union > 0 ? inter / union : 0
    }

    private static func iouPx(_ a: [Int], _ b: [Int], w: Int, h: Int) -> Double {
        let na = [Double(a[0]) / Double(w), Double(a[1]) / Double(h),
                  Double(a[2] - a[0]) / Double(w), Double(a[3] - a[1]) / Double(h)]
        let nb = [Double(b[0]) / Double(w), Double(b[1]) / Double(h),
                  Double(b[2] - b[0]) / Double(w), Double(b[3] - b[1]) / Double(h)]
        return iouNorm(na, nb)
    }
}
