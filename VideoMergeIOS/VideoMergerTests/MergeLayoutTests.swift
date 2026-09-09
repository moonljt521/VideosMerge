//
//  MergeLayoutTests.swift
//  VideoMergerTests
//
//  合并布局（预览与导出共用）单元测试，对齐 Android MergeLayoutTest。
//

import XCTest
@testable import VideoMerger

final class MergeLayoutTests: XCTestCase {

    private func meta(w: Int = 1920, h: Int = 1080) -> VideoMeta {
        VideoMeta(width: w, height: h, duration: 10, hasAudio: true)
    }

    // MARK: - Grid

    func test四视频网格为2x2且单元格16对齐() {
        let spec = MergeLayout.gridSpec(n: 4, aspects: Array(repeating: 16.0 / 9.0, count: 4),
                                        gridCellSize: 720)
        XCTAssertEqual(spec.rows, 2)
        XCTAssertEqual(spec.cols, 2)
        // 720x405 → 16 对齐后 720x400
        XCTAssertEqual(spec.cellW, 720)
        XCTAssertEqual(spec.cellH, 400)
        XCTAssertEqual(spec.outW, 1440)
        XCTAssertEqual(spec.outH, 800)
    }

    func test三视频网格末行留空且矩形行优先() {
        let rects = MergeLayout.gridSpec(n: 3, aspects: Array(repeating: 16.0 / 9.0, count: 3),
                                         gridCellSize: 720).rects()
        XCTAssertEqual(rects[0], LayoutRect(x: 0, y: 0, w: 720, h: 400))
        XCTAssertEqual(rects[1], LayoutRect(x: 720, y: 0, w: 720, h: 400))
        XCTAssertEqual(rects[2], LayoutRect(x: 0, y: 400, w: 720, h: 400))
    }

    func test主导宽高比取出现次数最多者() {
        let aspect = MergeLayout.dominantAspect([16.0 / 9.0, 16.0 / 9.0, 1.0])
        XCTAssertEqual(aspect, 1.778, accuracy: 1e-9)
    }

    func test竖屏视频单元格也16对齐() {
        let spec = MergeLayout.gridSpec(n: 2, aspects: Array(repeating: 9.0 / 16.0, count: 2),
                                        gridCellSize: 720)
        // 405x720 → 400x720；2 个视频为 1 行 2 列
        XCTAssertEqual(spec.cellW, 400)
        XCTAssertEqual(spec.cellH, 720)
        XCTAssertEqual(spec.outW, 800)
        XCTAssertEqual(spec.outH, 720)
    }

    func test网格预览矩形与导出xstack布局一致() {
        let metas = Array(repeating: meta(), count: 3)
        let options = MergeOptions(gridCellSize: 720)
        let command = GridMerger().buildCommand(
            inputPaths: (0..<3).map { "/tmp/\($0).mp4" },
            durations: Array(repeating: 10, count: 3),
            metas: metas,
            outputPath: "/tmp/out.mp4",
            options: options,
            cutTimes: []
        )
        let spec = MergeLayout.gridSpec(n: 3, aspects: metas.map { Double($0.width) / Double($0.height) },
                                        gridCellSize: options.gridCellSize)
        let expectedLayout = spec.rects().prefix(metas.count)
            .map { "\($0.x)_\($0.y)" }
            .joined(separator: "|")
        XCTAssertTrue(command.contains("layout=\(expectedLayout)"),
                      "命令应包含预览布局 \(expectedLayout)\n\(command)")
    }

    // MARK: - Collage

    func test画中画左右布局严格铺满画布() {
        let layout = MergeLayout.collageLayout(
            n: 2, canvasW: 1920, canvasH: 1080, mainRatio: 0.62,
            orient: "left", gap: 0, mainIdx: 0
        )
        XCTAssertEqual(layout.rects()[0], LayoutRect(x: 0, y: 0, w: 1190, h: 1080))
        XCTAssertEqual(layout.rects()[1], LayoutRect(x: 1190, y: 0, w: 730, h: 1080))
        XCTAssertEqual(layout.outW, 1920)
        XCTAssertEqual(layout.outH, 1080)
    }

    func test画中画上下布局主窗口在上() {
        let layout = MergeLayout.collageLayout(
            n: 2, canvasW: 1920, canvasH: 1080, mainRatio: 0.62,
            orient: "top", gap: 0, mainIdx: 0
        )
        XCTAssertEqual(layout.rects()[0], LayoutRect(x: 0, y: 0, w: 1920, h: 670))
        XCTAssertEqual(layout.rects()[1], LayoutRect(x: 0, y: 670, w: 1920, h: 410))
    }

    func test画中画四视频副窗口纵向均分() {
        let layout = MergeLayout.collageLayout(
            n: 4, canvasW: 1920, canvasH: 1080, mainRatio: 0.62,
            orient: "left", gap: 0, mainIdx: 0
        )
        let rects = layout.rects()
        XCTAssertEqual(rects[0], LayoutRect(x: 0, y: 0, w: 1190, h: 1080))
        XCTAssertEqual(rects[1], LayoutRect(x: 1190, y: 0, w: 730, h: 360))
        XCTAssertEqual(rects[2], LayoutRect(x: 1190, y: 360, w: 730, h: 360))
        XCTAssertEqual(rects[3], LayoutRect(x: 1190, y: 720, w: 730, h: 360))
        XCTAssertEqual(rects[1].h + rects[2].h + rects[3].h, 1080)
    }

    func test画中画主窗口索引生效() {
        let layout = MergeLayout.collageLayout(
            n: 2, canvasW: 1920, canvasH: 1080, mainRatio: 0.62,
            orient: "left", gap: 0, mainIdx: 1
        )
        let rects = layout.rects()
        XCTAssertEqual(rects[0], LayoutRect(x: 1190, y: 0, w: 730, h: 1080))
        XCTAssertEqual(rects[1], LayoutRect(x: 0, y: 0, w: 1190, h: 1080))
    }

    func test画中画预览矩形与导出xstack布局一致() {
        let metas = Array(repeating: meta(), count: 3)
        let options = MergeOptions(canvasWidth: 1920, canvasHeight: 1080)
        let command = CollageMerger().buildCommand(
            inputPaths: (0..<3).map { "/tmp/\($0).mp4" },
            durations: Array(repeating: 10, count: 3),
            metas: metas,
            outputPath: "/tmp/out.mp4",
            options: options,
            cutTimes: []
        )
        let layout = MergeLayout.collageLayout(
            n: 3,
            canvasW: options.canvasWidth.toAligned16,
            canvasH: options.canvasHeight.toAligned16,
            mainRatio: options.collageMainRatio,
            orient: options.collageOrient.rawValue,
            gap: options.gap,
            mainIdx: options.collageMainIndex
        )
        let expectedLayout = layout.rects().map { "\($0.x)_\($0.y)" }.joined(separator: "|")
        XCTAssertTrue(command.contains("layout=\(expectedLayout)"),
                      "命令应包含预览布局 \(expectedLayout)\n\(command)")
    }

    // MARK: - Photo Wall

    func test照片墙同种子结果可复现() {
        let a = MergeLayout.photoWallLayout(n: 5, canvasW: 1920, canvasH: 1080, seed: 42)
        let b = MergeLayout.photoWallLayout(n: 5, canvasW: 1920, canvasH: 1080, seed: 42)
        XCTAssertEqual(a.rects, b.rects)
        XCTAssertEqual(a.rotations, b.rotations)
    }

    func test照片墙不同种子版式不同() {
        let a = MergeLayout.photoWallLayout(n: 5, canvasW: 1920, canvasH: 1080, seed: 42)
        let b = MergeLayout.photoWallLayout(n: 5, canvasW: 1920, canvasH: 1080, seed: 43)
        XCTAssertNotEqual(a.rects, b.rects)
    }

    func test照片墙矩形不越界且数量正确() {
        let wall = MergeLayout.photoWallLayout(n: 6, canvasW: 1920, canvasH: 1080, seed: 7)
        XCTAssertEqual(wall.rects.count, 6)
        XCTAssertEqual(wall.rotations.count, 6)
        for r in wall.rects {
            XCTAssertTrue(r.x >= 0 && r.x + r.w <= 1920, "x=\(r.x) w=\(r.w)")
            XCTAssertTrue(r.y >= 0 && r.y + r.h <= 1080, "y=\(r.y) h=\(r.h)")
            XCTAssertTrue(r.w > 0 && r.h > 0)
        }
    }

    func test照片墙旋转角在正负3点5度内() {
        let wall = MergeLayout.photoWallLayout(n: 4, canvasW: 1920, canvasH: 1080, seed: 99)
        for rot in wall.rotations {
            XCTAssertLessThanOrEqual(abs(rot), 3.5, "rotation=\(rot)")
        }
    }
}
