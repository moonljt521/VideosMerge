//
//  CanvasSizeTests.swift
//  VideoMergerTests
//
//  画布尺寸设置（setCanvasSize）16 对齐自测，对齐 Android setCanvasSize 行为。
//

import XCTest
@testable import VideoMerger

@MainActor
final class CanvasSizeTests: XCTestCase {

    func test宽高向上16对齐() {
        let vm = EditorViewModel()
        vm.setCanvasSize(width: 720, height: 1280)
        XCTAssertEqual(vm.uiState.project.canvasWidth, 720)
        XCTAssertEqual(vm.uiState.project.canvasHeight, 1280)
    }

    func test非对齐值向上取整() {
        let vm = EditorViewModel()
        vm.setCanvasSize(width: 721, height: 1281)
        XCTAssertEqual(vm.uiState.project.canvasWidth, 736)   // (721+15)/16*16
        XCTAssertEqual(vm.uiState.project.canvasHeight, 1296) // (1281+15)/16*16
    }

    func test宽高比换算后的对齐() {
        // 16:9 短边 720 → 1280x720；9:16 短边 720 → 720x1280；2K 1440 → 1440x2560
        let vm = EditorViewModel()
        vm.setCanvasSize(width: 1280, height: 720)
        XCTAssertEqual(vm.uiState.project.canvasWidth, 1280)
        XCTAssertEqual(vm.uiState.project.canvasHeight, 720)

        vm.setCanvasSize(width: 1440, height: 2560)
        XCTAssertEqual(vm.uiState.project.canvasWidth, 1440)
        XCTAssertEqual(vm.uiState.project.canvasHeight, 2560)
    }

    func test相同尺寸不重复入撤销栈() {
        let vm = EditorViewModel()
        // ★ 1080 非 16 对齐 → 实际落入 1088（与 Android 1080P 档位一致）
        vm.setCanvasSize(width: 1080, height: 1920)
        XCTAssertEqual(vm.uiState.project.canvasWidth, 1088)
        XCTAssertEqual(vm.uiState.project.canvasHeight, 1920)
        let canUndo = vm.uiState.canUndo
        vm.setCanvasSize(width: 1080, height: 1920)
        XCTAssertEqual(vm.uiState.canUndo, canUndo)
        XCTAssertEqual(vm.uiState.project.canvasWidth, 1088)
        XCTAssertEqual(vm.uiState.project.canvasHeight, 1920)
    }

    func test过小值钳制到最小16() {
        let vm = EditorViewModel()
        vm.setCanvasSize(width: 8, height: 4)
        // (8+15)/16*16 = 16, (4+15)/16*16 = 16
        XCTAssertEqual(vm.uiState.project.canvasWidth, 16)
        XCTAssertEqual(vm.uiState.project.canvasHeight, 16)
    }
}
