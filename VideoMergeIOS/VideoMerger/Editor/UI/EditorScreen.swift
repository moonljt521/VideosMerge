//
//  EditorScreen.swift
//  VideoMerger
//
//  iOS 移植自 Android EditorScreen.kt / TimelinePanel.kt / ToolbarPanel.kt / ToolPanels.kt
//  三段式布局：顶栏 + 预览 + 时间轴 + 工具栏/面板
//

import SwiftUI
import PhotosUI

struct EditorScreen: View {
    enum Mode {
        case new([URL])
        case draft
    }

    @StateObject private var viewModel = EditorViewModel()
    let mode: Mode
    let onBack: () -> Void

    @State private var started = false
    @State private var showAddPicker = false
    @State private var showPipVideoPicker = false
    @State private var showPipImagePicker = false
    @State private var showWmImagePicker = false
    @State private var draftSaveTask: Task<Void, Never>? = nil
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(spacing: 0) {
                topBar
                GeometryReader { geo in
                    let previewHeight = geo.size.height * 0.45
                    VStack(spacing: 0) {
                        PreviewPanel(
                            state: viewModel.uiState,
                            playhead: viewModel.playhead,
                            onTogglePlay: { viewModel.togglePlay() },
                            onSeek: { viewModel.seekTo($0) }
                        )
                        .frame(height: previewHeight)

                        Rectangle().fill(Color(hex: 0x111111)).frame(height: 8)

                        VStack(spacing: 0) {
                            TimelinePanel(
                                state: viewModel.uiState,
                                playhead: viewModel.playhead,
                                onSelectClip: { viewModel.selectClip($0) },
                                onSeek: { viewModel.seekTo($0) },
                                onSetInPoint: { viewModel.setInPoint() },
                                onSetOutPoint: { viewModel.setOutPoint() },
                                onClearRange: { viewModel.clearRange() },
                                onSplitAtPlayhead: { viewModel.splitAtPlayhead() },
                                onDelete: {
                                    if let i = viewModel.uiState.inPoint, let o = viewModel.uiState.outPoint, o > i {
                                        viewModel.deleteRange()
                                    } else if let sel = viewModel.uiState.selectedClipId {
                                        viewModel.deleteClip(sel)
                                    }
                                }
                            )

                            if viewModel.uiState.currentPanel == .none {
                                ToolbarPanel(
                                    hasSelectedClip: viewModel.uiState.selectedClip != nil,
                                    canTransition: viewModel.uiState.selectedClipCanTransition,
                                    isExporting: viewModel.uiState.isExporting,
                                    onToolClick: { panel in
                                        if viewModel.uiState.selectedClip == nil &&
                                            panel != .export && panel != .subtitle && panel != .watermarkRemove {
                                            viewModel.showError("请先点击时间轴上的视频片段选中后再使用该工具")
                                        } else {
                                            viewModel.showPanel(panel)
                                        }
                                    },
                                    onPipClick: {
                                        // 已选中画中画片段 → 面板调整；否则 → 选择视频新增
                                        if viewModel.uiState.selectedClip?.pipEnabled == true {
                                            viewModel.showPanel(.picture)
                                        } else {
                                            showPipVideoPicker = true
                                        }
                                    }
                                )
                            } else {
                                panels
                            }
                        }
                    }
                }
            }

            // 导出进度浮层
            if viewModel.uiState.isExporting {
                progressOverlay(
                    title: "正在导出视频",
                    progress: viewModel.uiState.exportProgress,
                    message: viewModel.uiState.exportMessage,
                    indeterminate: false,
                    cancelText: "取消导出",
                    onCancel: { viewModel.cancelExport() })
            }

            // 导入进度浮层
            if viewModel.uiState.isImporting {
                progressOverlay(
                    title: "正在导入素材",
                    progress: viewModel.uiState.importProgress,
                    message: viewModel.uiState.importMessage,
                    indeterminate: viewModel.uiState.importProgress <= 0,
                    cancelText: "取消导入",
                    onCancel: { viewModel.cancelImport() })
            }

            // 错误/提示浮层
            if let msg = viewModel.uiState.errorMessage {
                FloatingMessage(message: msg, severity: viewModel.uiState.errorSeverity) {
                    viewModel.dismissError()
                }
            }
        }
        .preferredColorScheme(.dark)
        .sheet(isPresented: $showAddPicker) {
            VideoPicker(maxSelection: 10) { ids in
                Task {
                    let urls = await MediaUtils.loadVideoURLs(assetIdentifiers: ids)
                    viewModel.addClips(urls: urls)
                }
            }
        }
        .sheet(isPresented: $showPipVideoPicker) {
            VideoPicker(maxSelection: 5) { ids in
                Task {
                    let urls = await MediaUtils.loadVideoURLs(assetIdentifiers: ids)
                    viewModel.addPipOverlay(urls: urls)
                }
            }
        }
        .sheet(isPresented: $showPipImagePicker) {
            ImagePicker { data in
                if let data = data { viewModel.addPipImage(data: data) }
            }
        }
        .sheet(isPresented: $showWmImagePicker) {
            ImagePicker { data in
                if let data = data, let sel = viewModel.uiState.selectedClipId {
                    viewModel.setImageWatermark(sel, data: data)
                }
            }
        }
        .onAppear {
            guard !started else { return }
            started = true
            switch mode {
            case .new(let urls): viewModel.createProject(urls: urls)
            case .draft: viewModel.loadDraft()
            }
        }
        .onChange(of: viewModel.uiState.project.updatedAt) { _ in
            scheduleDraftSave()
        }
        .onChange(of: scenePhase) { phase in
            if phase == .background {
                viewModel.pausePlayback()
                viewModel.saveDraftNow()
            }
        }
    }

    private func scheduleDraftSave() {
        draftSaveTask?.cancel()
        draftSaveTask = Task {
            try? await Task.sleep(nanoseconds: 800_000_000)
            guard !Task.isCancelled else { return }
            viewModel.saveDraftNow()
        }
    }

    // MARK: 顶栏

    private var topBar: some View {
        HStack(spacing: 8) {
            Button(action: {
                viewModel.saveDraftNow()
                onBack()
            }) {
                Image(systemName: "chevron.left").font(.system(size: 18, weight: .semibold))
            }
            .frame(width: 36)

            VStack(alignment: .leading, spacing: 1) {
                Text(viewModel.uiState.project.name)
                    .font(.system(size: 14, weight: .medium))
                    .lineLimit(1)
                if let count = viewModel.uiState.project.mainTrack?.clips.count {
                    Text("\(count) 个片段").font(.system(size: 10)).foregroundColor(Color(hex: 0xFF888888))
                }
            }
            Spacer()

            Button { viewModel.undo() } label: {
                Image(systemName: "arrow.uturn.backward")
            }
            .disabled(!viewModel.uiState.canUndo || viewModel.uiState.isExporting)
            Button { viewModel.redo() } label: {
                Image(systemName: "arrow.uturn.forward")
            }
            .disabled(!viewModel.uiState.canRedo || viewModel.uiState.isExporting)

            Button {
                showAddPicker = true
            } label: {
                Image(systemName: "plus.circle.fill")
            }
            .disabled(viewModel.uiState.isExporting)

            if viewModel.uiState.isExporting {
                ProgressView().scaleEffect(0.7)
            } else {
                Button("导出") {
                    viewModel.showPanel(.export)
                }
                .font(.system(size: 15, weight: .bold))
            }
        }
        .foregroundColor(.white)
        .padding(.horizontal, 8)
        .frame(height: 48)
        .background(Color(hex: 0xFF1A1A1A))
    }

    // MARK: 面板路由

    @ViewBuilder
    private var panels: some View {
        let clip = viewModel.uiState.selectedClip
        switch viewModel.uiState.currentPanel {
        case .trim:
            TrimPanelView(clip: clip, vm: viewModel, onClose: { viewModel.closePanel() })
        case .speed:
            SpeedPanelView(clip: clip, vm: viewModel, onClose: { viewModel.closePanel() })
        case .filter:
            FilterPanelView(clip: clip, vm: viewModel, onClose: { viewModel.closePanel() })
        case .text:
            TextPanelView(clip: clip, vm: viewModel, onClose: { viewModel.closePanel() })
        case .audio:
            AudioPanelView(clip: clip, vm: viewModel, onClose: { viewModel.closePanel() })
        case .transition:
            TransitionPanelView(clip: clip, vm: viewModel, onClose: { viewModel.closePanel() })
        case .watermarkRemove:
            WatermarkRemovePanelView(clip: clip, vm: viewModel, onClose: { viewModel.closePanel() })
        case .picture:
            PicturePanelView(clip: clip, vm: viewModel,
                             onClose: { viewModel.closePanel() },
                             onPickVideo: { showPipVideoPicker = true },
                             onPickImage: { showPipImagePicker = true })
        case .sticker:
            StickerPanelView(vm: viewModel, onClose: { viewModel.closePanel() })
        case .subtitle:
            SubtitlePanelView(state: viewModel.uiState, vm: viewModel,
                              onClose: { viewModel.closePanel() })
        case .imageWatermark:
            ImageWatermarkPanelView(clip: clip, vm: viewModel,
                                    onClose: { viewModel.closePanel() },
                                    onPickImage: { showWmImagePicker = true })
        case .blurBg:
            BlurBgPanelView(clip: clip, vm: viewModel, onClose: { viewModel.closePanel() })
        case .export:
            ExportPanelView(state: viewModel.uiState, vm: viewModel, onClose: { viewModel.closePanel() })
        default:
            EmptyView()
        }
    }

    // MARK: 进度浮层

    private func progressOverlay(title: String, progress: Float, message: String,
                                 indeterminate: Bool, cancelText: String, onCancel: @escaping () -> Void) -> some View {
        ZStack {
            Color.black.opacity(0.66).ignoresSafeArea()
            VStack(alignment: .leading, spacing: 14) {
                Text(title).font(.system(size: 16, weight: .medium))
                Group {
                    if indeterminate {
                        ProgressView().tint(.white)
                    } else {
                        ProgressView(value: Double(progress))
                            .tint(Color(hex: 0xFF2196F3))
                    }
                }
                Text(message).font(.system(size: 13)).foregroundColor(Color(hex: 0xFFAAAAAA))
                HStack {
                    Spacer()
                    Button(cancelText) { onCancel() }
                        .foregroundColor(Color(hex: 0xFFFF7043))
                }
            }
            .padding(24)
            .background(RoundedRectangle(cornerRadius: 16).fill(Color(hex: 0xFF1E1E1E)))
            .padding(.horizontal, 40)
        }
    }
}

// MARK: - 提示浮层

struct FloatingMessage: View {
    let message: String
    let severity: MessageSeverity
    let onDismissAction: () -> Void

    var body: some View {
        VStack {
            Text(message)
                .font(.system(size: 13))
                .foregroundColor(.white)
                .padding(.horizontal, 16).padding(.vertical, 10)
                .background(RoundedRectangle(cornerRadius: 8).fill(
                    severity == .error ? Color(hex: 0xFFD32F2F) : Color(hex: 0xFF333333)))
                .padding(.horizontal, 24)
                .padding(.top, 60)
                .onTapGesture { onDismissAction() }
            Spacer()
        }
        .task {
            try? await Task.sleep(nanoseconds: 3_500_000_000)
            onDismissAction()
        }
    }
}

// MARK: - 时间轴

struct TimelinePanel: View {
    let state: EditorUiState
    let playhead: Double
    let onSelectClip: (String?) -> Void
    let onSeekAction: (Double) -> Void
    let onSetInPoint: () -> Void
    let onSetOutPoint: () -> Void
    let onClearRange: () -> Void
    let onSplitAtPlayhead: () -> Void
    let onDelete: () -> Void

    init(state: EditorUiState, playhead: Double,
         onSelectClip: @escaping (String?) -> Void,
         onSeek: @escaping (Double) -> Void,
         onSetInPoint: @escaping () -> Void,
         onSetOutPoint: @escaping () -> Void,
         onClearRange: @escaping () -> Void,
         onSplitAtPlayhead: @escaping () -> Void,
         onDelete: @escaping () -> Void) {
        self.state = state
        self.playhead = playhead
        self.onSelectClip = onSelectClip
        self.onSeekAction = onSeek
        self.onSetInPoint = onSetInPoint
        self.onSetOutPoint = onSetOutPoint
        self.onClearRange = onClearRange
        self.onSplitAtPlayhead = onSplitAtPlayhead
        self.onDelete = onDelete
    }

    var body: some View {
        VStack(spacing: 0) {
            actionBar
            ruler
            tracks
        }
        .background(Color(hex: 0xFF1A1A1A))
    }

    private var totalDuration: Double { max(state.project.totalDuration, 1.0) }

    // MARK: 快捷操作行

    private var actionBar: some View {
        let hasRange = state.inPoint != nil && state.outPoint != nil && state.outPoint! > state.inPoint!
        let selected = state.selectedClip
        let canSplit = selected != nil &&
            playhead > selected!.timelineStart + 0.05 && playhead < selected!.timelineEnd - 0.05
        return HStack(spacing: 10) {
            Text("\(timecode(playhead)) / \(timecode(state.project.totalDuration))")
                .font(.system(size: 11)).foregroundColor(Color(hex: 0xFF2196F3))
            if hasRange {
                Text("✂ \(timecode(state.inPoint!))-\(timecode(state.outPoint!))")
                    .font(.system(size: 11)).foregroundColor(Color(hex: 0xFFFF7043))
                    .onTapGesture { onClearRange() }
            }
            Spacer()
            actionBarBtn("入", icon: "flag", active: state.inPoint != nil, action: onSetInPoint)
            actionBarBtn("出", icon: "flag", active: state.outPoint != nil, action: onSetOutPoint)
            actionBarBtn("分割", icon: "scissors", enabled: canSplit, action: onSplitAtPlayhead)
            actionBarBtn(hasRange ? "删区间" : "删除", icon: "trash",
                         enabled: hasRange || state.selectedClip != nil, action: onDelete)
        }
        .padding(.horizontal, 12)
        .frame(height: 34)
    }

    private func actionBarBtn(_ label: String, icon: String, enabled: Bool = true,
                              active: Bool = false, action: @escaping () -> Void) -> some View {
        HStack(spacing: 2) {
            Image(systemName: icon).font(.system(size: 11))
            Text(label).font(.system(size: 11))
        }
        .foregroundColor(!enabled ? Color(hex: 0xFF555555) : (active ? Color(hex: 0xFFFF7043) : .white))
        .padding(.horizontal, 6).padding(.vertical, 4)
        .contentShape(Rectangle())
        .onTapGesture { if enabled { action() } }
    }

    // MARK: 标尺

    private var ruler: some View {
        GeometryReader { geo in
            let width = geo.size.width
            let pps = width / totalDuration
            ZStack(alignment: .topLeading) {
                Rectangle().fill(Color(hex: 0xFF222222))
                // 刻度
                let interval = totalDuration <= 10 ? 1.0 : totalDuration <= 30 ? 2.0
                    : totalDuration <= 60 ? 5.0 : totalDuration <= 300 ? 10.0 : 30.0
                ForEach(0..<Int(totalDuration / interval) + 1, id: \.self) { i in
                    let t = Double(i) * interval
                    if t <= totalDuration {
                        Text(timecode(t))
                            .font(.system(size: 9))
                            .foregroundColor(Color(hex: 0xFF888888))
                            .offset(x: t * pps + 2, y: 2)
                    }
                }
                // 区间高亮
                if let i = state.inPoint, let o = state.outPoint, o > i {
                    Rectangle().fill(Color(hex: 0x55FF7043))
                        .frame(width: (o - i) * pps)
                        .offset(x: i * pps)
                }
                // 播放头
                Rectangle().fill(Color(hex: 0xFF2196F3)).frame(width: 2)
                    .offset(x: playhead * pps)
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { g in
                onSeekAction(min(max(Double(g.location.x / pps), 0), totalDuration))
            })
        }
        .frame(height: 30)
        .padding(.horizontal, 12)
    }

    // MARK: 轨道

    private var tracks: some View {
        GeometryReader { geo in
            let width = geo.size.width
            let pps = (width - 24) / totalDuration
            ZStack(alignment: .topLeading) {
                // 多轨：主轨（接缝显示模型）+ 画中画等自由定位轨（真实位置）
                ForEach(Array(state.project.tracks.enumerated()), id: \.element.id) { rowIdx, track in
                    let sorted = track.clips.sorted { $0.timelineStart < $1.timelineStart }
                    let rects: [(clip: Clip, start: Double, width: Double)] =
                        track.type == .main ? displayRects(sorted)
                        : sorted.map { ($0, $0.timelineStart, $0.timelineDuration) }
                    ForEach(Array(rects.enumerated()), id: \.element.clip.id) { _, rect in
                        ClipBlockView(
                            clip: rect.clip,
                            x: rect.start * pps, width: max(rect.width * pps, 20),
                            isSelected: rect.clip.id == state.selectedClipId,
                            onTap: {
                                onSelectClip(rect.clip.id == state.selectedClipId ? nil : rect.clip.id)
                            })
                        .offset(x: 24 + rect.start * pps, y: CGFloat(rowIdx * 48) + 4)
                    }
                    if track.type == .main {
                        ForEach(Array(sorted.enumerated()), id: \.element.id) { _, clip in
                            if clip.transition != .none {
                                TransitionBadgeView()
                                    .offset(x: 24 + clip.timelineEnd * pps - 8,
                                            y: CGFloat(rowIdx * 48) + 20)
                            }
                        }
                    }
                }
                // 播放头
                Rectangle().fill(Color.white).frame(width: 2)
                    .offset(x: playhead * pps)
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { g in
                onSeekAction(min(max(Double((g.location.x - 24) / pps), 0), totalDuration))
            })
        }
        .frame(height: CGFloat(state.project.tracks.count * 48 + 8))
        .padding(.horizontal, 12)
        .padding(.bottom, 4)
    }

    /// 主轨显示矩形：块首尾相接，接缝=前一片段真正结束点（与 Android 显示模型一致）
    private func displayRects(_ clips: [Clip]) -> [(clip: Clip, start: Double, width: Double)] {
        var x = 0.0
        var prevEnd = 0.0
        return clips.map { c in
            let w = c.timelineEnd - prevEnd
            let r = (c, x, w)
            x += w
            prevEnd = c.timelineEnd
            return r
        }
    }

    private func timecode(_ seconds: Double) -> String {
        let s = Int(seconds)
        let m = s / 60
        let sec = s % 60
        return m > 0 ? "\(m)'\(sec)\"" : "\(sec)\""
    }
}

struct ClipBlockView: View {
    let clip: Clip
    let x: Double
    let width: Double
    let isSelected: Bool
    let onTap: () -> Void

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            if let path = clip.thumbnailPath, let img = UIImage(contentsOfFile: path) {
                Image(uiImage: img).resizable().scaledToFill()
            } else {
                Color(hex: 0xFF3D3D3D)
            }
            Text(String(clip.mediaName.prefix(8)))
                .font(.system(size: 9)).foregroundColor(.white)
                .padding(2).background(Color.black.opacity(0.4), in: RoundedRectangle(cornerRadius: 2))
                .padding(2)
            if clip.speed != 1.0 {
                VStack {
                    HStack {
                        Spacer()
                        Text("\(clip.speed)x").font(.system(size: 8)).foregroundColor(.yellow)
                    }
                    Spacer()
                }
            }
        }
        .frame(width: width, height: 44)
        .clipShape(RoundedRectangle(cornerRadius: 6))
        .overlay(
            RoundedRectangle(cornerRadius: 6)
                .stroke(isSelected ? Color.white : .clear, lineWidth: 2)
        )
        .background(isSelected ? Color(hex: 0xFF2196F3) : Color(hex: 0xFF3D3D3D))
        .contentShape(Rectangle())
        .onTapGesture { onTap() }
    }
}

struct TransitionBadgeView: View {
    var body: some View {
        ZStack {
            Circle().fill(Color.white).frame(width: 16, height: 16)
            Image(systemName: "arrow.left.arrow.right")
                .font(.system(size: 9, weight: .bold)).foregroundColor(Color(hex: 0xFF1A1A1A))
        }
    }
}

// MARK: - 一级工具栏

struct ToolbarPanel: View {
    let hasSelectedClip: Bool
    let canTransition: Bool
    let isExporting: Bool
    let onToolClick: (ToolPanel) -> Void

    private struct Item: Identifiable {
        let id = UUID()
        let name: String
        let icon: String
        let panel: ToolPanel
        var enabled: Bool
    }

    var onPipClick: (() -> Void)? = nil

    private var items: [Item] {
        var base: [Item] = [
            .init(name: "剪辑", icon: "scissors", panel: .trim, enabled: hasSelectedClip),
            .init(name: "变速", icon: "speedometer", panel: .speed, enabled: hasSelectedClip),
            .init(name: "滤镜", icon: "camera.filters", panel: .filter, enabled: hasSelectedClip),
            .init(name: "文字", icon: "textformat", panel: .text, enabled: hasSelectedClip),
            .init(name: "字幕", icon: "captions.bubble", panel: .subtitle, enabled: true),
            .init(name: "贴纸", icon: "face.smiling", panel: .sticker, enabled: true),
            .init(name: "画中画", icon: "rectangle.on.rectangle", panel: .picture, enabled: true),
            .init(name: "水印", icon: "photo.badge.plus", panel: .imageWatermark, enabled: hasSelectedClip),
            .init(name: "去水印", icon: "wand.and.stars", panel: .watermarkRemove, enabled: hasSelectedClip),
            .init(name: "音频", icon: "music.note", panel: .audio, enabled: hasSelectedClip),
            .init(name: "背景", icon: "drop.halffull", panel: .blurBg, enabled: hasSelectedClip),
        ]
        if canTransition {
            base.append(.init(name: "转场", icon: "arrow.left.arrow.right", panel: .transition, enabled: hasSelectedClip))
        }
        base.append(.init(name: "导出", icon: "square.and.arrow.down", panel: .export, enabled: true))
        return base
    }

    var body: some View {
        VStack(spacing: 2) {
            if !hasSelectedClip {
                Text("👆 点击时间轴上的视频片段选中后使用工具")
                    .font(.system(size: 12)).foregroundColor(Color(hex: 0xFF888888))
                    .frame(maxWidth: .infinity)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 2) {
                    ForEach(items) { item in
                        VStack(spacing: 3) {
                            Image(systemName: item.icon)
                                .font(.system(size: 20))
                                .foregroundColor(item.enabled ? .white : Color(hex: 0xFF555555))
                            Text(item.name).font(.system(size: 10))
                                .foregroundColor(item.enabled ? .white : Color(hex: 0xFF555555))
                        }
                        .frame(width: 52)
                        .padding(.vertical, 6)
                        .contentShape(Rectangle())
                        .onTapGesture {
                            if isExporting { return }
                            if item.panel == .picture { onPipClick?() } else { onToolClick(item.panel) }
                        }
                    }
                }
                .padding(.horizontal, 10)
            }
        }
        .padding(.vertical, 6)
        .background(Color(hex: 0xFF1A1A1A))
    }
}
