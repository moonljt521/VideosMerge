import CoreGraphics
import ImageIO
import Foundation
import UniformTypeIdentifiers

// 1024x1024 sRGB 位图
let size = 1024
let cs = CGColorSpace(name: CGColorSpace.sRGB)!
let ctx = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8,
                    bytesPerRow: 0, space: cs,
                    bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!

// 翻转坐标系，让 y 轴向下（与设计稿一致）
ctx.translateBy(x: 0, y: CGFloat(size))
ctx.scaleBy(x: 1, y: -1)

func rgb(_ hex: UInt32, _ alpha: CGFloat = 1) -> CGColor {
    CGColor(srgbRed: CGFloat((hex >> 16) & 0xFF) / 255,
            green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255, alpha: alpha)
}

// 背景：深海军蓝对角渐变 #16283E -> #0A141E
let colors = [rgb(0x16283E), rgb(0x0A141E)] as CFArray
let grad = CGGradient(colorsSpace: cs, colors: colors, locations: [0, 1])!
ctx.drawLinearGradient(grad, start: CGPoint(x: 0, y: 0), end: CGPoint(x: 1024, y: 1024), options: [])

// 青色双指环（剪刀柄 / 双视频源）
let cyan = rgb(0x26C6DA)
ctx.setStrokeColor(cyan)
ctx.setLineWidth(50)
for cy in [407.0, 617.0] {
    ctx.strokeEllipse(in: CGRect(x: 272 - 60, y: cy - 60, width: 120, height: 120))
}

// 白色三角 = 刀刃 = 播放键（圆角连接）
let white = rgb(0xFFFFFF)
ctx.setFillColor(white)
ctx.setStrokeColor(white)
ctx.setLineWidth(50)
ctx.setLineJoin(.round)
let tri = [CGPoint(x: 342, y: 397), CGPoint(x: 342, y: 627), CGPoint(x: 812, y: 512)]
ctx.addLines(between: tri)
ctx.closePath()
ctx.drawPath(using: .fillStroke)

// 深色枢轴圆点
ctx.setFillColor(rgb(0x0B1622))
ctx.fillEllipse(in: CGRect(x: 392 - 25, y: 512 - 25, width: 50, height: 50))

// 导出 PNG
let img = ctx.makeImage()!
let outURL = URL(fileURLWithPath: CommandLine.arguments.count > 1
    ? CommandLine.arguments[1]
    : "AppIcon-1024.png")
let dest = CGImageDestinationCreateWithURL(outURL as CFURL, UTType.png.identifier as CFString, 1, nil)!
CGImageDestinationAddImage(dest, img, nil)
CGImageDestinationFinalize(dest)
print("saved: \(outURL.path)")
