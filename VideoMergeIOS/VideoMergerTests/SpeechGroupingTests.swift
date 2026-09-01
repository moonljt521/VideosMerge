//
//  SpeechGroupingTests.swift
//  VideoMergerTests
//
//  字幕聚合（groupToSubtitles）断句规则自测，规则对齐 Android VoskSpeechRecognizer.groupToSubtitles。
//

import XCTest
@testable import VideoMerger

final class SpeechGroupingTests: XCTestCase {

    private func word(_ text: String, _ start: Double, _ end: Double) -> SpeechRecognizerService.WordTiming {
        SpeechRecognizerService.WordTiming(text: text, start: start, end: end)
    }

    func test空输入返回空() {
        XCTAssertTrue(SpeechRecognizerService.groupToSubtitles([]).isEmpty)
    }

    func test句读优先断句() {
        // 每个词带句读 → 每词一条字幕
        let subs = SpeechRecognizerService.groupToSubtitles([
            word("你好。", 0.0, 1.0),
            word("再见。", 1.2, 2.0),
        ])
        XCTAssertEqual(subs.count, 2)
        XCTAssertEqual(subs[0].text, "你好。")
        XCTAssertEqual(subs[0].startTime, 0.0, accuracy: 1e-9)
        XCTAssertEqual(subs[1].text, "再见。")
        XCTAssertEqual(subs[1].startTime, 1.2, accuracy: 1e-9)
    }

    func test累计超十字断句() {
        // 单个长词流：累计字符数超过 10 → 断句
        let subs = SpeechRecognizerService.groupToSubtitles([
            word("一二三四五六", 0.0, 1.0),
            word("七八九十甲乙丙丁", 1.0, 2.0),
        ])
        // 6 + 8 = 14 > 10 → 第二个词前断句（buf 非空）
        XCTAssertEqual(subs.count, 2)
        XCTAssertEqual(subs[0].text, "一二三四五六")
        XCTAssertEqual(subs[1].text, "七八九十甲乙丙丁")
    }

    func test停顿超零点八秒断句() {
        let subs = SpeechRecognizerService.groupToSubtitles([
            word("前半句", 0.0, 1.0),
            word("后半句", 2.0, 3.0),   // 与上一词间隔 1.0s > 0.8s
        ])
        XCTAssertEqual(subs.count, 2)
        XCTAssertEqual(subs[0].text, "前半句")
        XCTAssertEqual(subs[0].endTime, 1.0, accuracy: 1e-9)
        XCTAssertEqual(subs[1].startTime, 2.0, accuracy: 1e-9)
    }

    func test正常语速连续词合并为一条() {
        let subs = SpeechRecognizerService.groupToSubtitles([
            word("这", 0.0, 0.2),
            word("是", 0.2, 0.4),
            word("一", 0.4, 0.6),
            word("句", 0.6, 0.8),
        ])
        XCTAssertEqual(subs.count, 1)
        XCTAssertEqual(subs[0].text, "这是一句")
        XCTAssertEqual(subs[0].startTime, 0.0, accuracy: 1e-9)
        XCTAssertEqual(subs[0].endTime, 0.8, accuracy: 1e-9)
    }

    func test结尾残句被flush() {
        // 最后没有句读/停顿，也要 flush 出最后一条
        let subs = SpeechRecognizerService.groupToSubtitles([
            word("完", 0.0, 0.3),
        ])
        XCTAssertEqual(subs.count, 1)
        XCTAssertEqual(subs[0].text, "完")
    }
}
