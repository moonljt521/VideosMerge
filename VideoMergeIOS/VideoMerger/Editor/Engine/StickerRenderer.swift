//
//  StickerRenderer.swift
//  VideoMerger
//
//  iOS 移植自 Android StickerRenderer.kt
//  emoji 渲染为透明背景 PNG（画中画叠加）
//

import UIKit

enum StickerRenderer {

    /// 渲染 emoji 到 PNG（固定 256x256 画布居中，避免彩色字形测量不准导致裁切）
    static func renderToPng(_ emoji: String, size: Int = 256) -> URL? {
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: size, height: size))
        let image = renderer.image { ctx in
            let font = UIFont.systemFont(ofSize: CGFloat(size) * 0.7)
            let attrs: [NSAttributedString.Key: Any] = [.font: font]
            let str = emoji as NSString
            let textSize = str.size(withAttributes: attrs)
            let origin = CGPoint(x: (CGFloat(size) - textSize.width) / 2,
                                 y: (CGFloat(size) - textSize.height) / 2)
            str.draw(at: origin, withAttributes: attrs)
        }
        guard let data = image.pngData() else { return nil }
        let dir = EditorViewModel.editorMediaDir
        let url = dir.appendingPathComponent("sticker_\(Int(Date().timeIntervalSince1970 * 1000)).png")
        do { try data.write(to: url) } catch { return nil }
        return url
    }
}
