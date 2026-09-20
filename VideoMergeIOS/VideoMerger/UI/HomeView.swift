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
    @ObservedObject private var remoteConfig = RemoteConfig.shared
    @ObservedObject private var ads = AdsManager.shared
    @State private var showNewProjectPicker = false
    @State private var showMergePicker = false
    @State private var showOverwriteDialog = false
    @State private var showDraftDialog = false
    @State private var showEditor = false
    @State private var showMerge = false
    @State private var showLinkParse = false
    @State private var showGif = false
    @State private var mergeInitialURLs: [URL] = []
    @State private var mergeHint: String? = nil
    @State private var editorMode: EditorScreen.Mode = .draft
    @State private var pendingURLs: [URL] = []
    @State private var showHistory = false
    @State private var showSettings = false
    @State private var didHandleLaunchArgs = false
    @State private var historyList: [HistoryEntry] = []
    @State private var playingHistoryURL: URL? = nil

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                VStack(spacing: 12) {
                    homeCard(icon: "plus.circle.fill", iconColor: Color(hex: 0xFF2196F3),
                             title: L10n.t("home.new_project"), subtitle: L10n.t("home.new_project_subtitle")) {
                        if draftInfo != nil { showOverwriteDialog = true } else { showNewProjectPicker = true }
                    }
                    homeCard(icon: "square.grid.2x2.fill", iconColor: Color(hex: 0xFF4CAF50),
                             title: L10n.t("home.merge_entry_title"), subtitle: L10n.t("home.merge_entry_subtitle")) {
                        showMergePicker = true
                    }
                    // 去水印入口受远程开关控制：App Store 审核风险出现时可热降级
                    if remoteConfig.linkParserEnabled {
                        homeCard(icon: "music.note", iconColor: Color(hex: 0xFF26C6DA),
                                 title: L10n.t("home.douyin_title"), subtitle: L10n.t("home.douyin_subtitle")) {
                            showLinkParse = true
                        }
                    }
                    homeCard(icon: "photo.stack", iconColor: Color(hex: 0xFFFFA726),
                             title: L10n.t("home.gif_title"), subtitle: L10n.t("home.gif_subtitle")) {
                        showGif = true
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
            .safeAreaInset(edge: .bottom) {
                // 首页横幅广告：SDK 就绪且远端未关闭时显示
                if ads.bannerEnabled && remoteConfig.adsEnabled {
                    BannerAdView()
                        .frame(height: BannerAdView.preferredHeight)
                }
            }
            .onAppear {
                remoteConfig.refreshIfNeeded()
                // 冷启兜底：didBecomeActive 可能早于视图创建，这里补一次触发
                ads.onActive()
                // 调试辅助：launch argument "-OpenSettings" 冷启时直接打开设置页（自动化验证用）
                // ★ 仅首次 onAppear 处理：从设置页返回主页会再次触发 onAppear，不能重复打开
                if !didHandleLaunchArgs {
                    didHandleLaunchArgs = true
                    if ProcessInfo.processInfo.arguments.contains("-OpenSettings") {
                        showSettings = true
                    }
                }
            }
            .toolbar {
                ToolbarItem(placement: .principal) {
                    Text(L10n.t("home.app_name")).font(.system(size: 20, weight: .bold))
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
                    // ★ 历史图标应进历史记录页（此前误跳合并页）
                    Button {
                        historyList = VideoHistoryStore.loadHistory()
                        showHistory = true
                    } label: {
                        Image(systemName: "clock.arrow.circlepath")
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showSettings = true } label: {
                        Image(systemName: "gearshape")
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
            .navigationDestination(isPresented: $showLinkParse) {
                LinkParseView()
            }
            .navigationDestination(isPresented: $showGif) {
                GifView()
            }
            .navigationDestination(isPresented: $showHistory) {
                HistoryView(
                    history: historyList,
                    onItemClick: { entry in playingHistoryURL = entry.fileURL },
                    onItemDelete: { entry in
                        VideoHistoryStore.deleteEntry(entry: entry)
                        historyList = VideoHistoryStore.loadHistory()
                    },
                    onBack: { showHistory = false }
                )
            }
            .navigationDestination(isPresented: $showSettings) {
                SettingsView(onBack: { showSettings = false },
                             onDraftCleared: { draftInfo = DraftStore.peek() })
                    .navigationBarBackButtonHidden(true)
            }
            .fullScreenCover(isPresented: Binding(
                get: { playingHistoryURL != nil },
                set: { if !$0 { playingHistoryURL = nil } }
            )) {
                if let url = playingHistoryURL {
                    FullscreenVideoPlayer(
                        videoURL: url,
                        onSaveClick: {
                            playingHistoryURL = nil
                            saveHistoryVideo(url)
                        },
                        onDismiss: { playingHistoryURL = nil }
                    )
                }
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
                        if !urls.isEmpty { mergeHint = L10n.t("home.merge_min_videos", urls.count) }
                        return
                    }
                    mergeInitialURLs = urls
                    showMerge = true
                }
            }
        }
        .alert(L10n.t("home.overwrite_draft_title"), isPresented: $showOverwriteDialog) {
            Button(L10n.t("home.overwrite_draft_new"), role: .destructive) { showNewProjectPicker = true }
            Button(L10n.t("home.overwrite_draft_edit")) { openDraft() }
            Button(L10n.t("home.cancel"), role: .cancel) {}
        } message: {
            Text(L10n.t("home.overwrite_draft_message", draftInfo?.name ?? ""))
        }
        .alert(L10n.t("home.draft_title"), isPresented: $showDraftDialog) {
            Button(L10n.t("home.draft_continue")) { openDraft() }
            Button(L10n.t("home.draft_delete"), role: .destructive) {
                DraftStore.clear()
                draftInfo = nil
            }
            Button(L10n.t("home.cancel"), role: .cancel) {}
        } message: {
            Text(draftSummary())
        }
    }

    private func draftSummary() -> String {
        var parts: [String] = [draftInfo?.name ?? ""]
        if let c = draftInfo?.clipCount, c > 0 { parts.append(L10n.t("home.draft_clips", c)) }
        if let s = draftInfo?.subtitleCount, s > 0 { parts.append(L10n.t("home.draft_subtitles", s)) }
        return parts.joined(separator: " · ")
    }

    private func openDraft() {
        editorMode = .draft
        showEditor = true
    }

    /// 把历史成片保存到相册（对齐 Android HistoryScreen 直接调 MediaUtils.saveToGallery）
    private func saveHistoryVideo(_ url: URL) {
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                let ts = Int(Date().timeIntervalSince1970 * 1000)
                try MediaUtils.saveToGallery(fileURL: url, displayName: L10n.t("home.save_filename", ts))
                DispatchQueue.main.async { mergeHint = L10n.t("home.saved_to_gallery") }
            } catch {
                DispatchQueue.main.async { mergeHint = L10n.t("home.save_failed", error.localizedDescription) }
            }
        }
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
