//
//  PipMaskRenderer.swift
//  VideoMerger
//
//  iOS 移植自 Android PipMaskRenderer.kt
//  画中画形状蒙版（alphamerge 用）与描边 PNG
//

import UIKit

enum PipMaskRenderer {

    /// 形状蒙版：白色形状 + 透明背景（alphamerge 用亮度替换 alpha）
    static func renderMask(width: Int, height: Int, shape: PipShape, radius: CGFloat) -> URL? {
        render(width: width, height: height, shape: shape, radius: radius, stroke: false)
    }

    /// 白色描边框
    static func renderBorder(width: Int, height: Int, shape: PipShape, radius: CGFloat, strokeWidth: Float) -> URL? {
        render(width: width, height: height, shape: shape, radius: radius, stroke: true, strokeWidth: strokeWidth)
    }

    private static func render(width: Int, height: Int, shape: PipShape, radius: CGFloat,
                               stroke: Bool, strokeWidth: Float = 0) -> URL? {
        guard width > 0, height > 0 else { return nil }
        let size = CGSize(width: width, height: height)
        let renderer = UIGraphicsImageRenderer(size: size)
        let image = renderer.image { _ in
            // 描边居中于形状边缘，向内缩进半个描边宽度避免被画布裁掉（对齐 Android）
            let inset = stroke ? CGFloat(strokeWidth) / 2 : 0
            let w = CGFloat(width), h = CGFloat(height)
            let path: UIBezierPath
            switch shape {
            case .circle:
                // ★ 内切正圆：直径取宽高中的较小者，而不是椭圆
                let d = min(w, h) - inset * 2
                path = UIBezierPath(ovalIn: CGRect(x: (w - d) / 2, y: (h - d) / 2, width: d, height: d))
            case .rounded:
                path = UIBezierPath(roundedRect: CGRect(x: inset, y: inset, width: w - inset * 2, height: h - inset * 2),
                                    cornerRadius: radius)
            default:
                path = UIBezierPath(rect: CGRect(x: inset, y: inset, width: w - inset * 2, height: h - inset * 2))
            }
            if stroke {
                UIColor.white.setStroke()
                path.lineWidth = CGFloat(strokeWidth)
                path.stroke()
            } else {
                UIColor.white.setFill()
                path.fill()
            }
        }
        guard let data = image.pngData() else { return nil }
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("pip_mask_\(Int(Date().timeIntervalSince1970 * 1000))_\(Int.random(in: 0..<9999)).png")
        do { try data.write(to: url) } catch { return nil }
        return url
    }
}
