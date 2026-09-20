//
//  LinkParseView.swift
//  VideoMerger
//
//  iOS 移植自 Android DouyinScreen.kt —— 短视频去水印页(平台由分享文案自动识别)
//  粘贴分享文案 → 解析 → 下载 → 预览播放 → 保存到相册 / 系统分享
//

import SwiftUI
import UIKit

struct LinkParseView: View {
    @StateObject private var viewModel = LinkParseViewModel()
    @State private var showFullscreen = false
    @FocusState private var inputFocused: Bool

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                inputSection
                if viewModel.uiState.isProcessing {
                    progressSection
                }
                if let url = viewModel.uiState.resultURL {
                    resultSection(url: url)
                }
                if let msg = viewModel.uiState.errorMessage {
                    errorSection(msg)
                }
                usageSection
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .background(Color.black)
        .navigationTitle(L10n.t("douyin.nav_title"))
        .navigationBarTitleDisplayMode(.inline)
        .fullScreenCover(isPresented: $showFullscreen) {
            if let url = viewModel.uiState.resultURL {
                FullscreenVideoPlayer(
                    videoURL: url,
                    onSaveClick: {
                        showFullscreen = false
                        viewModel.saveResult()
                    },
                    onDismiss: { showFullscreen = false }
                )
            }
        }
    }

    // MARK: - 输入区

    private var inputSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: "doc.on.clipboard")
                    .foregroundColor(Color(hex: 0xFF2196F3))
                Text(L10n.t("douyin.input_title"))
                    .font(.system(size: 16, weight: .bold))
            }
            Text(L10n.t("douyin.input_hint"))
                .font(.system(size: 12))
                .foregroundColor(Color(hex: 0xFF888888))

            TextEditor(text: $viewModel.uiState.input)
                .font(.system(size: 13))
                .focused($inputFocused)
                .frame(minHeight: 88)
                .scrollContentBackground(.hidden)
                .padding(8)
                .background(RoundedRectangle(cornerRadius: 10).fill(Color(hex: 0xFF111111)))
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(Color(hex: 0xFF333333)))

            HStack {
                Spacer()
                Button {
                    if let s = UIPasteboard.general.string, !s.isEmpty {
                        viewModel.updateInput(s)
                    }
                } label: {
                    Text(L10n.t("douyin.paste"))
                        .font(.system(size: 14))
                        .padding(.horizontal, 18).padding(.vertical, 8)
                        .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color(hex: 0xFF2196F3)))
                }
                .disabled(viewModel.uiState.isProcessing)

                Button {
                    // 开始解析即收起软键盘,避免挡住下方进度与结果
                    inputFocused = false
                    viewModel.parseAndDownload()
                } label: {
                    HStack(spacing: 6) {
                        if viewModel.uiState.isProcessing {
                            ProgressView().scaleEffect(0.7)
                        }
                        Text(L10n.t("douyin.parse")).font(.system(size: 14, weight: .medium))
                    }
                    .padding(.horizontal, 18).padding(.vertical, 8)
                    .background(RoundedRectangle(cornerRadius: 8).fill(Color(hex: 0xFF2196F3)))
                    .foregroundColor(.white)
                }
                .disabled(viewModel.uiState.isProcessing)
            }
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 16).fill(Color(hex: 0xFF1A1A1A)))
    }

    // MARK: - 进度

    private var progressSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(viewModel.uiState.stage)
                    .font(.system(size: 14, weight: .medium))
                Spacer()
                if viewModel.uiState.progress >= 0 {
                    Text("\(Int(viewModel.uiState.progress * 100))%").bold()
                }
            }
            if viewModel.uiState.progress >= 0 {
                ProgressView(value: viewModel.uiState.progress)
                    .tint(Color(hex: 0xFF2196F3))
            } else {
                ProgressView()
            }
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 16).fill(Color(hex: 0xFF1A1A1A)))
    }

    // MARK: - 结果

    private func resultSection(url: URL) -> some View {
        VStack(spacing: 12) {
            HStack {
                Image(systemName: "checkmark.circle.fill").foregroundColor(.green)
                Text(L10n.t("douyin.result_title"))
                    .font(.system(size: 16, weight: .bold))
                Spacer()
                Button { viewModel.clearResult() } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 13))
                        .foregroundColor(Color(hex: 0xFF999999))
                }
            }
            if !viewModel.uiState.title.isEmpty {
                Text(viewModel.uiState.title)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundColor(Color(hex: 0xFFCCCCCC))
                    .lineLimit(2)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            Text(authorLine)
                .font(.system(size: 12)).foregroundColor(.green)
                .frame(maxWidth: .infinity, alignment: .leading)

            InlineVideoPlayer(
                videoURL: url,
                onTap: { showFullscreen = true },
                onLongPress: { viewModel.saveResult() }
            )
            .frame(height: 220)

            HStack(spacing: 8) {
                if viewModel.uiState.isSaving {
                    HStack {
                        ProgressView()
                        Text(L10n.t("douyin.saving")).foregroundColor(.green)
                    }
                    .frame(maxWidth: .infinity)
                } else if viewModel.uiState.isSaved {
                    HStack {
                        Image(systemName: "checkmark.circle.fill").foregroundColor(.green)
                        Text(L10n.t("douyin.saved")).foregroundColor(.green)
                    }
                    .frame(maxWidth: .infinity)
                } else {
                    Button { viewModel.saveResult() } label: {
                        HStack {
                            Image(systemName: "square.and.arrow.down")
                            Text(L10n.t("douyin.save"))
                        }
                        .font(.system(size: 15, weight: .medium))
                        .frame(maxWidth: .infinity).padding(.vertical, 12)
                        .background(Color.green).foregroundColor(.white)
                        .cornerRadius(12)
                    }
                }

                // 调起系统分享面板
                ShareLink(item: url) {
                    HStack(spacing: 4) {
                        Image(systemName: "square.and.arrow.up")
                        Text(L10n.t("douyin.share"))
                    }
                    .font(.system(size: 15, weight: .medium))
                    .padding(.horizontal, 14).padding(.vertical, 12)
                    .background(Color(hex: 0xFF2A2A2A)).foregroundColor(.white)
                    .cornerRadius(12)
                }
                .simultaneousGesture(TapGesture().onEnded {
                    AppAnalytics.linkShareTap(platform: viewModel.uiState.platformID)
                })
            }
            if !viewModel.uiState.isSaving && !viewModel.uiState.isSaved {
                Text(L10n.t("douyin.result_hint"))
                    .font(.system(size: 11)).foregroundColor(.gray)
            }
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 16).fill(Color(hex: 0xFF1A1A1A)))
    }

    private var authorLine: String {
        var parts: [String] = [viewModel.uiState.author.isEmpty ? L10n.t("douyin.default_author") : viewModel.uiState.author]
        if viewModel.uiState.durationMs > 0 {
            parts.append("\(viewModel.uiState.durationMs / 1000)s")
        }
        return parts.joined(separator: " · ")
    }

    // MARK: - 错误

    private func errorSection(_ message: String) -> some View {
        VStack(spacing: 8) {
            Image(systemName: "xmark.octagon.fill")
                .font(.system(size: 44))
                .foregroundColor(Color(hex: 0xFFFF5252))
            Text(L10n.t("douyin.error_title")).font(.system(size: 18, weight: .bold))
            Text(message)
                .font(.system(size: 13))
                .multilineTextAlignment(.center)
                .foregroundColor(Color(hex: 0xFFE57373))
            Button(L10n.t("douyin.ok")) { viewModel.dismissError() }
                .padding(.top, 4)
        }
        .frame(maxWidth: .infinity)
        .padding(20)
        .background(RoundedRectangle(cornerRadius: 16).fill(Color(hex: 0xFF2A1215)))
    }

    // MARK: - 使用说明

    private var usageSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L10n.t("douyin.usage_title")).font(.system(size: 14, weight: .bold))
            ForEach([
                L10n.t("douyin.usage_step1"),
                L10n.t("douyin.usage_step2"),
                L10n.t("douyin.usage_step3"),
                L10n.t("douyin.usage_step4"),
            ], id: \.self) { step in
                Text(step)
                    .font(.system(size: 12))
                    .foregroundColor(Color(hex: 0xFF888888))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 16).fill(Color(hex: 0xFF1A1A1A)))
    }
}
