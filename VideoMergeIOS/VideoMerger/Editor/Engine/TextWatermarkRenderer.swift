//
//  TextWatermarkRenderer.swift
//  VideoMerger
//
//  iOS 移植自 Android TextWatermarkRenderer.kt
//  Canvas 渲染文字为透明 PNG（ffmpeg GPL 版才有 drawtext，统一用 PNG overlay 方案）
//

import UIKit

enum TextWatermarkRenderer {

    /// 渲染文字为 PNG 文件（带黑色描边增强可读性）
    static func renderToPng(text: String, fontSize: Int, colorStr: String,
                            opacity: Double, border: Bool) -> TextPngResult? {
        let color = parseColor(colorStr)
        // ★ 常规字重（对齐 Android Typeface.DEFAULT，原 bold 会比 Android 明显偏粗）
        let font = UIFont.systemFont(ofSize: CGFloat(max(fontSize, 12)))
        // 描边偏移随字号缩放（对齐 Android strokeWidth = fontSize/6，最小 2px）
        let strokeOffset = max(CGFloat(fontSize) / 6, 2)
        let attrs: [NSAttributedString.Key: Any] = [
            .font: font,
            .foregroundColor: color.withAlphaComponent(CGFloat(opacity)),
        ]

        let size = (text as NSString).size(withAttributes: attrs)
        let pad: CGFloat = border ? 10 : 2
        let width = Int(ceil(size.width)) + Int(pad) * 2
        let height = Int(ceil(size.height)) + Int(pad) * 2
        guard width > 0, height > 0 else { return nil }

        let renderer = UIGraphicsImageRenderer(size: CGSize(width: width, height: height))
        let image = renderer.image { ctx in
            if border {
                // 黑色描边（八方向偏移绘制，偏移量随字号缩放）
                let strokeAttrs: [NSAttributedString.Key: Any] = [
                    .font: font,
                    .foregroundColor: UIColor.black.withAlphaComponent(CGFloat(opacity)),
                ]
                let o = strokeOffset
                for (dx, dy) in [(-o, 0), (o, 0), (0, -o), (0, o), (-o * 0.75, -o * 0.75), (o * 0.75, o * 0.75), (-o * 0.75, o * 0.75), (o * 0.75, -o * 0.75)] {
                    (text as NSString).draw(
                        at: CGPoint(x: pad + dx, y: pad + dy),
                        withAttributes: strokeAttrs)
                }
            }
            (text as NSString).draw(at: CGPoint(x: pad, y: pad), withAttributes: attrs)
        }

        guard let data = image.pngData() else { return nil }
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("text_wm_\(Int(Date().timeIntervalSince1970 * 1000)).png")
        do {
            try data.write(to: url)
        } catch {
            return nil
        }
        return TextPngResult(file: url, width: width, height: height)
    }

    /// 九宫格位置 → 画布上的 overlay 坐标
    static func calcOverlayPosition(_ position: String, _ pngW: Int, _ pngH: Int,
                                    _ canvasW: Int, _ canvasH: Int) -> (Int, Int) {
        let pad = 12
        switch position {
        case "top-left": return (pad, pad)
        case "top-center": return ((canvasW - pngW) / 2, pad)
        case "top-right": return (canvasW - pngW - pad, pad)
        case "center-left": return (pad, (canvasH - pngH) / 2)
        case "center": return ((canvasW - pngW) / 2, (canvasH - pngH) / 2)
        case "center-right": return (canvasW - pngW - pad, (canvasH - pngH) / 2)
        case "bottom-left": return (pad, canvasH - pngH - pad)
        case "bottom-center": return ((canvasW - pngW) / 2, canvasH - pngH - pad)
        default: return (canvasW - pngW - pad, canvasH - pngH - pad) // bottom-right
        }
    }

    private static func parseColor(_ s: String) -> UIColor {
        switch s.lowercased() {
        case "white": return .white
        case "black": return .black
        case "red": return .red
        case "yellow": return .yellow
        case "green": return .green
        case "blue": return .blue
        default:
            var hex = s.replacingOccurrences(of: "#", with: "")
            if hex.count == 6, let v = UInt64(hex, radix: 16) {
                return UIColor(red: CGFloat((v >> 16) & 0xFF) / 255,
                               green: CGFloat((v >> 8) & 0xFF) / 255,
                               blue: CGFloat(v & 0xFF) / 255, alpha: 1)
            }
            hex = ""
            return .white
        }
    }
}
