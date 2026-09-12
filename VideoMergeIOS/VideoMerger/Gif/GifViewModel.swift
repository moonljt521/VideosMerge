//
//  GifViewModel.swift
//  VideoMerger
//
//  iOS 移植自 Android GifViewModel.kt —— 视频转 GIF 页状态
//

import Foundation
import Combine
import ffmpegkit

/// 视频转 GIF 的 UI 状态
struct GifUiState {
    var sourceURL: URL?
    var sourceName = ""
    var sourceDuration: Double = 0        // 秒
    var sourceWidth = 0
    var sourceHeight = 0
    var targetWidth = 480                 // GIF 目标宽度（等比缩放）
    var fps = 10
    var quality: GifQuality = .normal
    var loop = true
    var isConverting = false
    var progress: Double = 0              // 0~1
    var resultURL: URL?
    var isSaving = false
    var isSaved = false
    var errorMessage: String?
}

@MainActor
final class GifViewModel: ObservableObject {

    @Published var uiState = GifUiState()

    private var runner: FFmpegRunner?

    func updateTargetWidth(_ width: Int) { uiState.targetWidth = width }
    func updateFps(_ fps: Int) { uiState.fps = fps }
    func updateQuality(_ quality: GifQuality) { uiState.quality = quality }
    func updateLoop(_ loop: Bool) { uiState.loop = loop }

    /// 选择视频后：探测宽高/时长（URL 已由 MediaUtils.loadVideoURLs 落到本地临时目录）
    func onVideoPicked(url: URL) {
        guard !uiState.isConverting else { return }
        Task { [weak self] in
            guard let self else { return }
            guard let meta = MediaUtils.getVideoMeta(path: url.path),
                  meta.duration > 0, meta.width > 0 else {
                self.uiState.errorMessage = "无法读取视频信息，请换一个视频试试"
                return
            }
            // 换新源时清掉上一轮的源文件与结果
            let old = self.uiState
            guard !old.isConverting else { return }
            if let oldURL = old.sourceURL { try? FileManager.default.removeItem(at: oldURL) }
            if let oldResult = old.resultURL { try? FileManager.default.removeItem(at: oldResult) }
            self.uiState = GifUiState(
                sourceURL: url,
                sourceName: url.lastPathComponent,
                sourceDuration: meta.duration,
                sourceWidth: meta.width,
                sourceHeight: meta.height
            )
        }
    }

    /// 开始转换（palettegen/paletteuse 两步法，见 GifCommandBuilder）
    func convert() {
        guard let src = uiState.sourceURL, !uiState.isConverting else { return }

        let out = FileManager.default.temporaryDirectory
            .appendingPathComponent("gif_out_\(Int(Date().timeIntervalSince1970 * 1000)).gif")
        let dims = GifCommandBuilder.targetDimensions(
            sourceWidth: uiState.sourceWidth,
            sourceHeight: uiState.sourceHeight,
            targetWidth: uiState.targetWidth
        )
        let command = GifCommandBuilder.build(
            inputPath: src.path,
            outputPath: out.path,
            fps: uiState.fps,
            width: dims.width,
            height: dims.height,
            quality: uiState.quality,
            loop: uiState.loop
        )
        uiState.isConverting = true
        uiState.progress = 0
        uiState.resultURL = nil
        uiState.isSaved = false
        uiState.errorMessage = nil

        let duration = uiState.sourceDuration
        // 进度回调在 FFmpegKit 的代理线程，跳回主线程刷新
        // （闭包先赋给显式类型变量，避免整条 FFmpegRunner(...) 表达式类型检查超时）
        let onProgress: (Float) -> Void = { [weak self] p in
            Task { @MainActor [weak self] in
                guard let self, self.uiState.isConverting else { return }
                // 按整数百分比过滤，减少高频回调触发的刷新
                let newPct = Int(Double(p) * 100)
                let oldPct = Int(self.uiState.progress * 100)
                if newPct != oldPct {
                    self.uiState.progress = Double(newPct) / 100
                }
            }
        }
        let onComplete: (FFmpegResult) -> Void = { [weak self] result in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.handleConvertComplete(result: result, output: out)
            }
        }
        let newRunner = FFmpegRunner(
            command: command,
            totalDuration: duration,
            onProgress: onProgress,
            onLog: { _ in },
            onComplete: onComplete
        )
        runner = newRunner
        newRunner.execute()
    }

    /// 转换完成（主线程）：成功记录结果文件，失败清理并提示（取消不算错误）
    private func handleConvertComplete(result: FFmpegResult, output: URL) {
        runner = nil
        uiState.isConverting = false
        if result.success, Self.fileSize(of: output) > 0 {
            uiState.progress = 1
            uiState.resultURL = output
            AppAnalytics.gifExportSuccess()
        } else {
            try? FileManager.default.removeItem(at: output)
            uiState.progress = 0
            if result.message != "已取消" {
                uiState.errorMessage = "转换失败 (code=\(result.returnCode))"
            }
        }
    }

    private static func fileSize(of url: URL) -> Int64 {
        let attrs = try? FileManager.default.attributesOfItem(atPath: url.path)
        return (attrs?[.size] as? Int64) ?? 0
    }

    func cancelConvert() {
        runner?.cancel()
    }

    /// 清除结果，回到参数页（缓存中的 GIF 可直接删除，相册已有拷贝）
    func clearResult() {
        if let url = uiState.resultURL {
            try? FileManager.default.removeItem(at: url)
        }
        uiState.resultURL = nil
        uiState.isSaved = false
        uiState.progress = 0
    }

    /// 保存到相册 Pictures/VideoMerger/ 对应位置（iOS: 相册图片）
    func saveResult() {
        guard let url = uiState.resultURL, !uiState.isSaving else { return }
        uiState.isSaving = true
        // MediaUtils.saveGifToGallery 内部用信号量阻塞等待,放到后台线程执行
        Task.detached(priority: .userInitiated) { [weak self] in
            do {
                let name = "gif_\(Int(Date().timeIntervalSince1970 * 1000)).gif"
                _ = try MediaUtils.saveGifToGallery(fileURL: url, displayName: name)
                await MainActor.run { [weak self] in
                    self?.uiState.isSaving = false
                    self?.uiState.isSaved = true
                }
            } catch {
                await MainActor.run { [weak self] in
                    self?.uiState.isSaving = false
                    self?.uiState.errorMessage = "保存到相册失败:\(error.localizedDescription)"
                }
            }
        }
    }

    func dismissError() {
        uiState.errorMessage = nil
    }
}
