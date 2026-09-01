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
        // ★ 校验 returnCode（对齐 Android：失败时带上日志尾部，便于定位）
        guard let session = FFmpegKit.execute(
            "-y -v error -i \"\(videoPath)\" -vn -acodec pcm_s16le -ar 16000 -ac 1 \"\(out.path)\""
        ), let rc = session.getReturnCode(), ReturnCode.isSuccess(rc) else {
            let logs = FFmpegKitConfig.getLastSession()?.getAllLogsAsString() ?? ""
            throw RuntimeError("音频提取失败：\(String(logs.suffix(500)))")
        }
        guard FileManager.default.fileExists(atPath: out.path) else {
            throw RuntimeError("音频提取失败：输出文件不存在")
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
                        // ★ 回退（对齐 Android）：有整段文本但无词级时间戳 → 生成一条覆盖全音频的词
                        if best.isEmpty {
                            let full = result.bestTranscription.formattedString
                                .replacingOccurrences(of: " ", with: "")
                            if !full.isEmpty {
                                best = [WordTiming(text: full, start: 0, end: Self.audioDuration(url: url))]
                            }
                        }
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

    /// WAV 时长（秒），用于无词级时间戳时的整段回退
    private static func audioDuration(url: URL) -> Double {
        if let file = try? AVAudioFile(forReading: url) {
            let sec = Double(file.length) / file.processingFormat.sampleRate
            if sec.isFinite && sec > 0 { return sec }
        }
        return 0
    }

    /// 从识别结果提取词时间戳
    private static func words(from result: SFSpeechRecognitionResult) -> [WordTiming] {
        result.bestTranscription.segments.map { seg in
            WordTiming(text: seg.substring, start: seg.timestamp,
                       end: seg.timestamp + seg.duration)
        }
    }

    /// 按词时间戳聚合字幕（断句规则对齐 Android VoskSpeechRecognizer）：
    /// 累计超 10 字或停顿 > 0.8s 先断句；Apple 识别自带句读，句读处优先断句。
    static func groupToSubtitles(_ words: [WordTiming]) -> [Subtitle] {
        guard !words.isEmpty else { return [] }
        var subs: [Subtitle] = []
        var buf = String()
        var segStart = words[0].start
        var segEnd = words[0].end

        func flush() {
            let trimmed = buf.trimmingCharacters(in: .whitespaces)
            if !trimmed.isEmpty && segEnd > segStart {
                subs.append(Subtitle(text: trimmed, startTime: segStart, endTime: segEnd))
            }
            buf = String()
        }

        for w in words {
            // ★ 停顿 > 0.8s 或累计超 10 字 → 断句（对齐 Android：buf 非空才断）
            if !buf.isEmpty && (buf.count + w.text.count > 10 || w.start - segEnd > 0.8) {
                flush()
            }
            // buf 为空时以当前词起点作为新字幕起点（首条 / 断句后 / 句读后统一处理）
            if buf.isEmpty { segStart = w.start }
            buf += w.text
            segEnd = w.end
            // 句读断句（Vosk 无标点故 Android 无此规则，Apple 有，保留为优先断句点）
            if let last = w.text.last, "。，！？,.!?".contains(last) {
                flush()
            }
        }
        flush()
        return subs
    }
}
