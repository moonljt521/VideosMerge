//
//  DouyinViewModel.swift
//  VideoMerger
//
//  iOS 移植自 Android DouyinViewModel.kt —— 抖音去水印页状态
//

import Foundation
import Combine

/// 抖音去水印 UI 状态
struct DouyinUiState: Equatable {
    var input: String = ""
    var isProcessing = false
    var stage = ""
    /// 下载进度 0~1;-1 表示无进度(解析阶段)
    var progress: Double = -1
    var title = ""
    var author = ""
    var durationMs = 0
    var resultURL: URL?
    var isSaving = false
    var isSaved = false
    var errorMessage: String?
}

@MainActor
final class DouyinViewModel: ObservableObject {

    @Published var uiState = DouyinUiState()

    func updateInput(_ text: String) {
        uiState.input = text
    }

    /// 解析分享文案 → 获取无水印地址 → 下载到缓存
    func parseAndDownload() {
        guard !uiState.isProcessing else { return }
        let text = uiState.input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else {
            uiState.errorMessage = "请先粘贴分享文案或链接"
            return
        }

        uiState.isProcessing = true
        uiState.stage = "解析链接中..."
        uiState.progress = -1
        uiState.title = ""
        uiState.author = ""
        uiState.durationMs = 0
        uiState.resultURL = nil
        uiState.isSaved = false
        uiState.errorMessage = nil
        AppAnalytics.douyinParseStart()

        Task { [weak self] in
            guard let self else { return }
            do {
                // 1. 解析链接与元数据(解析器为非隔离 async,网络与 JSON 均在后台线程)
                guard let link = DouyinParser.extractShareURL(from: text) else {
                    throw DouyinError.parseFailed("未在文案中找到视频链接,请重新复制分享文案")
                }
                let info = try await DouyinParser.resolveAndFetch(shareLink: link)
                self.uiState.title = info.title
                self.uiState.author = info.author
                self.uiState.durationMs = info.durationMs
                self.uiState.stage = "下载视频中..."

                // 2. 下载到缓存(进度回调在代理线程,跳回主线程刷新)
                let dest = FileManager.default.temporaryDirectory
                    .appendingPathComponent("douyin_\(Int(Date().timeIntervalSince1970 * 1000)).mp4")
                try await DouyinParser.downloadVideo(from: info.playURLs, to: dest) { [weak self] p in
                    Task { @MainActor [weak self] in
                        self?.uiState.progress = p ?? -1
                    }
                }

                self.uiState.isProcessing = false
                self.uiState.progress = 1
                self.uiState.stage = ""
                self.uiState.resultURL = dest
                AppAnalytics.douyinParseSuccess(durationMs: info.durationMs)
            } catch {
                self.uiState.isProcessing = false
                self.uiState.stage = ""
                self.uiState.errorMessage = error.localizedDescription
                AppAnalytics.douyinParseError(error.localizedDescription)
            }
        }
    }

    /// 保存到相册
    func saveResult() {
        guard let url = uiState.resultURL, !uiState.isSaving else { return }
        uiState.isSaving = true
        // MediaUtils.saveToGallery 内部用信号量阻塞等待,放到后台线程执行
        Task.detached(priority: .userInitiated) { [weak self] in
            do {
                let name = "douyin_\(Int(Date().timeIntervalSince1970 * 1000)).mp4"
                _ = try MediaUtils.saveToGallery(fileURL: url, displayName: name)
                await MainActor.run { [weak self] in
                    self?.uiState.isSaving = false
                    self?.uiState.isSaved = true
                    AppAnalytics.douyinSaveSuccess()
                }
            } catch {
                await MainActor.run { [weak self] in
                    self?.uiState.isSaving = false
                    self?.uiState.errorMessage = "保存到相册失败:\(error.localizedDescription)"
                }
            }
        }
    }

    /// 清除结果,回到输入态
    func clearResult() {
        if let url = uiState.resultURL {
            try? FileManager.default.removeItem(at: url)
        }
        uiState = DouyinUiState(input: uiState.input)
    }

    func dismissError() {
        uiState.errorMessage = nil
    }
}
