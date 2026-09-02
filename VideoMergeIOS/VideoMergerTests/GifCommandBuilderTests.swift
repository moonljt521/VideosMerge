//
//  GifCommandBuilderTests.swift
//  VideoMergerTests
//
//  GIF 命令构造的纯逻辑测试：等比尺寸取偶、质量档位参数、循环参数。
//  与 Android 端 GifCommandBuilderTest.kt 对齐。
//

import XCTest
@testable import VideoMerger

final class GifCommandBuilderTests: XCTestCase {

    // MARK: - targetDimensions

    func test等比缩放16比9源到480宽() {
        let dims = GifCommandBuilder.targetDimensions(sourceWidth: 1920, sourceHeight: 1080, targetWidth: 480)
        XCTAssertEqual(dims.width, 480)
        XCTAssertEqual(dims.height, 270)
    }

    func test竖屏源按宽度等比缩放() {
        let dims = GifCommandBuilder.targetDimensions(sourceWidth: 1080, sourceHeight: 1920, targetWidth: 360)
        XCTAssertEqual(dims.width, 360)
        XCTAssertEqual(dims.height, 640)
    }

    func test奇数高度四舍五入后取偶() {
        // 481 为奇数 → 480
        let dims = GifCommandBuilder.targetDimensions(sourceWidth: 720, sourceHeight: 481, targetWidth: 720)
        XCTAssertEqual(dims.width, 720)
        XCTAssertEqual(dims.height, 480)
    }

    func test目标宽度超出范围被收敛() {
        let dims = GifCommandBuilder.targetDimensions(sourceWidth: 1920, sourceHeight: 1080, targetWidth: 9999)
        XCTAssertEqual(dims.width, 4096)
    }

    // MARK: - build

    func test标准档包含调色板与sierra抖动() {
        let cmd = GifCommandBuilder.build(
            inputPath: "/tmp/in.mp4", outputPath: "/tmp/out.gif",
            fps: 10, width: 480, height: 270, quality: .normal, loop: true
        )
        XCTAssertTrue(cmd.contains("fps=10,scale=480:270:flags=lanczos,split=2"))
        XCTAssertTrue(cmd.contains("palettegen=stats_mode=diff"))
        XCTAssertTrue(cmd.contains("paletteuse=dither=sierra2_4a"))
        XCTAssertTrue(cmd.contains("-map \"[vout]\""))
        XCTAssertTrue(cmd.contains("-f gif \"/tmp/out.gif\""))
    }

    func test高质量档使用bayer抖动() {
        let cmd = GifCommandBuilder.build(
            inputPath: "in.mp4", outputPath: "out.gif",
            fps: 15, width: 480, height: 270, quality: .high, loop: true
        )
        XCTAssertTrue(cmd.contains("paletteuse=dither=bayer:bayer_scale=3"))
        XCTAssertTrue(cmd.contains("fps=15"))
    }

    func test快速档使用全帧调色板且不抖动() {
        let cmd = GifCommandBuilder.build(
            inputPath: "in.mp4", outputPath: "out.gif",
            fps: 8, width: 240, height: 135, quality: .fast, loop: true
        )
        XCTAssertTrue(cmd.contains("palettegen=stats_mode=full"))
        XCTAssertTrue(cmd.contains("paletteuse=dither=none"))
    }

    func test循环开关映射到loop参数() {
        func loopParam(_ loop: Bool) -> String {
            GifCommandBuilder.build(
                inputPath: "in.mp4", outputPath: "out.gif",
                fps: 10, width: 480, height: 270, quality: .normal, loop: loop
            )
            .components(separatedBy: "-loop ")[1]
            .components(separatedBy: " ")[0]
        }
        // ffmpeg GIF muxer 语义：0 无限循环，-1 只播一次
        XCTAssertEqual(loopParam(true), "0")
        XCTAssertEqual(loopParam(false), "-1")
    }
}
