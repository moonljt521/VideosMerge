//
//  TimelineLayoutTests.swift
//  VideoMergerTests
//
//  移植自 Android TimelineLayoutTest.kt：时间轴布局（转场 = 重叠）数学自测。
//  保证时间轴布局与导出 xfade 的重叠时长严格一致。
//

import XCTest
@testable import VideoMerger

final class TimelineLayoutTests: XCTestCase {

    private func clip(_ duration: Double,
                      _ transition: TransitionEffect = .none,
                      transDur: Double = 0.8,
                      speed: Double = 1.0) -> Clip {
        var c = Clip(mediaPath: "/tmp/x.mp4", mediaName: "x",
                     mediaDuration: duration, width: 1088, height: 1920, hasAudio: true)
        c.trimEnd = duration
        c.speed = speed
        c.transition = transition
        c.transitionDuration = transDur
        return c
    }

    func test无转场时按极短重叠拼接() {
        // NONE 也保留 0.01s 重叠（与导出 xfade 链的衔接淡变一致）
        let laid = relayoutMainTrackClips([clip(5.0), clip(7.0), clip(3.0)])
        XCTAssertEqual(laid[0].timelineStart, 0.0, accuracy: 1e-9)
        XCTAssertEqual(laid[1].timelineStart, 5.0 - TRANSITION_NONE_OVERLAP, accuracy: 1e-9)
        XCTAssertEqual(laid[2].timelineStart, 12.0 - TRANSITION_NONE_OVERLAP * 2, accuracy: 1e-9)
        XCTAssertEqual(laid[2].timelineEnd, 15.0 - TRANSITION_NONE_OVERLAP * 2, accuracy: 1e-9)
    }

    func test转场处片段重叠且总时长缩短() {
        let laid = relayoutMainTrackClips([clip(5.0, .fade, transDur: 0.8), clip(7.0)])
        // 下一片段提前转场时长开始
        XCTAssertEqual(laid[1].timelineStart, 5.0 - 0.8, accuracy: 1e-9)
        // 总时长 = 各片段时长之和 - 重叠
        XCTAssertEqual(laid[1].timelineEnd, 5.0 + 7.0 - 0.8, accuracy: 1e-9)
    }

    func testNONE转场使用极短重叠保持与导出一致() {
        let a = clip(5.0)
        let b = clip(7.0)
        XCTAssertEqual(effectiveTransitionOverlap(prev: a, next: b), TRANSITION_NONE_OVERLAP, accuracy: 1e-9)
        let laid = relayoutMainTrackClips([a, b])
        XCTAssertEqual(laid[1].timelineStart, 5.0 - TRANSITION_NONE_OVERLAP, accuracy: 1e-9)
    }

    func test转场时长不超过较短片段的八成() {
        let a = clip(1.0, .fade, transDur: 3.0)
        let b = clip(10.0)
        XCTAssertEqual(effectiveTransitionOverlap(prev: a, next: b), 0.8, accuracy: 1e-9)

        let laid = relayoutMainTrackClips([a, b])
        XCTAssertEqual(laid[1].timelineStart, 1.0 - 0.8, accuracy: 1e-9)
    }

    func test链式转场累计重叠() {
        let laid = relayoutMainTrackClips([
            clip(5.0, .fade, transDur: 1.0),
            clip(5.0, .fade, transDur: 1.0),
            clip(5.0),
        ])
        XCTAssertEqual(laid[1].timelineStart, 4.0, accuracy: 1e-9)
        XCTAssertEqual(laid[2].timelineStart, 8.0, accuracy: 1e-9)
        XCTAssertEqual(laid[2].timelineEnd, 13.0, accuracy: 1e-9) // 15 - 2 个转场重叠
    }

    func test变速影响重叠上限与总时长() {
        // timelineDuration = 4/2 = 2 → 上限 = 2*0.8 = 1.6
        let a = clip(4.0, .fade, transDur: 2.0, speed: 2.0)
        let b = clip(10.0)
        XCTAssertEqual(effectiveTransitionOverlap(prev: a, next: b), 1.6, accuracy: 1e-9)

        let laid = relayoutMainTrackClips([a, b])
        XCTAssertEqual(laid[1].timelineStart, 2.0 - 1.6, accuracy: 1e-9)
        XCTAssertEqual(laid[1].timelineEnd, 2.0 - 1.6 + 10.0, accuracy: 1e-9)
    }

    func test空列表与单片段安全() {
        XCTAssertEqual(relayoutMainTrackClips([]).count, 0)
        let single = relayoutMainTrackClips([clip(6.0)])
        XCTAssertEqual(single[0].timelineStart, 0.0, accuracy: 1e-9)
        XCTAssertEqual(single[0].timelineEnd, 6.0, accuracy: 1e-9)
    }
}
