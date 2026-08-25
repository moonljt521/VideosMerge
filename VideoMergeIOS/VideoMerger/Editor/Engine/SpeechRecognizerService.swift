//
//  SpeechRecognizerService.swift
//  VideoMerger
//
//  iOS 移植自 Android VoskSpeechRecognizer.kt
//  用 iOS 原生 Speech 框架替代 Vosk：
//  ffmpeg 提取音频 → SFSpeechURLRecognitionRequest 识别 → 按词时间戳聚合字幕
//

import Foundation
import Speech
import AVFoundation
import ffmpegkit

enum SpeechRecognizerService {

    struct WordTiming {
        let text: String
        let start: Double
        let end: Double
    }

    /// 语音转字幕主流程
    static func transcribe(videoPath: String,
                           onStatus: @escaping (String) -> Void) async throws -> [Subtitle] {
        onStatus("提取音频...")
        let wavURL = try extractAudio(videoPath: videoPath)

        onStatus("语音识别中（中文）...")
        let words = try await recognize(url: wavURL)

        onStatus("聚合字幕...")
        return groupToSubtitles(words)
    }

    /// ffmpeg 提取 16kHz 单声道 WAV（识别最稳）
    private static func extractAudio(videoPath: String) throws -> URL {
        let out = FileManager.default.temporaryDirectory
            .appendingPathComponent("speech_\(Int(Date().timeIntervalSince1970 * 1000)).wav")
        FFmpegKit.execute(
            "-y -v error -i \"\(videoPath)\" -vn -acodec pcm_s16le -ar 16000 -ac 1 \"\(out.path)\""
        )
        guard FileManager.default.fileExists(atPath: out.path) else {
            throw RuntimeError("音频提取失败")
        }
        return out
    }

    private static func recognize(url: URL) async throws -> [WordTiming] {
        let granted = await withCheckedContinuation { (cont: CheckedContinuation<Bool, Never>) in
            SFSpeechRecognizer.requestAuthorization { status in
                cont.resume(returning: status == .authorized)
            }
        }
        guard granted else { throw RuntimeError("语音识别权限未授权") }

        guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "zh-CN")),
              recognizer.isAvailable else {
            throw RuntimeError("中文语音识别不可用（设备不支持）")
        }

        let request = SFSpeechURLRecognitionRequest(url: url)
        request.shouldReportPartialResults = true

        return try await withCheckedThrowingContinuation { cont in
            var finished = false
            var best: [WordTiming] = []
            let task = recognizer.recognitionTask(with: request) { result, error in
                if let result = result {
                    // 保留最新一版的词时间戳（每次回调都是全量前缀）
                    best = Self.words(from: result)
                    if result.isFinal {
                        finished = true
                        cont.resume(returning: best)
                    }
                }
                if let error = error, !finished {
                    finished = true
                    cont.resume(throwing: error)
                }
            }
            _ = task
        }
    }

    /// 从识别结果提取词时间戳
    private static func words(from result: SFSpeechRecognitionResult) -> [WordTiming] {
        result.bestTranscription.segments.map { seg in
            WordTiming(text: seg.substring, start: seg.timestamp,
                       end: seg.timestamp + seg.duration)
        }
    }

    /// 按词时间戳聚合字幕：每条最多 10 字、最长 4 秒，句读优先断句
    static func groupToSubtitles(_ words: [WordTiming]) -> [Subtitle] {
        guard !words.isEmpty else { return [] }
        var subs: [Subtitle] = []
        var curText = String()
        var curStart = words[0].start
        var curEnd = words[0].end

        func flush() {
            let trimmed = curText.trimmingCharacters(in: .whitespaces)
            if !trimmed.isEmpty && curEnd > curStart {
                subs.append(Subtitle(text: trimmed, startTime: curStart, endTime: curEnd))
            }
            curText = String()
        }

        for w in words {
            curText += w.text
            curEnd = w.end
            // 句读断句
            if let last = w.text.last, "。，！？,.!?".contains(last) {
                flush()
                curStart = w.end
                continue
            }
            // 长度/时长上限断句
            if curText.count >= 10 || (curEnd - curStart) >= 4.0 {
                flush()
                curStart = w.end
            }
        }
        flush()
        return subs
    }
}
