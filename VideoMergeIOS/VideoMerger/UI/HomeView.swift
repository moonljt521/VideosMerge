//
//  HomeView.swift
//  VideoMerger
//
//  iOS 移植自 Android HomeScreen.kt：新建项目 / 视频合并 / 草稿 / 历史
//

import SwiftUI
import PhotosUI

struct HomeView: View {
    @State private var draftInfo = DraftStore.peek()
    @State private var showNewProjectPicker = false
    @State private var showMergePicker = false
    @State private var showOverwriteDialog = false
    @State private var showDraftDialog = false
    @State private var showEditor = false
    @State private var showMerge = false
    @State private var mergeInitialURLs: [URL] = []
    @State private var mergeHint: String? = nil
    @State private var editorMode: EditorScreen.Mode = .draft
    @State private var pendingURLs: [URL] = []

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                VStack(spacing: 12) {
                    homeCard(icon: "plus.circle.fill", iconColor: Color(hex: 0xFF2196F3),
                             title: "新建项目", subtitle: "选择视频开始编辑") {
                        if draftInfo != nil { showOverwriteDialog = true } else { showNewProjectPicker = true }
                    }
                    homeCard(icon: "square.grid.2x2.fill", iconColor: Color(hex: 0xFF4CAF50),
                             title: "视频合并", subtitle: "宫格 / 主次 / 照片墙") {
                        showMergePicker = true
                    }
                    Spacer()
                }
                .padding(.top, 8)
                .overlay(alignment: .bottom) {
                    if let hint = mergeHint {
                        Text(hint)
                            .font(.system(size: 13))
                            .foregroundColor(.white)
                            .padding(.horizontal, 16).padding(.vertical, 10)
                            .background(Capsule().fill(Color(hex: 0xFFD32F2F).opacity(0.95)))
                            .padding(.bottom, 40)
                            .onTapGesture { mergeHint = nil }
                            .task {
                                try? await Task.sleep(nanoseconds: 3_500_000_000)
                                mergeHint = nil
                            }
                    }
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    Text("影剪").font(.system(size: 20, weight: .bold))
                }
                ToolbarItem(placement: .topBarTrailing) {
                    if draftInfo != nil {
                        Button { showDraftDialog = true } label: {
                            ZStack(alignment: .topTrailing) {
                                Image(systemName: "folder.fill")
                                Circle()
                                    .fill(Color.red)
                                    .frame(width: 8, height: 8)
                                    .offset(x: 6, y: -4)
                            }
                        }
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showMerge = true } label: {
                        Image(systemName: "clock.arrow.circlepath")
                    }
                }
            }
            .navigationDestination(isPresented: $showEditor) {
                EditorScreen(mode: editorMode, onBack: {
                    showEditor = false
                    draftInfo = DraftStore.peek()
                })
                .navigationBarBackButtonHidden(true)
            }
            .navigationDestination(isPresented: $showMerge) {
                MergeScreen(initialURLs: mergeInitialURLs)
            }
        }
        .preferredColorScheme(.dark)
        .sheet(isPresented: $showNewProjectPicker) {
            VideoPicker(maxSelection: 10) { ids in
                // ★ @State 必须在主线程更新，否则首次导航不生效
                Task { @MainActor in
                    let urls = await MediaUtils.loadVideoURLs(assetIdentifiers: ids)
                    pendingURLs = urls
                    editorMode = .new(urls)
                    showEditor = true
                }
            }
        }
        .sheet(isPresented: $showMergePicker) {
            VideoPicker(maxSelection: 10) { ids in
                Task { @MainActor in
                    let urls = await MediaUtils.loadVideoURLs(assetIdentifiers: ids)
                    guard urls.count >= 2 else {
                        if !urls.isEmpty { mergeHint = "视频合并至少需要选择 2 个视频（当前 \(urls.count) 个）" }
                        return
                    }
                    mergeInitialURLs = urls
                    showMerge = true
                }
            }
        }
        .alert("覆盖现有草稿？", isPresented: $showOverwriteDialog) {
            Button("仍要新建", role: .destructive) { showNewProjectPicker = true }
            Button("继续编辑草稿") { openDraft() }
            Button("取消", role: .cancel) {}
        } message: {
            Text("当前有未导出的草稿「\(draftInfo?.name ?? "")」，新建项目将自动覆盖它。")
        }
        .alert("草稿", isPresented: $showDraftDialog) {
            Button("继续编辑") { openDraft() }
            Button("删除草稿", role: .destructive) {
                DraftStore.clear()
                draftInfo = nil
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text(draftSummary())
        }
    }

    private func draftSummary() -> String {
        var parts: [String] = [draftInfo?.name ?? ""]
        if let c = draftInfo?.clipCount, c > 0 { parts.append("\(c) 个片段") }
        if let s = draftInfo?.subtitleCount, s > 0 { parts.append("\(s) 条字幕") }
        return parts.joined(separator: " · ")
    }

    private func openDraft() {
        editorMode = .draft
        showEditor = true
    }

    private func homeCard(icon: String, iconColor: Color, title: String,
                          subtitle: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Spacer()
                VStack(spacing: 8) {
                    Image(systemName: icon).font(.system(size: 40)).foregroundColor(iconColor)
                    Text(title).font(.system(size: 16, weight: .medium))
                    Text(subtitle).font(.system(size: 12)).foregroundColor(Color(hex: 0xFF666666))
                }
                Spacer()
            }
            .frame(maxWidth: .infinity).frame(height: 110)
            .background(RoundedRectangle(cornerRadius: 16).fill(Color(hex: 0xFF1A1A1A)))
        }
        .padding(.horizontal, 16)
    }
}
