//
//  DouyinView.swift
//  VideoMerger
//
//  iOS 移植自 Android DouyinScreen.kt —— 抖音去水印页
//  粘贴分享文案 → 解析 → 下载 → 预览播放 → 保存到相册
//

import SwiftUI
import UIKit

struct DouyinView: View {
    @StateObject private var viewModel = DouyinViewModel()
    @State private var showFullscreen = false

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
        .navigationTitle("抖音去水印")
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
                Text("粘贴抖音分享文案")
                    .font(.system(size: 16, weight: .bold))
            }
            Text("在抖音里点「分享 → 复制链接」，把整段文案粘贴到下面即可")
                .font(.system(size: 12))
                .foregroundColor(Color(hex: 0xFF888888))

            TextEditor(text: $viewModel.uiState.input)
                .font(.system(size: 13))
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
                    Text("粘贴")
                        .font(.system(size: 14))
                        .padding(.horizontal, 18).padding(.vertical, 8)
                        .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color(hex: 0xFF2196F3)))
                }
                .disabled(viewModel.uiState.isProcessing)

                Button {
                    viewModel.parseAndDownload()
                } label: {
                    HStack(spacing: 6) {
                        if viewModel.uiState.isProcessing {
                            ProgressView().scaleEffect(0.7)
                        }
                        Text("解析视频").font(.system(size: 14, weight: .medium))
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
                Text("解析成功 · 无水印")
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

            if viewModel.uiState.isSaving {
                HStack {
                    ProgressView()
                    Text("保存中...").foregroundColor(.green)
                }
            } else if viewModel.uiState.isSaved {
                HStack {
                    Image(systemName: "checkmark.circle.fill").foregroundColor(.green)
                    Text("已保存到相册").foregroundColor(.green)
                }
            } else {
                Button { viewModel.saveResult() } label: {
                    HStack {
                        Image(systemName: "square.and.arrow.down")
                        Text("保存到相册")
                    }
                    .font(.system(size: 15, weight: .medium))
                    .frame(maxWidth: .infinity).padding(.vertical, 12)
                    .background(Color.green).foregroundColor(.white)
                    .cornerRadius(12)
                }
                Text("提示: 长按视频也可保存 · 点击视频全屏播放")
                    .font(.system(size: 11)).foregroundColor(.gray)
            }
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 16).fill(Color(hex: 0xFF1A1A1A)))
    }

    private var authorLine: String {
        var parts: [String] = [viewModel.uiState.author.isEmpty ? "抖音" : viewModel.uiState.author]
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
            Text("解析失败").font(.system(size: 18, weight: .bold))
            Text(message)
                .font(.system(size: 13))
                .multilineTextAlignment(.center)
                .foregroundColor(Color(hex: 0xFFE57373))
            Button("确定") { viewModel.dismissError() }
                .padding(.top, 4)
        }
        .frame(maxWidth: .infinity)
        .padding(20)
        .background(RoundedRectangle(cornerRadius: 16).fill(Color(hex: 0xFF2A1215)))
    }

    // MARK: - 使用说明

    private var usageSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("使用步骤").font(.system(size: 14, weight: .bold))
            ForEach([
                "1. 打开抖音，找到想保存的视频",
                "2. 点右侧「分享」→「复制链接」",
                "3. 回到这里粘贴，点「解析视频」",
                "4. 预览无水印视频，确认后保存到相册",
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
