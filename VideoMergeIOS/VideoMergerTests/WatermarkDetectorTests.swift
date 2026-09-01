//
//  WatermarkDetectorTests.swift
//  VideoMergerTests
//
//  移植自 Android WatermarkAnalyzerTest.kt：静态水印检测核心算法自测（纯函数）。
//

import XCTest
@testable import VideoMerger

final class WatermarkDetectorTests: XCTestCase {

    private let w = 90
    private let h = 160

    /// 生成一帧：随帧号平移的渐变背景 + 可选的固定"水印"方块
    private func frame(_ index: Int, _ logo: [Double]? = nil) -> [Float] {
        var out = [Float](repeating: 0, count: w * h)
        for y in 0..<h {
            for x in 0..<w {
                // 背景：随 index 移动的平滑正弦波场（模拟水面/光影，无空间接缝）
                let v = 127.0 + 90.0 * sin(Double(x) * 0.35 + Double(index) * 0.9)
                                    * sin(Double(y) * 0.23 + Double(index) * 0.7)
                out[y * w + x] = Float(v)
            }
        }
        if let logo = logo {
            // logo 区域：固定的条纹亮块（模拟文字 logo 的内部笔画边缘）
            let x0 = Int(logo[0] * Double(w)), y0 = Int(logo[1] * Double(h))
            let x1 = x0 + Int(logo[2] * Double(w)), y1 = y0 + Int(logo[3] * Double(h))
            for y in y0..<y1 {
                for x in x0..<x1 {
                    out[y * w + x] = ((x + y * 2) % 8) < 4 ? 240 : 60
                }
            }
        }
        return out
    }

    /// 半透明 logo：像素 = 0.5×logo图案 + 0.5×动态背景（时间方差被背景抬高）
    private func frameWithTranslucentLogo(_ index: Int, _ logo: [Double]) -> [Float] {
        var bg = frame(index)
        let x0 = Int(logo[0] * Double(w)), y0 = Int(logo[1] * Double(h))
        let x1 = x0 + Int(logo[2] * Double(w)), y1 = y0 + Int(logo[3] * Double(h))
        for y in y0..<y1 {
            for x in x0..<x1 {
                let pattern: Float = ((x + y * 2) % 8) < 4 ? 240 : 60
                bg[y * w + x] = 0.5 * pattern + 0.5 * bg[y * w + x]
            }
        }
        return bg
    }

    private let bottomRightLogo: [Double] = [0.68, 0.82, 0.25, 0.12]

    func test半透明水印经均值图边缘通道检出() {
        // 静态掩码抓不到（方差被背景抬高），但均值图会留下 logo 幽灵边缘
        let frames = (0..<12).map { frameWithTranslucentLogo($0, bottomRightLogo) }
        let found = WatermarkDetector.analyze(frames: frames, w: w, h: h)
        XCTAssertFalse(found.isEmpty, "半透明水印应被检出: \(found)")

        let logo = found[0]
        let cx = logo[0] + logo[2] / 2
        let cy = logo[1] + logo[3] / 2
        XCTAssertTrue(cx >= bottomRightLogo[0] && cx <= bottomRightLogo[0] + bottomRightLogo[2] + 0.05,
                      "检测框中心 x 应落在水印附近 (\(cx))")
        XCTAssertTrue(cy >= bottomRightLogo[1] && cy <= bottomRightLogo[1] + bottomRightLogo[3] + 0.05,
                      "检测框中心 y 应落在水印附近 (\(cy))")
    }

    func test能检测到右下角静态水印() {
        let frames = (0..<12).map { frame($0, bottomRightLogo) }
        let found = WatermarkDetector.analyze(frames: frames, w: w, h: h)
        XCTAssertFalse(found.isEmpty, "应至少检测到 1 个区域")

        let logo = found[0]
        let cx = logo[0] + logo[2] / 2
        let cy = logo[1] + logo[3] / 2
        XCTAssertTrue(cx >= bottomRightLogo[0] && cx <= bottomRightLogo[0] + bottomRightLogo[2],
                      "检测框中心 x 应落在水印内 (\(cx))")
        XCTAssertTrue(cy >= bottomRightLogo[1] && cy <= bottomRightLogo[1] + bottomRightLogo[3],
                      "检测框中心 y 应落在水印内 (\(cy))")
    }

    func test无静态区时返回空() {
        let frames = (0..<12).map { frame($0) }
        let found = WatermarkDetector.analyze(frames: frames, w: w, h: h)
        XCTAssertTrue(found.isEmpty)
    }

    func test全画面静止时放弃检测() {
        // 冻结帧：所有帧完全一致 → 满屏低方差，应主动放弃而非满屏误报
        let frozen = frame(0, bottomRightLogo)
        let frames = (0..<12).map { _ in frozen }
        let found = WatermarkDetector.analyze(frames: frames, w: w, h: h)
        XCTAssertTrue(found.isEmpty)
    }

    func test检测框面积合理() {
        let frames = (0..<12).map { frame($0, bottomRightLogo) }
        let found = WatermarkDetector.analyze(frames: frames, w: w, h: h)
        for r in found {
            let area = r[2] * r[3]
            XCTAssertLessThan(area, 0.12, "面积应小于 12%（实际 \(area)）")
            XCTAssertGreaterThan(area, 0.0005, "面积不能过小（实际 \(area)）")
        }
    }

    func test静止背景块被环带检验剔除() {
        // 大块静止区域（如纯色墙壁）：区域本身和周围环带都静止 → 应被剔除
        let frames = (0..<12).map { idx -> [Float] in
            var f = frame(idx)
            // 右下角一块 20%x20% 的区域完全静止
            for y in Int(Double(h) * 0.7)..<h {
                for x in Int(Double(w) * 0.7)..<w {
                    f[y * w + x] = 128
                }
            }
            return f
        }
        let found = WatermarkDetector.analyze(frames: frames, w: w, h: h)
        XCTAssertTrue(found.isEmpty, "静止背景块不应被误判为水印: \(found)")
    }

    func test细长条被剔除() {
        // 底部横向静止细条（黑边/字幕条）：长宽比 > 12 → 剔除
        let frames = (0..<12).map { idx -> [Float] in
            var f = frame(idx)
            for x in 0..<w {
                for y in Int(Double(h) * 0.95)..<h {
                    f[y * w + x] = 10
                }
            }
            return f
        }
        let found = WatermarkDetector.analyze(frames: frames, w: w, h: h)
        XCTAssertTrue(found.isEmpty, "细长条不应被误判为水印: \(found)")
    }

    func test膨胀与连通域基本正确() {
        var mask = [Bool](repeating: false, count: w * h)
        mask[10 * w + 10] = true
        mask[10 * w + 12] = true
        let dilated = WatermarkDetector.dilate(mask, w: w, h: h, radius: 2)
        // 膨胀后两点应连通为一个连通域
        let boxes = WatermarkDetector.connectedBoxes(dilated, w: w, h: h)
        XCTAssertEqual(boxes.count, 1)
    }
}
