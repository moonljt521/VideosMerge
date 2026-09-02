//
//  GifCommandBuilder.swift
//  VideoMerger
//
//  视频转 GIF 的 ffmpeg 命令构造（纯函数，便于单元测试）。
//  与 Android 端 GifCommandBuilder.kt、根目录 video_to_gif.py 原型保持一致：
//  palettegen/paletteuse 两步法，用 split 避免视频被处理两次：
//    [0:v] → fps → scale → split → [gif] + [palin]
//    [palin] → palettegen → [palette]
//    [gif][palette] → paletteuse → [vout]
//

import Foundation

/// GIF 质量档位（对应 video_to_gif.py 的 fast/normal/high）
enum GifQuality: String, CaseIterable {
    case fast = "快速"
    case normal = "标准"
    case high = "高质量"
}

enum GifCommandBuilder {

    /// 按目标宽度等比计算 GIF 宽高。
    /// 尺寸取偶数，避免个别播放器/平台对奇数尺寸 GIF 兼容性差。
    static func targetDimensions(sourceWidth: Int, sourceHeight: Int, targetWidth: Int) -> (width: Int, height: Int) {
        precondition(sourceWidth > 0 && sourceHeight > 0, "源视频尺寸无效")
        let w = max(2, min(4096, targetWidth))
        let h = max(2, Int((Double(sourceHeight) * Double(w) / Double(sourceWidth)).rounded()))
        return (w - w % 2, h - h % 2)
    }

    static func build(inputPath: String,
                      outputPath: String,
                      fps: Int,
                      width: Int,
                      height: Int,
                      quality: GifQuality,
                      loop: Bool) -> String {
        // 质量档位 → palettegen.stats_mode / paletteuse.dither（与 video_to_gif.py 相同映射）
        let statsMode: String
        let dither: String
        switch quality {
        case .fast:
            statsMode = "full"; dither = "none"
        case .normal:
            statsMode = "diff"; dither = "sierra2_4a"
        case .high:
            statsMode = "diff"; dither = "bayer:bayer_scale=3"
        }
        // ffmpeg GIF muxer 语义：-loop 0 无限循环，-1 只播一次
        let loopParam = loop ? "0" : "-1"
        return "-y -i \"\(inputPath)\" " +
            "-filter_complex \"[0:v]fps=\(fps),scale=\(width):\(height):flags=lanczos,split=2[gif][palin];" +
            "[palin]palettegen=stats_mode=\(statsMode)[palette];" +
            "[gif][palette]paletteuse=dither=\(dither)[vout]\" " +
            "-map \"[vout]\" -loop \(loopParam) -f gif \"\(outputPath)\""
    }
}
