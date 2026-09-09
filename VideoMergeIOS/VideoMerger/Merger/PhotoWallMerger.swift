//
//  PhotoWallMerger.swift
//  VideoMerger
//
//  iOS 移植自 Android VideoMerger 的 PhotoWallMerger.kt
//  算法源自 photo_wall_merge.py（简化掉人脸检测，使用偏上裁剪）
//

import Foundation

/// 照片墙模式合并器 —— 大大小小、错落有致，加白边框和阴影
final class PhotoWallMerger {

    // MARK: - 常量

    private let BORDER_PX = 8
    private let SHADOW_PX = 12
    // 背景色 (BGR -> RGB) = (40,40,45) -> 0x28282d
    private let BG_R = 0x28
    private let BG_G = 0x28
    private let BG_B = 0x2d

    // MARK: - 公开 API

    func buildCommand(inputPaths: [String],
                      durations: [Double],
                      metas: [VideoMeta],
                      outputPath: String,
                      options: MergeOptions,
                      cutTimes: [Double?]) -> String {
        let n = inputPaths.count
        let canvasW = options.canvasWidth.toAligned16
        let canvasH = options.canvasHeight.toAligned16

        // 1. treemap 布局 + 抖动 + 旋转（与 UI 预览共用 MergeLayout，保证所见即所得）
        let wall = MergeLayout.photoWallLayout(
            n: n, canvasW: canvasW, canvasH: canvasH, seed: options.photoWallSeed
        )
        let jittered = wall.rects
        let rotations = wall.rotations

        // 2. 计算每个视频的裁剪中心
        var cropCenters: [(Double, Double)] = []
        for i in 0..<n {
            let vw = metas[i].width
            let vh = metas[i].height
            let cellW = jittered[i].w
            let cellH = jittered[i].h
            cropCenters.append(computeSafeCropCenter(vw: vw, vh: vh, cellW: cellW, cellH: cellH))
        }

        let maxDur = durations.max() ?? 0.0
        let audioMask = metas.map { $0.hasAudio }

        let (filterComplex, hasAudio) = buildWallFilter(
            n: n, layout: jittered, rotations: rotations, cropCenters: cropCenters,
            metas: metas, durations: durations, maxDur: maxDur, audioMask: audioMask,
            canvasW: canvasW, canvasH: canvasH, cutTimes: cutTimes
        )

        var cmd = "-y "
        for p in inputPaths { cmd += "-i \"\(p)\" " }
        cmd += "-filter_complex \"\(filterComplex)\" "
        cmd += "-map \"[vout]\" "
        cmd += "-t \(maxDur.fmt()) "
        cmd += "-c:v h264_videotoolbox -b:v 8M -pix_fmt yuv420p " // ★ 硬件编码（对齐 Android mediacodec 与编辑器 videotoolbox）
        if hasAudio {
            cmd += "-map \"[aout]\" -c:a aac -b:a 192k "
        }
        cmd += "\"\(outputPath)\""
        return cmd
    }

    // MARK: - 裁剪中心

    private func computeSafeCropCenter(vw: Int, vh: Int, cellW: Int, cellH: Int) -> (Double, Double) {
        let targetAr = Double(cellW) / Double(cellH)
        let videoAr = Double(vw) / Double(vh)

        let cropW: Int, cropH: Int
        if videoAr > targetAr {
            cropH = vh
            cropW = min(max(2, Int(Double(vh) * targetAr)), vw)
        } else {
            cropW = vw
            cropH = min(max(2, Int(Double(vw) / targetAr)), vh)
        }

        let halfW = Double(cropW) / 2.0
        let halfH = Double(cropH) / 2.0

        // 默认偏上 1/3（人物头部通常在画面上方）
        var cx = Double(vw) / 2.0
        var cy = Double(vh) / 3.0
        cx = max(halfW, min(cx, Double(vw) - halfW))
        cy = max(halfH, min(cy, Double(vh) - halfH))
        return (cx, cy)
    }

    // MARK: - filter 构建

    private func buildWallFilter(n: Int,
                                 layout: [LayoutRect],
                                 rotations: [Double],
                                 cropCenters: [(Double, Double)],
                                 metas: [VideoMeta],
                                 durations: [Double],
                                 maxDur: Double,
                                 audioMask: [Bool],
                                 canvasW: Int,
                                 canvasH: Int,
                                 cutTimes: [Double?]) -> (String, Bool) {
        var filters: [String] = []

        // 背景画布
        let bgHex = String(format: "0x%02x%02x%02x", BG_R, BG_G, BG_B)
        filters.append("color=c=\(bgHex):s=\(canvasW)x\(canvasH):d=\(maxDur.fmt()):rate=30[bg]")

        for i in 0..<n {
            let r = layout[i]
            let cellW = r.w, cellH = r.h
            let x = r.x, y = r.y
            let vw = metas[i].width
            let vh = metas[i].height
            let rot = rotations[i]
            let (ccx, ccy) = cropCenters[i]

            let padDur = max(0.0, maxDur - durations[i])
            let trimPfx: String = (i < cutTimes.count && cutTimes[i] != nil)
                ? "trim=end=\(cutTimes[i]!.fmt(3)),setpts=PTS-STARTPTS,"
                : ""

            // 裁剪
            let targetAr = Double(cellW) / Double(cellH)
            let videoAr = Double(vw) / Double(vh)
            let rawCropW: Int, rawCropH: Int
            if videoAr > targetAr {
                rawCropW = min(max(2, Int(Double(vh) * targetAr)), vw)
                rawCropH = vh
            } else {
                rawCropW = vw
                rawCropH = min(max(2, Int(Double(vw) / targetAr)), vh)
            }
            let cropW = rawCropW.toEven
            let cropH = rawCropH.toEven
            let cropX = max(0, min(vw - cropW, Int(ccx - Double(cropW) / 2.0)))
            let cropY = max(0, min(vh - cropH, Int(ccy - Double(cropH) / 2.0)))
            let cropStr = "crop=\(cropW):\(cropH):\(cropX):\(cropY),"
            let scaleStr = "scale=\(cellW):\(cellH):flags=lanczos,"
            let commonSuffix = "\(cropStr)\(scaleStr)fps=30,setsar=1,format=yuv420p"

            // 时长处理
            if padDur <= 0.001 {
                filters.append("[\(i):v]\(trimPfx)\(commonSuffix)[v\(i)raw]")
            } else {
                filters.append(
                    "[\(i):v]\(trimPfx)" +
                    "split=2[\(i)A][\(i)B];" +
                    "[\(i)A]trim=end_frame=1,loop=loop=-1:size=1,setpts=PTS-STARTPTS[\(i)Fof];" +
                    "[\(i)B]setpts=PTS-STARTPTS[\(i)M];" +
                    "[\(i)M][\(i)Fof]concat=n=2:v=1:a=0[\(i)C];" +
                    "[\(i)C]trim=end=\(maxDur.fmt()),setpts=PTS-STARTPTS,\(commonSuffix)[v\(i)raw]"
                )
            }

            // 白色边框 + 阴影
            let borderedW = cellW + 2 * BORDER_PX
            let borderedH = cellH + 2 * BORDER_PX
            let shadowedW = borderedW + SHADOW_PX
            let shadowedH = borderedH + SHADOW_PX

            filters.append(
                "[v\(i)raw]pad=\(borderedW):\(borderedH):-1:-1:color=white," +
                "pad=\(shadowedW):\(shadowedH):-1:-1:color=0x3c3c3c@0.35[v\(i)bordered]"
            )

            // 旋转
            if abs(rot) > 0.1 {
                filters.append(
                    "[v\(i)bordered]rotate=\(rot)*PI/180:c=\(bgHex)" +
                    ":ow=rotw(\(rot)*PI/180):oh=roth(\(rot)*PI/180)[v\(i)rot]"
                )
            } else {
                filters.append("[v\(i)bordered]copy[v\(i)rot]")
            }

            // overlay
            let overlayX = max(0, x - BORDER_PX)
            let overlayY = max(0, y - BORDER_PX)

            if i == 0 {
                filters.append("[bg][v\(i)rot]overlay=\(overlayX):\(overlayY):eof_action=repeat[v\(i)out]")
            } else {
                let prev = i - 1
                filters.append("[v\(prev)out][v\(i)rot]overlay=\(overlayX):\(overlayY):eof_action=repeat[v\(i)out]")
            }
        }

        let finalLabel = "v\(n - 1)out"

        // 音频
        var audioParts: [String] = []
        for i in 0..<n {
            if !audioMask[i] { continue }
            let padA = max(0.0, maxDur - durations[i])
            let aTrim: String = (i < cutTimes.count && cutTimes[i] != nil)
                ? "atrim=end=\(cutTimes[i]!.fmt(3)),asetpts=PTS-STARTPTS,"
                : ""
            let apad = padA > 0 ? ",apad=whole_dur=\(maxDur.fmt())" : ""
            audioParts.append("[\(i):a]\(aTrim)aresample=44100\(apad)[a\(i)]")
        }

        var filterStr = filters.joined(separator: ";")
        if !audioParts.isEmpty {
            let amixIn = (0..<n).filter { audioMask[$0] }.map { "[a\($0)]" }.joined()
            filterStr += ";" + audioParts.joined(separator: ";") +
                         ";\(amixIn) amix=inputs=\(audioParts.count):duration=first:normalize=0[aout]"
        }

        // 把最终标签改名为 vout
        filterStr = filterStr.replacingOccurrences(of: "[\(finalLabel)]", with: "[vout]")

        return (filterStr, !audioParts.isEmpty)
    }
}

// MARK: - SeededRandom

/// 简单的种子随机数发生器，对齐 Kotlin Random 行为：
/// 提供可重现的 nextDouble / nextInt 区间采样。
final class SeededRandom {
    private var state: UInt64

    init(seed: Int?) {
        if let s = seed {
            state = UInt64(bitPattern: Int64(s))
            if state == 0 { state = 0x9E3779B97F4A7C15 }
        } else {
            // 用系统时间作为种子
            state = UInt64(Date().timeIntervalSince1970 * 1_000_000) ^ 0x9E3779B97F4A7C15
        }
    }

    /// xorshift64
    @discardableResult
    func nextU64() -> UInt64 {
        var x = state
        x ^= x << 13
        x ^= x >> 7
        x ^= x << 17
        state = x
        return x
    }

    /// [0, 1) double
    func nextDouble() -> Double {
        Double(nextU64() >> 11) / Double(1 << 53)
    }

    /// [low, high) double
    func nextDouble(_ low: Double, _ high: Double) -> Double {
        low + (high - low) * nextDouble()
    }

    /// Kotlin 的 nextInt(from, until) -> [from, until)
    func nextInt(_ low: Int, _ high: Int) -> Int {
        if high <= low { return low }
        let range = UInt64(high - low)
        return low + Int(nextU64() % range)
    }

    /// 对齐 Kotlin Random.shuffle
    func shuffle<T>(_ array: inout [T]) {
        for i in stride(from: array.count - 1, through: 1, by: -1) {
            let j = nextInt(0, i + 1)
            array.swapAt(i, j)
        }
    }
}

private extension Array {
    mutating func shuffle(using rng: SeededRandom) {
        rng.shuffle(&self)
    }
}
