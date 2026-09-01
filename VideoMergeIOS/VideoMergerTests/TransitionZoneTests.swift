//
//  TransitionZoneTests.swift
//  VideoMergerTests
//
//  移植自 Android TransitionZoneTest.kt：转场重叠区（实时预览用）判定自测。
//

import XCTest
@testable import VideoMerger

final class TransitionZoneTests: XCTestCase {

    private func clip(_ duration: Double,
                      _ transition: TransitionEffect = .none,
                      transDur: Double = 0.8) -> Clip {
        var c = Clip(mediaPath: "/tmp/x.mp4", mediaName: "x",
                     mediaDuration: duration, width: 100, height: 100, hasAudio: false)
        c.trimEnd = duration
        c.transition = transition
        c.transitionDuration = transDur
        return c
    }

    func test播放头在重叠区内能找到转场区() {
        // a [0,5] fade 1.0 → b 从 4.0 开始，重叠区 [4.0, 5.0]
        let laid = relayoutMainTrackClips([clip(5.0, .fade, transDur: 1.0), clip(7.0)])
        let zone = findTransitionZone(laid, 4.5)
        XCTAssertNotNil(zone)
        XCTAssertEqual(zone!.start, 4.0, accuracy: 1e-9)
        XCTAssertEqual(zone!.end, 5.0, accuracy: 1e-9)
        XCTAssertEqual(zone!.progress(4.5), 0.5, accuracy: 1e-9)
    }

    func test重叠区外返回nil() {
        let laid = relayoutMainTrackClips([clip(5.0, .fade, transDur: 1.0), clip(7.0)])
        XCTAssertNil(findTransitionZone(laid, 3.9))   // 区间前
        XCTAssertNil(findTransitionZone(laid, 5.01))  // 区间后
    }

    func testNONE转场不构成预览区() {
        let laid = relayoutMainTrackClips([clip(5.0), clip(7.0)])
        // NONE 重叠只有 0.01s，低于预览阈值
        XCTAssertNil(findTransitionZone(laid, laid[1].timelineStart + 0.005))
    }

    func test进度线性且钳制到01() {
        let a = clip(5.0, .fade, transDur: 1.0)
        let b = clip(7.0)
        let laid = relayoutMainTrackClips([a, b])
        let zone = findTransitionZone(laid, 4.25)!
        XCTAssertEqual(zone.progress(4.25), 0.25, accuracy: 1e-9)
        XCTAssertEqual(zone.progress(3.0), 0.0, accuracy: 1e-9)  // 越界钳制
        XCTAssertEqual(zone.progress(6.0), 1.0, accuracy: 1e-9)
    }

    func test多段连续转场各自独立成区() {
        let laid = relayoutMainTrackClips([
            clip(5.0, .fade, transDur: 1.0),
            clip(5.0, .fade, transDur: 1.0),
            clip(5.0),
        ])
        XCTAssertNotNil(findTransitionZone(laid, laid[1].timelineStart + 0.5))
        XCTAssertNil(findTransitionZone(laid, laid[2].timelineStart - 2.0)) // b 与 c 无转场
    }
}
