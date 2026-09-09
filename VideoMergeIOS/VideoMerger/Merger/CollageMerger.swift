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

        let canvasW = options.canvasWidth.toAligned16
        let canvasH = options.canvasHeight.toAligned16

        // 与 UI 预览共用 MergeLayout，保证所见即所得
        let layout = MergeLayout.collageLayout(
            n: n, canvasW: canvasW, canvasH: canvasH,
            mainRatio: options.collageMainRatio,
            orient: options.collageOrient.rawValue,
            gap: options.gap, mainIdx: mainIdx
        )
        let sizes = layout.sizes
        let positions = layout.positions

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
        cmd += "-c:v h264_videotoolbox -b:v 8M -pix_fmt yuv420p " // ★ 硬件编码（对齐 Android mediacodec 与编辑器 videotoolbox）
        if hasAudio {
            cmd += "-map \"[aout]\" -c:a aac -b:a 192k "
        }
        cmd += "\"\(outputPath)\""
        return cmd
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
