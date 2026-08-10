//
//  CollageMerger.swift
//  VideoMerger
//
//  iOS 移植自 Android VideoMerger 的 CollageMerger.kt
//  算法源自 collage_merge.py（简化掉人脸检测，使用正中裁剪）
//

import Foundation

/// Collage 模式合并器 —— 一个主窗口 + 若干副窗口，严格无空隙铺满画布
final class CollageMerger {

    func buildCommand(inputPaths: [String],
                      durations: [Double],
                      metas: [VideoMeta],
                      outputPath: String,
                      options: MergeOptions,
                      cutTimes: [Double?]) -> String {
        let n = inputPaths.count
        let mainIdx = max(0, min(options.collageMainIndex, n - 1))
        let gap = options.gap.toEven

        let canvasW = options.canvasWidth.toAligned16
        let canvasH = options.canvasHeight.toAligned16

        let (sizes, positions) = calcMainSub(
            n: n, canvasW: canvasW, canvasH: canvasH,
            mainRatio: options.collageMainRatio,
            orient: options.collageOrient.rawValue,
            subCols: nil, gap: gap, mainIdx: mainIdx
        )

        let maxDur = durations.max() ?? 0.0
        let audioMask = metas.map { $0.hasAudio }
        let faceXY = Array(repeating: (0.5, 0.5), count: n)

        let (filterComplex, hasAudio) = buildFilter(
            n: n, sizes: sizes, positions: positions,
            durations: durations, maxDur: maxDur, audioMask: audioMask,
            fit: options.collageFit.rawValue, faceXY: faceXY, cutTimes: cutTimes
        )

        var cmd = "-y "
        for p in inputPaths { cmd += "-i \"\(p)\" " }
        cmd += "-filter_complex \"\(filterComplex)\" "
        cmd += "-map \"[vout]\" "
        cmd += "-t \(maxDur.fmt()) "
        cmd += "-c:v libx264 -preset medium -crf 20 -pix_fmt yuv420p "
        if hasAudio {
            cmd += "-map \"[aout]\" -c:a aac -b:a 192k "
        }
        cmd += "\"\(outputPath)\""
        return cmd
    }

    // MARK: - 布局算法

    /// 把 total（偶数）切成 parts 个偶数块，精确铺满，块间留 gap
    private func evenDivide(total: Int, parts: Int, gap: Int) -> [Int] {
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
    private func calcMainSub(n: Int, canvasW: Int, canvasH: Int, mainRatio: Double,
                             orient: String, subCols: Int?, gap: Int, mainIdx: Int)
                            -> (sizes: [(Int, Int)], positions: [(Int, Int)]) {
        let cw = canvasW.toEven
        let ch = canvasH.toEven
        let gp = gap.toEven
        let subs = n - 1

        var sizes: [(Int, Int)] = Array(repeating: (0, 0), count: n)
        var positions: [(Int, Int)] = Array(repeating: (0, 0), count: n)

        if subs == 0 {
            sizes[0] = (cw, ch)
            positions[0] = (0, 0)
            return (sizes, positions)
        }

        let subIndices = (0..<n).filter { $0 != mainIdx }

        switch orient {
        case "left", "right":
            let mainW = Int((Double(cw) * mainRatio).rounded()).toEven
            let subW = cw - mainW

            let sc = subCols ?? (subs >= 6 ? 2 : 1)
            let subRows = Int(ceil(Double(subs) / Double(sc)))
            let rowHs = evenDivide(total: ch, parts: subRows, gap: gp)

            let mainX = orient == "left" ? 0 : subW
            sizes[mainIdx] = (mainW, ch)
            positions[mainIdx] = (mainX, 0)

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
            sizes[mainIdx] = (cw, mainH)
            positions[mainIdx] = (0, mainY)

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

        return (sizes, positions)
    }

    // MARK: - filter

    private func scaleFill(cw: Int, ch: Int, fit: String, fx: Double = 0.5, fy: Double = 0.5) -> String {
        if fit == "contain" {
            return "scale=\(cw):\(ch):force_original_aspect_ratio=decrease," +
                   "pad=\(cw):\(ch):(ow-iw)/2:(oh-ih)/2:black,fps=30,setsar=1,format=yuv420p"
        } else {
            // cover: crop 偏移
            return "scale=\(cw):\(ch):force_original_aspect_ratio=increase," +
                   "crop=\(cw):\(ch):\(fx.fmt(4))*(in_w-out_w):\(fy.fmt(4))*(in_h-out_h),fps=30,setsar=1,format=yuv420p"
        }
    }

    private func buildFilter(n: Int,
                             sizes: [(Int, Int)],
                             positions: [(Int, Int)],
                             durations: [Double],
                             maxDur: Double,
                             audioMask: [Bool],
                             fit: String,
                             faceXY: [(Double, Double)],
                             cutTimes: [Double?]) -> (String, Bool) {
        var scaled: [String] = []

        for i in 0..<n {
            let (cw, ch) = sizes[i]
            let (fx, fy) = faceXY[i]
            let padDur = max(0.0, maxDur - durations[i])
            let trimPfx: String = (i < cutTimes.count && cutTimes[i] != nil)
                ? "trim=end=\(cutTimes[i]!.fmt(3)),setpts=PTS-STARTPTS,"
                : ""
            let common = scaleFill(cw: cw, ch: ch, fit: fit, fx: fx, fy: fy) + "[v\(i)]"

            if padDur <= 0.001 {
                scaled.append("[\(i):v]\(trimPfx)\(common)")
            } else {
                scaled.append(
                    "[\(i):v]\(trimPfx)" +
                    "split=2[\(i)A][\(i)B];" +
                    "[\(i)A]trim=end_frame=1,loop=loop=-1:size=1,setpts=PTS-STARTPTS[\(i)Fof];" +
                    "[\(i)B]setpts=PTS-STARTPTS[\(i)M];" +
                    "[\(i)M][\(i)Fof]concat=n=2:v=1:a=0[\(i)C];" +
                    "[\(i)C]trim=end=\(maxDur.fmt()),setpts=PTS-STARTPTS,\(common)"
                )
            }
        }

        let layouts = positions.map { "\($0.0)_\($0.1)" }
        let inputsConcat = (0..<n).map { "[v\($0)]" }.joined()
        let layoutStr = layouts.joined(separator: "|")

        var parts: [String] = []
        parts.append(scaled.joined(separator: ";"))
        parts.append("\(inputsConcat) xstack=inputs=\(n):layout=\(layoutStr):fill=black[vout]")

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

        if !audioParts.isEmpty {
            let amixIn = (0..<n).filter { audioMask[$0] }.map { "[a\($0)]" }.joined()
            parts.append(audioParts.joined(separator: ";"))
            parts.append("\(amixIn) amix=inputs=\(audioParts.count):duration=first:normalize=0[aout]")
        }

        return (parts.joined(separator: ";"), !audioParts.isEmpty)
    }
}
