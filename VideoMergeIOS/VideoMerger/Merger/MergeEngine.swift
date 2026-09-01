//
//  MergeEngine.swift
//  VideoMerger
//
//  iOS 移植自 Android VideoMerger 的 MergeEngine.kt
//

import Foundation
import UIKit
import Photos

/// 合并引擎 —— 协调整个流程：
/// 1. 将选中的视频 URL 复制到临时文件
/// 2. 用 FFprobe 提取每个视频的元数据
/// 3. 检测尾部 logo（抖音结尾）
/// 4. 根据合并类型构建 ffmpeg 命令
/// 5. 异步执行 ffmpeg 合并
/// 6. 生成缩略图
/// 7. 返回结果，由 UI 层决定是否保存
final class MergeEngine {

    private var currentRunner: FFmpegRunner?

    /// 执行合并流程，返回结果文件（不保存到相册）
    func merge(videoURLs: [URL],
               mergeType: MergeType,
               options: MergeOptions,
               onProgress: @escaping (Float) -> Void,
               onLog: @escaping (String) -> Void,
               completion: @escaping (Result<MergeResult, Error>) -> Void) {

        if videoURLs.isEmpty {
            completion(.failure(NSError(domain: "MergeEngine", code: 1,
                                         userInfo: [NSLocalizedDescriptionKey: "未选择视频"])))
            return
        }

        DispatchQueue.global(qos: .userInitiated).async {
            do {
                // 1. 复制到临时文件
                onProgress(0.0)
                onLog("开始复制 \(videoURLs.count) 个视频到临时文件...\n")

                var tempFiles: [URL] = []
                for (i, url) in videoURLs.enumerated() {
                    let dest = try MediaUtils.copyToTempFile(url: url, index: i)
                    tempFiles.append(dest)
                }
                let inputPaths = tempFiles.map { $0.path }

                // 2. 探测元数据
                onLog("探测视频元数据...\n")
                var metas: [VideoMeta] = []
                for path in inputPaths {
                    guard let m = MediaUtils.getVideoMeta(path: path) else {
                        throw NSError(domain: "MergeEngine", code: 2,
                                      userInfo: [NSLocalizedDescriptionKey: "无法读取视频元数据: \(path)"])
                    }
                    metas.append(m)
                }
                let durations = metas.map { $0.duration }

                for (i, meta) in metas.enumerated() {
                    onLog("  [\(i)] \(tempFiles[i].lastPathComponent)  \(meta.width)x\(meta.height)  \(String(format: "%.2f", meta.duration))s  audio=\(meta.hasAudio)\n")
                }

                // 2.5 检测尾部 logo
                onLog("检测尾部 logo（静止片段）...\n")
                var cutTimes: [Double?] = []
                for (i, path) in inputPaths.enumerated() {
                    let cut = MediaUtils.detectLogoCut(path: path)
                    if let cut = cut {
                        onLog("  [\(i)] \(tempFiles[i].lastPathComponent)  \(String(format: "%.2f", durations[i]))s → 截断至 \(String(format: "%.2f", cut))s（去除尾部 \(String(format: "%.2f", durations[i] - cut))s logo）\n")
                    } else {
                        onLog("  [\(i)] \(tempFiles[i].lastPathComponent)  \(String(format: "%.2f", durations[i]))s → 未检测到尾部 logo\n")
                    }
                    cutTimes.append(cut)
                }
                let effDurations = cutTimes.enumerated().map { (i, cut) in
                    (cut != nil && cut! > 0) ? cut! : durations[i]
                }

                // 3. 构建命令
                let outputFile = MediaUtils.outputFileURL
                try? FileManager.default.removeItem(at: outputFile)

                let command: String
                switch mergeType {
                case .grid:
                    command = GridMerger().buildCommand(
                        inputPaths: inputPaths, durations: effDurations, metas: metas,
                        outputPath: outputFile.path, options: options, cutTimes: cutTimes
                    )
                case .collage:
                    command = CollageMerger().buildCommand(
                        inputPaths: inputPaths, durations: effDurations, metas: metas,
                        outputPath: outputFile.path, options: options, cutTimes: cutTimes
                    )
                case .photoWall:
                    command = PhotoWallMerger().buildCommand(
                        inputPaths: inputPaths, durations: effDurations, metas: metas,
                        outputPath: outputFile.path, options: options, cutTimes: cutTimes
                    )
                }

                onLog("ffmpeg 命令:\n\(command)\n\n")

                // 4. 执行 ffmpeg
                let maxDur = effDurations.max() ?? 0.0
                let success = self.executeFFmpeg(command: command,
                                                 totalDuration: maxDur,
                                                 onProgress: onProgress,
                                                 onLog: onLog)

                if !success {
                    DispatchQueue.main.async {
                        completion(.failure(NSError(domain: "MergeEngine", code: 3,
                                                    userInfo: [NSLocalizedDescriptionKey: "FFmpeg 合并失败"])))
                    }
                    MediaUtils.cleanupInputFiles()
                    return
                }

                guard FileManager.default.fileExists(atPath: outputFile.path) else {
                    DispatchQueue.main.async {
                        completion(.failure(NSError(domain: "MergeEngine", code: 4,
                                                    userInfo: [NSLocalizedDescriptionKey: "输出文件不存在或为空"])))
                    }
                    MediaUtils.cleanupInputFiles()
                    return
                }

                // 5. 生成缩略图
                onProgress(0.98)
                let thumbFile = MediaUtils.thumbnailFileURL
                if let thumb = MediaUtils.loadThumbnailFromFile(path: outputFile.path) {
                    MediaUtils.saveImageAsJpeg(thumb, to: thumbFile)
                }

                // 6. 获取输出视频元数据
                guard let outMeta = MediaUtils.getVideoMeta(path: outputFile.path) else {
                    DispatchQueue.main.async {
                        completion(.failure(NSError(domain: "MergeEngine", code: 5,
                                                    userInfo: [NSLocalizedDescriptionKey: "无法读取输出文件元数据"])))
                    }
                    MediaUtils.cleanupInputFiles()
                    return
                }

                onLog("合并完成！输出: \(outputFile.path)\n")
                onLog("  尺寸: \(outMeta.width)x\(outMeta.height)  时长: \(String(format: "%.2f", outMeta.duration))s\n")

                // 清理临时输入文件
                MediaUtils.cleanupInputFiles()

                let result = MergeResult(
                    outputFileURL: outputFile,
                    thumbnailFileURL: FileManager.default.fileExists(atPath: thumbFile.path) ? thumbFile : nil,
                    mergeType: mergeType.rawValue,
                    duration: outMeta.duration,
                    width: outMeta.width,
                    height: outMeta.height
                )

                DispatchQueue.main.async {
                    completion(.success(result))
                }
            } catch {
                MediaUtils.cleanupInputFiles()
                DispatchQueue.main.async {
                    completion(.failure(error))
                }
            }
        }
    }

    /// 将合并结果保存到系统相册 + 本地历史
    func saveResult(_ result: MergeResult,
                    completion: @escaping (Result<Void, Error>) -> Void) {
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                let timestamp = Int(Date().timeIntervalSince1970 * 1000)
                let displayName = "merged_\(result.mergeType.lowercased())_\(timestamp).mp4"

                // 保存到相册
                try MediaUtils.saveToGallery(fileURL: result.outputFileURL, displayName: displayName)

                // 保存到本地历史
                VideoHistoryStore.addToHistory(
                    videoURL: result.outputFileURL,
                    thumbnailURL: result.thumbnailFileURL,
                    mergeType: result.mergeType,
                    duration: result.duration,
                    width: result.width,
                    height: result.height
                )

                DispatchQueue.main.async {
                    completion(.success(()))
                }
            } catch {
                DispatchQueue.main.async {
                    completion(.failure(error))
                }
            }
        }
    }

    /// 把已存在的视频文件（例如历史记录里的成片）重新保存到系统相册。
    /// 对应 Android HistoryScreen 直接调 MediaUtils.saveToGallery 的入口。
    /// 与 saveResult 的区别：不依赖 MergeResult，也不写入历史（它本来就在历史里）。
    func saveExistingVideoToGallery(url: URL,
                                    completion: @escaping (Result<Void, Error>) -> Void) {
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                let timestamp = Int(Date().timeIntervalSince1970 * 1000)
                let displayName = "影剪_\(timestamp).mp4"
                try MediaUtils.saveToGallery(fileURL: url, displayName: displayName)
                DispatchQueue.main.async {
                    completion(.success(()))
                }
            } catch {
                DispatchQueue.main.async {
                    completion(.failure(error))
                }
            }
        }
    }

    /// 加载本地历史记录
    func loadHistory() -> [HistoryEntry] {
        VideoHistoryStore.loadHistory()
    }

    /// 删除历史记录
    func deleteHistoryEntry(entry: HistoryEntry) {
        VideoHistoryStore.deleteEntry(entry: entry)
    }

    /// 取消正在进行的合并
    func cancel() {
        currentRunner?.cancel()
    }

    // MARK: - 内部

    /// 同步执行 ffmpeg，挂起等待结果（在后台线程调用）
    private func executeFFmpeg(command: String,
                               totalDuration: Double,
                               onProgress: @escaping (Float) -> Void,
                               onLog: @escaping (String) -> Void) -> Bool {
        let semaphore = DispatchSemaphore(value: 0)
        var success = false

        let runner = FFmpegRunner(
            command: command,
            totalDuration: totalDuration,
            onProgress: { progress in
                DispatchQueue.main.async { onProgress(progress) }
            },
            onLog: { line in
                DispatchQueue.main.async { onLog(line) }
            },
            onComplete: { result in
                onLog("\n\(result.message)\n")
                success = result.success
                semaphore.signal()
            }
        )
        self.currentRunner = runner
        runner.execute()

        // 等待 ffmpeg 结束（FFmpegKit 的回调默认在内部线程触发）
        semaphore.wait()
        self.currentRunner = nil
        return success
    }
}
