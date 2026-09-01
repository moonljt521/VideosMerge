//
//  GridMerger.swift
//  VideoMerger
//
//  iOS 移植自 Android VideoMerger 的 GridMerger.kt
//  算法源自 grid_merge.py
//

import Foundation

/// Grid 模式合并器 —— 将多个视频排列成均匀网格
final class GridMerger {

    /// 构建完整的 ffmpeg 命令（不含 "ffmpeg" 前缀）
    func buildCommand(inputPaths: [String],
                      durations: [Double],
                      metas: [VideoMeta],
                      outputPath: String,
                      options: MergeOptions,
                      cutTimes: [Double?]) -> String {
        let n = inputPaths.count
        let (rows, cols) = calcGrid(n)

        // 1. 单元格尺寸（保证 16 对齐）
        let aspect = dominantAspect(ratios: metas.map { Double($0.width) / Double($0.height) })
        var (cellW, cellH) = cellSizeFromAspect(aspect: aspect, target: options.gridCellSize)
        cellW = cellW.toAligned16
        cellH = cellH.toAligned16
        let outW = cellW * cols
        let outH = cellH * rows
        _ = outW; _ = outH

        let maxDur = durations.max() ?? 0.0
        let audioMask = metas.map { $0.hasAudio }

        let (filterComplex, hasAudio) = buildFilterComplex(
            n: n, cellW: cellW, cellH: cellH, rows: rows, cols: cols,
            durations: durations, maxDur: maxDur, audioMask: audioMask, cutTimes: cutTimes
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

    // MARK: - 网格

    private func calcGrid(_ n: Int) -> (rows: Int, cols: Int) {
        let cols = Int(ceil(sqrt(Double(n))))
        let rows = Int(ceil(Double(n) / Double(cols)))
        return (rows, cols)
    }

    private func dominantAspect(ratios: [Double]) -> Double {
        var counts: [String: Int] = [:]
        for r in ratios {
            let key = String(format: "%.3f", r)
            counts[key, default: 0] += 1
        }
        if let topKey = counts.max(by: { $0.value < $1.value })?.key,
           let v = Double(topKey) {
            return v
        }
        return ratios[0]
    }

    private func cellSizeFromAspect(aspect: Double, target: Int) -> (Int, Int) {
        if aspect >= 1 {
            return (target, Int(Double(target) / aspect))
        } else {
            return (Int(Double(target) * aspect), target)
        }
    }

    // MARK: - filter

    private func buildFilterComplex(n: Int,
                                    cellW: Int,
                                    cellH: Int,
                                    rows: Int,
                                    cols: Int,
                                    durations: [Double],
                                    maxDur: Double,
                                    audioMask: [Bool],
                                    cutTimes: [Double?]) -> (String, Bool) {
        var scaled: [String] = []

        for i in 0..<n {
            let padDur = max(0.0, maxDur - durations[i])
            let trimPfx: String = (i < cutTimes.count && cutTimes[i] != nil)
                ? "trim=end=\(cutTimes[i]!.fmt(3)),setpts=PTS-STARTPTS,"
                : ""
            let common = "scale=\(cellW):\(cellH):force_original_aspect_ratio=decrease," +
                         "pad=\(cellW):\(cellH):(ow-iw)/2:(oh-ih)/2:black,fps=30,setsar=1,format=yuv420p[v\(i)]"

            if padDur <= 0.001 {
                scaled.append("[\(i):v]\(trimPfx)\(common)")
            } else {
                // 用首帧循环补齐时长
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

        // xstack 布局
        var layouts: [String] = []
        for idx in 0..<n {
            let r = idx / cols
            let c = idx % cols
            layouts.append("\(c * cellW)_\(r * cellH)")
        }
        let inputsConcat = (0..<n).map { "[v\($0)]" }.joined()
        let layoutStr = layouts.joined(separator: "|")

        var parts: [String] = []
        parts.append(scaled.joined(separator: ";"))
        parts.append("\(inputsConcat) xstack=inputs=\(n):layout=\(layoutStr)[vout]")

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
