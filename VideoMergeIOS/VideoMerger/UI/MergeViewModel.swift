//
//  MergeViewModel.swift
//  VideoMerger
//
//  iOS 移植自 Android VideoMerger 的 MergeViewModel.kt
//

import Foundation
import SwiftUI
import Combine
import Photos

/// UI 状态
struct MergeUiState: Equatable {
    var selectedVideoURLs: [URL] = []
    var mergeType: MergeType = .grid
    var options: MergeOptions = MergeOptions()
    var isProcessing: Bool = false
    var progress: Float = 0
    var statusMessage: String = ""
    var logLines: String = ""
    var mergeResult: MergeResult? = nil
    var isSaved: Bool = false
    var isSaving: Bool = false
    var isFullscreen: Bool = false
    var fullscreenVideoURL: URL? = nil
    var errorMessage: String? = nil
    var history: [HistoryEntry] = []
    var showHistory: Bool = false
}

@MainActor
final class MergeViewModel: ObservableObject {

    @Published var uiState = MergeUiState()

    private let engine = MergeEngine()

    init() {
        uiState.history = engine.loadHistory()
    }

    // MARK: - 选择视频

    func onVideosSelected(_ urls: [URL]) {
        uiState.selectedVideoURLs = urls
        uiState.errorMessage = nil
        uiState.mergeResult = nil
        uiState.isSaved = false
        uiState.logLines = ""
    }

    // MARK: - 合并类型 & 选项

    func onMergeTypeSelected(_ type: MergeType) {
        uiState.mergeType = type
        // 照片墙是随机布局：首次进入时固定一个种子，预览与导出才会是同一版式
        if type == .photoWall && uiState.options.photoWallSeed == nil {
            uiState.options.photoWallSeed = newPhotoWallSeed()
        }
    }

    /// 照片墙「换一批」：换种子即换版式，预览与导出同步。
    func shufflePhotoWall() {
        uiState.options.photoWallSeed = newPhotoWallSeed()
    }

    private func newPhotoWallSeed() -> Int {
        Int.random(in: 1..<Int.max)
    }

    func updateOptions(_ options: MergeOptions) {
        uiState.options = options
    }

    // MARK: - 启动合并

    func startMerge() {
        if uiState.selectedVideoURLs.isEmpty {
            uiState.errorMessage = "请先选择视频"
            return
        }
        if uiState.selectedVideoURLs.count == 1 {
            uiState.errorMessage = "至少需要选择 2 个视频"
            return
        }

        uiState.isProcessing = true
        uiState.progress = 0
        uiState.statusMessage = "准备中..."
        uiState.errorMessage = nil
        uiState.mergeResult = nil
        uiState.isSaved = false
        uiState.logLines = ""

        let urls = uiState.selectedVideoURLs
        let mergeType = uiState.mergeType
        let options = uiState.options

        engine.merge(
            videoURLs: urls,
            mergeType: mergeType,
            options: options,
            onProgress: { [weak self] progress in
                let msg: String
                if progress < 0.1 { msg = "复制视频文件中..." }
                else if progress < 0.98 { msg = "合并中... \(Int(progress * 100))%" }
                else { msg = "正在完成..." }
                self?.uiState.progress = progress
                self?.uiState.statusMessage = msg
            },
            onLog: { [weak self] line in
                guard let self = self else { return }
                // 限制日志总长度（保留末尾 8KB），避免 Text 渲染过多内容卡 UI
                let combined = self.uiState.logLines + line
                let maxLen = 8 * 1024
                if combined.count > maxLen {
                    let start = combined.index(combined.endIndex, offsetBy: -maxLen)
                    self.uiState.logLines = "…(已截断)\n" + combined[start...]
                } else {
                    self.uiState.logLines = combined
                }
            },
            completion: { [weak self] result in
                switch result {
                case .success(let mergeResult):
                    self?.uiState.isProcessing = false
                    self?.uiState.progress = 1
                    self?.uiState.statusMessage = "合并完成！预览中"
                    self?.uiState.mergeResult = mergeResult
                case .failure(let err):
                    self?.uiState.isProcessing = false
                    self?.uiState.progress = 0
                    self?.uiState.statusMessage = ""
                    self?.uiState.errorMessage = err.localizedDescription
                }
            }
        )
    }

    // MARK: - 保存

    func saveResult() {
        guard let result = uiState.mergeResult else { return }
        uiState.isSaving = true
        engine.saveResult(result) { [weak self] res in
            switch res {
            case .success:
                self?.uiState.isSaving = false
                self?.uiState.isSaved = true
                self?.uiState.history = self?.engine.loadHistory() ?? []
            case .failure(let err):
                self?.uiState.isSaving = false
                self?.uiState.errorMessage = "保存失败: \(err.localizedDescription)"
            }
        }
    }

    /// 把任意视频（含历史记录里的成片）保存到相册。
    /// ★ 不能用 saveResult()：那条路径 guard mergeResult != nil，
    ///   从历史页点开视频时 mergeResult 为 nil，会静默无反应。
    func saveVideoToGallery(url: URL) {
        uiState.isSaving = true
        engine.saveExistingVideoToGallery(url: url) { [weak self] res in
            switch res {
            case .success:
                self?.uiState.isSaving = false
                self?.uiState.isSaved = true
                self?.uiState.statusMessage = "已保存到相册"
            case .failure(let err):
                self?.uiState.isSaving = false
                self?.uiState.errorMessage = "保存失败: \(err.localizedDescription)"
            }
        }
    }

    // MARK: - 全屏播放

    func openFullscreen() {
        guard let url = uiState.mergeResult?.outputFileURL else { return }
        uiState.isFullscreen = true
        uiState.fullscreenVideoURL = url
    }

    func openFullscreen(url: URL) {
        uiState.isFullscreen = true
        uiState.fullscreenVideoURL = url
    }

    func closeFullscreen() {
        uiState.isFullscreen = false
        uiState.fullscreenVideoURL = nil
    }

    // MARK: - 清除

    func clearResult() {
        MediaUtils.cleanupOutputFiles()
        uiState.mergeResult = nil
        uiState.isSaved = false
        uiState.logLines = ""
    }

    func clearAll() {
        MediaUtils.cleanupOutputFiles()
        uiState.selectedVideoURLs = []
        uiState.isProcessing = false
        uiState.progress = 0
        uiState.statusMessage = ""
        uiState.logLines = ""
        uiState.mergeResult = nil
        uiState.isSaved = false
        uiState.isSaving = false
        uiState.errorMessage = nil
    }

    // MARK: - 历史

    func showHistoryPage() {
        uiState.history = engine.loadHistory()
        uiState.showHistory = true
    }

    func hideHistoryPage() {
        uiState.showHistory = false
    }

    func dismissError() {
        uiState.errorMessage = nil
    }

    func deleteHistoryEntry(_ entry: HistoryEntry) {
        engine.deleteHistoryEntry(entry: entry)
        uiState.history = engine.loadHistory()
    }
}
