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
        let image = renderer.image { ctx in
            let cg = ctx.cgContext
            let rect = CGRect(origin: .zero, size: size)
            let path: UIBezierPath
            switch shape {
            case .circle:
                path = UIBezierPath(ovalIn: rect)
            case .rounded:
                path = UIBezierPath(roundedRect: rect, cornerRadius: radius)
            default:
                path = UIBezierPath(rect: rect)
            }
            if stroke {
                UIColor.white.setStroke()
                path.lineWidth = CGFloat(strokeWidth)
                path.stroke()
            } else {
                UIColor.white.setFill()
                path.fill()
            }
            _ = cg
        }
        guard let data = image.pngData() else { return nil }
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("pip_mask_\(Int(Date().timeIntervalSince1970 * 1000))_\(Int.random(in: 0..<9999)).png")
        do { try data.write(to: url) } catch { return nil }
        return url
    }
}
