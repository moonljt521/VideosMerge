//
//  FFmpegRunner.swift
//  VideoMerger
//
//  FFmpegKit 异步封装 —— 提供进度与日志回调。
//  iOS 移植自 Android VideoMerger 的 FFmpegRunner.kt
//

import Foundation
import ffmpegkit

/// FFmpeg 执行结果
struct FFmpegResult {
    let success: Bool
    let message: String
    let returnCode: Int
}

/// FFmpeg 异步运行器
final class FFmpegRunner {

    private let command: String
    private let totalDuration: Double
    private let onProgress: (Float) -> Void
    private let onLog: (String) -> Void
    private let onComplete: (FFmpegResult) -> Void

    /// 当前会话（用于 cancel）
    private var session: FFmpegSession?

    init(command: String,
         totalDuration: Double,
         onProgress: @escaping (Float) -> Void,
         onLog: @escaping (String) -> Void,
         onComplete: @escaping (FFmpegResult) -> Void) {
        self.command = command
        self.totalDuration = totalDuration
        self.onProgress = onProgress
        self.onLog = onLog
        self.onComplete = onComplete
    }

    /// 启动异步执行
    func execute() {
        // 用 block 变量传递闭包，避免 Swift 闭包类型与 ObjC block 不匹配
        let completeBlock: FFmpegSessionCompleteCallback = { [weak self] session in
            self?.handleComplete(session)
        }
        let logBlock: LogCallback = { [weak self] log in
            guard let self = self, let log = log else { return }
            if let msg = log.getMessage() {
                self.onLog(msg + "\n")
            }
        }
        let statsBlock: StatisticsCallback = { [weak self] stats in
            self?.handleStatistics(stats)
        }

        session = FFmpegKit.executeAsync(
            command,
            withCompleteCallback: completeBlock,
            withLogCallback: logBlock,
            withStatisticsCallback: statsBlock
        )
    }

    /// 取消执行
    func cancel() {
        FFmpegKit.cancel()
    }

    // MARK: - 内部

    private func handleComplete(_ session: FFmpegSession?) {
        guard let session = session else {
            onComplete(FFmpegResult(success: false, message: "会话为空", returnCode: -1))
            return
        }
        guard let rc = session.getReturnCode() else {
            onComplete(FFmpegResult(success: false, message: "返回码为空", returnCode: -1))
            return
        }

        if ReturnCode.isSuccess(rc) {
            onComplete(FFmpegResult(success: true, message: "合并完成", returnCode: Int(rc.getValue())))
        } else if ReturnCode.isCancel(rc) {
            onComplete(FFmpegResult(success: false, message: "已取消", returnCode: Int(rc.getValue())))
        } else {
            let logs = session.getAllLogsAsString() ?? ""
            print("FFmpeg 失败: returnCode=\(rc.getValue())\n\(logs)")
            onComplete(FFmpegResult(
                success: false,
                message: "合并失败 (code=\(rc.getValue()))",
                returnCode: Int(rc.getValue())
            ))
        }
    }

    private func handleStatistics(_ stats: Statistics?) {
        guard let stats = stats, totalDuration > 0 else { return }
        let currentSec = stats.getTime()
        let progress = Float(max(0, min(1, currentSec / totalDuration)))
        onProgress(progress)
    }
}
