//
//  DraftSerializationTests.swift
//  VideoMergerTests
//
//  移植自 Android DraftSerializationTest.kt：草稿 JSON 序列化往返自测，
//  防止字段遗漏导致恢复丢数据。
//

import XCTest
@testable import VideoMerger

final class DraftSerializationTests: XCTestCase {

    private func sampleProject() -> EditorProject {
        let keyframes = [
            PipKeyframe(time: 0.5, x: 0.1, y: 0.2),
            PipKeyframe(time: 1.5, x: 0.4, y: 0.6),
        ]
        var main = Clip(mediaPath: "/data/editor_media/input_1.mp4", mediaName: "input_1.mp4",
                        mediaDuration: 12.0, width: 1088, height: 1920, hasAudio: true)
        main.trimStart = 1.0
        main.trimEnd = 10.0
        main.speed = 1.5
        main.reversed = true
        main.volume = 0.8
        main.audioFadeIn = 0.3
        main.pitchShift = 1.2
        main.noiseReduction = true
        main.rotation = 90
        main.hflip = true
        main.filterPreset = .vintage
        main.brightness = 0.1
        main.textOverlay = "hello"
        main.textSize = 40
        main.textColor = "#FF0000"
        main.transition = .fade
        main.transitionDuration = 1.0
        main.blurBgEnabled = true

        var pip = Clip(mediaPath: "/data/editor_media/sticker.png", mediaName: "😀",
                       mediaDuration: 300.0, width: 256, height: 256, hasAudio: false)
        pip.trimEnd = 3.0
        pip.timelineStart = 2.0
        pip.pipEnabled = true
        pip.isImage = true
        pip.pipX = 0.2
        pip.pipY = 0.3
        pip.pipWidth = 0.25
        pip.pipOpacity = 0.7
        pip.pipShape = .circle
        pip.pipKeyframes = keyframes

        var p = EditorProject()
        p.name = "项目 测试"
        p.canvasWidth = 1088
        p.canvasHeight = 1920
        p.fps = 30
        p.tracks = [
            Track(type: .main, clips: [relayoutMainTrackClips([main]).first!]),
            Track(type: .picture, clips: [pip], muted: true),
        ]
        p.subtitles = [
            Subtitle(text: "字幕一", startTime: 1.0, endTime: 2.5),
            Subtitle(text: "字幕二", startTime: 3.0, endTime: 4.0),
        ]
        return p
    }

    private func decode(_ json: String) -> EditorProject? {
        try? JSONDecoder().decode(EditorProject.self, from: Data(json.utf8))
    }

    func test序列化往返无损() {
        let original = sampleProject()
        let data = try! JSONEncoder().encode(original)
        guard let restored = try? JSONDecoder().decode(EditorProject.self, from: data) else {
            XCTFail("解码失败")
            return
        }

        XCTAssertEqual(original.name, restored.name)
        XCTAssertEqual(original.canvasWidth, restored.canvasWidth)
        XCTAssertEqual(original.tracks.count, restored.tracks.count)
        XCTAssertEqual(original.subtitles, restored.subtitles)

        let origMain = original.mainTrack!.clips[0]
        let newMain = restored.mainTrack!.clips[0]
        XCTAssertEqual(origMain.id, newMain.id)
        XCTAssertEqual(origMain.trimStart, newMain.trimStart, accuracy: 1e-9)
        XCTAssertEqual(origMain.trimEnd, newMain.trimEnd, accuracy: 1e-9)
        XCTAssertEqual(origMain.speed, newMain.speed, accuracy: 1e-9)
        XCTAssertEqual(origMain.reversed, newMain.reversed)
        XCTAssertEqual(origMain.filterPreset, newMain.filterPreset)
        XCTAssertEqual(origMain.transition, newMain.transition)
        XCTAssertEqual(origMain.timelineStart, newMain.timelineStart, accuracy: 1e-9)

        let origPip = original.tracks[1].clips[0]
        let newPip = restored.tracks[1].clips[0]
        XCTAssertEqual(origPip.pipShape, newPip.pipShape)
        XCTAssertEqual(origPip.pipKeyframes, newPip.pipKeyframes)
        XCTAssertEqual(origPip.isImage, newPip.isImage)
    }

    func test损坏的JSON返回nil不抛异常() {
        XCTAssertNil(decode("not a json {"))
        XCTAssertNil(decode(""))
    }

    func test空项目可往返() {
        let empty = EditorProject()
        let data = try! JSONEncoder().encode(empty)
        guard let restored = try? JSONDecoder().decode(EditorProject.self, from: data) else {
            XCTFail("解码失败")
            return
        }
        XCTAssertEqual(empty.name, restored.name)
    }
}
