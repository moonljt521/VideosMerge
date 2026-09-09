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

        // 1. 网格与单元格尺寸（与 UI 预览共用 MergeLayout，保证所见即所得）
        let spec = MergeLayout.gridSpec(
            n: n,
            aspects: metas.map { Double($0.width) / Double($0.height) },
            gridCellSize: options.gridCellSize
        )
        let rows = spec.rows
        let cols = spec.cols
        let cellW = spec.cellW
        let cellH = spec.cellH

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
