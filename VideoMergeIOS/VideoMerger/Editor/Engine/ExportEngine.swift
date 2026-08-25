//
//  ExportEngine.swift
//  VideoMerger
//
//  iOS 移植自 Android ExportEngine.kt
//  导出引擎：构建命令 → FFmpegKit 执行 → 保存相册 → 记录历史
//

import Foundation
import UIKit
import Photos
import ffmpegkit

final class ExportEngine {

    private let filterBuilder = FilterBuilder()
    private var runner: FFmpegRunner?

    func cancel() {
        runner?.cancel()
        runner = nil
    }

    /// 导出项目为视频文件
    func export(project: EditorProject,
                onProgress: @escaping (Float) -> Void,
                onLog: @escaping (String) -> Void) async -> Result<URL, Error> {
        await withCheckedContinuation { cont in
            DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                guard let self = self else {
                    cont.resume(returning: .failure(RuntimeError("引擎已释放")))
                    return
                }
                guard let mainTrack = project.mainTrack, !mainTrack.clips.isEmpty else {
                    cont.resume(returning: .failure(RuntimeError("没有视频片段")))
                    return
                }
                let output = FileManager.default.temporaryDirectory
                    .appendingPathComponent("editor_export_\(Int(Date().timeIntervalSince1970 * 1000)).mp4")
                try? FileManager.default.removeItem(at: output)

                let command = self.filterBuilder.buildExportCommand(project, output.path)
                guard !command.isEmpty else {
                    cont.resume(returning: .failure(RuntimeError("命令构建失败")))
                    return
                }
                onLog("ffmpeg 命令:\n\(command)\n")

                let totalDuration = self.filterBuilder.computeOutputDuration(project)
                let runner = FFmpegRunner(
                    command: command,
                    totalDuration: totalDuration,
                    onProgress: onProgress,
                    onLog: onLog,
                    onComplete: { result in
                        if result.success && FileManager.default.fileExists(atPath: output.path) {
                            cont.resume(returning: .success(output))
                        } else {
                            cont.resume(returning: .failure(RuntimeError("导出失败: \(result.message)")))
                        }
                    }
                )
                self.runner = runner
                runner.execute()
            }
        }
    }

    /// 保存到相册
    func saveToGallery(file: URL, projectName: String) async -> Result<URL, Error> {
        await withCheckedContinuation { cont in
            PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
                guard status == .authorized || status == .limited else {
                    cont.resume(returning: .failure(RuntimeError("没有相册权限")))
                    return
                }
                PHPhotoLibrary.shared().performChanges {
                    let request = PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: file)!
                    request.creationDate = Date()
                } completionHandler: { ok, error in
                    if ok {
                        cont.resume(returning: .success(file))
                    } else {
                        cont.resume(returning: .failure(error ?? RuntimeError("保存相册失败")))
                    }
                }
            }
        }
    }

    /// 记录到历史（复用合并模块的历史存储）
    func recordToHistory(file: URL, project: EditorProject) {
        var thumbURL: URL?
        if let thumb = MediaUtils.loadThumbnailFromFile(path: file.path) {
            thumbURL = FileManager.default.temporaryDirectory
                .appendingPathComponent("editor_history_thumb_\(Int(Date().timeIntervalSince1970 * 1000)).jpg")
            _ = MediaUtils.saveImageAsJpeg(thumb, to: thumbURL!)
        }
        _ = VideoHistoryStore.addToHistory(
            videoURL: file,
            thumbnailURL: thumbURL,
            mergeType: "剪辑导出",
            duration: filterBuilder.computeOutputDuration(project),
            width: project.canvasWidth,
            height: project.canvasHeight
        )
    }
}

/// 简单错误类型
struct RuntimeError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
    init(_ message: String) { self.message = message }
}
