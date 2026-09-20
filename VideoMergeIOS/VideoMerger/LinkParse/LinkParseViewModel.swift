//
//  LinkParseViewModel.swift
//  VideoMerger
//
//  去水印页状态编排,移植自 Android DouyinViewModel.kt
//  与具体平台无关:平台由 LinkParserRegistry 从分享文案里识别,再交给对应解析器
//

import Foundation
import Combine

/// 去水印 UI 状态
struct LinkParseUiState: Equatable {
    var input: String = ""
    /// 命中的解析平台 id;空 = 尚未识别出平台
    var platformID = ""
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
final class LinkParseViewModel: ObservableObject {

    @Published var uiState = LinkParseUiState()

    func updateInput(_ text: String) {
        uiState.input = text
    }

    /// 解析分享文案 → 获取无水印地址 → 下载到缓存
    func parseAndDownload() {
        guard !uiState.isProcessing else { return }
        let text = uiState.input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else {
            uiState.errorMessage = L10n.t("douyin.error_empty_input")
            return
        }
        guard let match = LinkParserRegistry.detect(in: text) else {
            uiState.errorMessage = L10n.t("douyin.error_no_link_found")
            return
        }

        let parser = match.parser
        uiState.platformID = parser.id
        uiState.isProcessing = true
        uiState.stage = L10n.t("douyin.stage_parsing")
        uiState.progress = -1
        uiState.title = ""
        uiState.author = ""
        uiState.durationMs = 0
        uiState.resultURL = nil
        uiState.isSaved = false
        uiState.errorMessage = nil
        AppAnalytics.linkParseStart(platform: parser.id)

        Task { [weak self] in
            guard let self else { return }
            do {
                // 1. 解析链接与元数据(解析器为非隔离 async,网络与 JSON 均在后台线程)
                let info = try await parser.parse(shareLink: match.shareURL)
                self.uiState.title = info.title
                self.uiState.author = info.author
                self.uiState.durationMs = info.durationMs

                // 体积闸门:超限就不发起下载,否则会留下一个永远走不完进度
                if let bytes = info.sizeBytes, bytes > LinkParsePolicy.maxDownloadBytes {
                    throw LinkParseError(L10n.t("douyin.error_too_large",
                                                LinkParsePolicy.human(bytes),
                                                LinkParsePolicy.human(LinkParsePolicy.maxDownloadBytes)))
                }
                self.uiState.stage = L10n.t("douyin.stage_downloading")

                // 2. 下载到缓存(进度回调在代理线程,跳回主线程刷新)
                let ts = Int(Date().timeIntervalSince1970 * 1000)
                let dest = FileManager.default.temporaryDirectory
                    .appendingPathComponent("\(parser.id)_\(ts).mp4")
                try await parser.download(urls: info.playURLs, to: dest) { [weak self] p in
                    Task { @MainActor [weak self] in
                        self?.uiState.progress = p ?? -1
                    }
                }

                self.uiState.isProcessing = false
                self.uiState.progress = 1
                self.uiState.stage = ""
                self.uiState.resultURL = dest
                AppAnalytics.linkParseSuccess(platform: parser.id, durationMs: info.durationMs)
            } catch {
                self.uiState.isProcessing = false
                self.uiState.stage = ""
                self.uiState.errorMessage = error.localizedDescription
                AppAnalytics.linkParseError(platform: parser.id, message: error.localizedDescription)
            }
        }
    }

    /// 保存到相册
    func saveResult() {
        guard let url = uiState.resultURL, !uiState.isSaving else { return }
        let platform = uiState.platformID
        uiState.isSaving = true
        // MediaUtils.saveToGallery 内部用信号量阻塞等待,放到后台线程执行
        Task.detached(priority: .userInitiated) { [weak self] in
            do {
                let name = "\(platform)_\(Int(Date().timeIntervalSince1970 * 1000)).mp4"
                _ = try MediaUtils.saveToGallery(fileURL: url, displayName: name)
                await MainActor.run { [weak self] in
                    self?.uiState.isSaving = false
                    self?.uiState.isSaved = true
                    AppAnalytics.linkSaveSuccess(platform: platform)
                }
            } catch {
                await MainActor.run { [weak self] in
                    self?.uiState.isSaving = false
                    self?.uiState.errorMessage = L10n.t("douyin.error_save_failed", error.localizedDescription)
                }
            }
        }
    }

    /// 清除结果,回到输入态
    func clearResult() {
        if let url = uiState.resultURL {
            try? FileManager.default.removeItem(at: url)
        }
        uiState = LinkParseUiState(input: uiState.input)
    }

    func dismissError() {
        uiState.errorMessage = nil
    }
}
