//
//  ShiftOverlaysTests.swift
//  VideoMergerTests
//
//  移植自 Android ShiftOverlaysTest.kt：主轨增删后叠加元素（字幕/画中画）联动平移的自测。
//

import XCTest
@testable import VideoMerger

final class ShiftOverlaysTests: XCTestCase {

    private func project(_ subtitles: [Subtitle], _ pipClips: [Clip]) -> EditorProject {
        var p = EditorProject()
        p.tracks = [Track(type: .main, clips: [clipOf(10.0)])]
        if !pipClips.isEmpty {
            p.tracks.append(Track(type: .picture, clips: pipClips))
        }
        p.subtitles = subtitles
        return p
    }

    private func clipOf(_ duration: Double, start: Double = 0.0) -> Clip {
        var c = Clip(mediaPath: "/tmp/x.mp4", mediaName: "x",
                     mediaDuration: duration, width: 100, height: 100, hasAudio: false)
        c.trimEnd = duration
        c.timelineStart = start
        return c
    }

    private func subtitle(_ text: String, _ s: Double, _ e: Double) -> Subtitle {
        Subtitle(text: text, startTime: s, endTime: e)
    }

    func test锚点之后的字幕整体前移() {
        let p = project([
            subtitle("前", 1.0, 2.0),
            subtitle("中", 4.5, 5.5),
            subtitle("后", 7.0, 9.0),
        ], [])
        // 删除 [3, 6]，主轨缩短 3s，锚点 = 3
        let out = shiftOverlaysAfter(project: p, anchor: 3.0, delta: -3.0)

        XCTAssertEqual(out.subtitles[0].startTime, 1.0, accuracy: 1e-9) // 锚点前不动
        XCTAssertEqual(out.subtitles[1].startTime, 1.5, accuracy: 1e-9) // 4.5-3
        XCTAssertEqual(out.subtitles[1].endTime, 2.5, accuracy: 1e-9)
        XCTAssertEqual(out.subtitles[2].startTime, 4.0, accuracy: 1e-9)
    }

    func test锚点之后的画中画片段前移且不小于0() {
        let p = project([], [clipOf(3.0, start: 1.0), clipOf(3.0, start: 5.0)])
        // 删除 [0, 4]：锚点 0 之后全部前移 4s；起点 1.0 的贴到 0
        let out = shiftOverlaysAfter(project: p, anchor: 0.0, delta: -4.0)

        let pipTrack = out.tracks.first { $0.type == .picture }!
        XCTAssertEqual(pipTrack.clips[0].timelineStart, 0.0, accuracy: 1e-9)
        XCTAssertEqual(pipTrack.clips[1].timelineStart, 1.0, accuracy: 1e-9)
    }

    func test主轨自身不平移() {
        var p = EditorProject()
        p.tracks = [Track(type: .main, clips: [clipOf(10.0)])]
        let out = shiftOverlaysAfter(project: p, anchor: 2.0, delta: -1.0)
        XCTAssertEqual(out.tracks[0].clips[0].timelineStart, 0.0, accuracy: 1e-9)
    }

    func testDelta为0时原样返回() {
        let p = project([subtitle("a", 3.0, 4.0)], [])
        let out = shiftOverlaysAfter(project: p, anchor: 2.0, delta: 0.0)
        XCTAssertEqual(out, p)
    }

    func test跨越锚点的元素保持绝对时间() {
        let p = project([subtitle("跨", 2.0, 8.0)], [])
        let out = shiftOverlaysAfter(project: p, anchor: 4.0, delta: -2.0)
        // 起点 2.0 < 锚点 4.0 → 不动（v1 规则）
        XCTAssertEqual(out.subtitles[0].startTime, 2.0, accuracy: 1e-9)
        XCTAssertEqual(out.subtitles[0].endTime, 8.0, accuracy: 1e-9)
    }
}
