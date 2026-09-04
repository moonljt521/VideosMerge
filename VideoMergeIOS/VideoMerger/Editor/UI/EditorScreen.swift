//
//  EditorScreen.swift
//  VideoMerger
//
//  iOS 移植自 Android EditorScreen.kt / TimelinePanel.kt / ToolbarPanel.kt / ToolPanels.kt
//  三段式布局：顶栏 + 预览 + 时间轴 + 工具栏/面板
//

import SwiftUI
import PhotosUI
import UIKit

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
                            onSeek: { viewModel.seekTo($0) },
                            onPlaybackEnded: { viewModel.pausePlayback() }
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
                                },
                                onOpenTransition: { clipId in
                                    viewModel.selectClip(clipId)
                                    viewModel.showPanel(.transition)
                                },
                                onPauseForScrub: { viewModel.pausePlayback() }
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

    // MARK: 草稿自动保存

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
        case .canvas:
            CanvasPanelView(vm: viewModel, onClose: { viewModel.closePanel() })
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

// MARK: - 缩略图内存缓存（对齐 Android Coil 内存缓存；刮擦高频重建胶片时避免反复读盘解码）

private enum ThumbnailCache {
    static let images = NSCache<NSString, UIImage>()

    static func image(atPath path: String) -> UIImage? {
        if let hit = images.object(forKey: path as NSString) { return hit }
        guard let img = UIImage(contentsOfFile: path) else { return nil }
        images.setObject(img, forKey: path as NSString)
        return img
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
    let onOpenTransition: ((String) -> Void)?
    let onPauseForScrub: () -> Void

    init(state: EditorUiState, playhead: Double,
         onSelectClip: @escaping (String?) -> Void,
         onSeek: @escaping (Double) -> Void,
         onSetInPoint: @escaping () -> Void,
         onSetOutPoint: @escaping () -> Void,
         onClearRange: @escaping () -> Void,
         onSplitAtPlayhead: @escaping () -> Void,
         onDelete: @escaping () -> Void,
         onOpenTransition: ((String) -> Void)? = nil,
         onPauseForScrub: @escaping () -> Void = {}) {
        self.state = state
        self.playhead = playhead
        self.onSelectClip = onSelectClip
        self.onSeekAction = onSeek
        self.onSetInPoint = onSetInPoint
        self.onSetOutPoint = onSetOutPoint
        self.onClearRange = onClearRange
        self.onSplitAtPlayhead = onSplitAtPlayhead
        self.onDelete = onDelete
        self.onOpenTransition = onOpenTransition
        self.onPauseForScrub = onPauseForScrub
    }

    /// 固定缩放：每秒 60pt（对齐 Android TimelinePanel pps=60dp，约一屏 10 秒）
    private let pps: CGFloat = 60

    var body: some View {
        VStack(spacing: 0) {
            actionBar
            filmArea
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

    // MARK: 胶片区（剪映式：播放头固定中央，胶片横向滚动）

    private var filmArea: some View {
        GeometryReader { geo in
            let viewportWidth = max(geo.size.width, 1)
            let trackH = CGFloat(state.project.tracks.count * 48 + 8)
            let rulerH: CGFloat = 32
            let viewportHeight = trackH + rulerH
            let filmWidth = CGFloat(totalDuration) * pps
            // ★ 胶片内容指纹：不依赖播放头。只有结构变化（片段增删改/选中/入出点/尺寸）才
            //   重建宿主内容，避免刮擦 30Hz + 播放推进 10Hz 时整棵胶片树每帧重渲染拖垮主线程
            //   （真机上渲染慢，每帧全量重建会灌满主线程：预览被饿死、点按被丢弃）
            let tracksKey = state.project.tracks.map { track in
                "\(track.id)|" + track.clips.map { clip in
                    "\(clip.id),\(clip.timelineStart),\(clip.timelineDuration),\(clip.trimStart),\(clip.trimEnd),\(clip.speed),\(clip.transition),\(clip.pipEnabled)"
                }.joined(separator: ";")
            }.joined(separator: "#")
            let filmKey = "\(tracksKey)|\(state.selectedClipId ?? "-")|\(state.inPoint ?? -1)|\(state.outPoint ?? -1)|\(filmWidth)|\(viewportWidth)|\(viewportHeight)"
            ZStack {
                TimelineScrollHost(
                    pps: pps,
                    totalDuration: totalDuration,
                    viewportWidth: viewportWidth,
                    viewportHeight: viewportHeight,
                    playhead: playhead,
                    isPlaying: state.isPlaying,
                    filmKey: filmKey,
                    onScrub: onSeekAction,
                    onPauseForScrub: onPauseForScrub
                ) {
                    HStack(spacing: 0) {
                        Color.clear.frame(width: viewportWidth / 2, height: viewportHeight)
                        VStack(spacing: 0) {
                            rulerContent(width: filmWidth)
                            tracksContent(width: filmWidth)
                        }
                        .frame(width: filmWidth, height: viewportHeight, alignment: .topLeading)
                        Color.clear.frame(width: viewportWidth / 2, height: viewportHeight)
                    }
                }
                .frame(width: viewportWidth, height: viewportHeight)
                // ★ 固定播放头：钉死滚动视口正中央（对齐 Android CenterPlayhead）
                CenterPlayhead()
                    .frame(width: viewportWidth, height: viewportHeight)
                    .allowsHitTesting(false)
            }
            .frame(width: viewportWidth, height: viewportHeight)
        }
        .frame(height: CGFloat(state.project.tracks.count * 48 + 8) + 32)
        .padding(.horizontal, 12)
        .padding(.bottom, 4)
    }

    // MARK: 标尺（宽度 = 总时长×pps，在滚动容器内；tap 定位，滚动宿主自动居中）

    private func rulerContent(width filmWidth: CGFloat) -> some View {
        ZStack(alignment: .topLeading) {
            Rectangle().fill(Color(hex: 0xFF222222))
            // 刻度：像素间距 ≥64pt 自适应（对齐 Android TimelineRuler）
            let interval = rulerInterval()
            ForEach(0..<Int(totalDuration / interval) + 1, id: \.self) { i in
                let t = Double(i) * interval
                if t <= totalDuration + 1e-9 {
                    Text(timecode(t))
                        .font(.system(size: 9))
                        .foregroundColor(Color(hex: 0xFF888888))
                        .offset(x: CGFloat(t) * pps + 2, y: 2)
                }
            }
            // 区间高亮
            if let i = state.inPoint, let o = state.outPoint, o > i {
                Rectangle().fill(Color(hex: 0x55FF7043))
                    .frame(width: CGFloat(o - i) * pps)
                    .offset(x: CGFloat(i) * pps)
            }
        }
        .frame(width: filmWidth, height: 32)
        .contentShape(Rectangle())
        // ★ 用 SpatialTapGesture 只处理点按定位，不抢夺横向拖动手势；
        //   拖动刮擦交由外层 UIScrollView（TimelineScrollHost）处理，对齐 Android horizontalScroll
        .gesture(SpatialTapGesture().onEnded { value in
            onSeekAction(min(max(Double(value.location.x / pps), 0), totalDuration))
        })
    }

    /// 与 Android 一致的标尺档位：最小的、且像素间距 ≥64pt 的档位
    private func rulerInterval() -> Double {
        let candidates = [0.5, 1.0, 2.0, 5.0, 10.0, 15.0, 30.0, 60.0, 120.0, 300.0]
        return candidates.first { $0 * Double(pps) >= 64 } ?? 300.0
    }

    // MARK: 轨道（宽度 = 总时长×pps；空白处 tap seek，拖动交由滚动宿主横向滚动）

    private func tracksContent(width filmWidth: CGFloat) -> some View {
        ZStack(alignment: .topLeading) {
            // 空白处点按定位（垫在最底层，片段块在上层优先命中，避免点片段时误触发 seek）
            Color.clear
                .frame(width: filmWidth, height: CGFloat(state.project.tracks.count * 48 + 8))
                .contentShape(Rectangle())
                .gesture(SpatialTapGesture().onEnded { value in
                    onSeekAction(min(max(Double(value.location.x / pps), 0), totalDuration))
                })
            // 多轨：主轨（接缝显示模型）+ 画中画等自由定位轨（真实位置）
            ForEach(Array(state.project.tracks.enumerated()), id: \.element.id) { rowIdx, track in
                let sorted = track.clips.sorted { $0.timelineStart < $1.timelineStart }
                let rects: [(clip: Clip, start: Double, width: Double)] =
                    track.type == .main ? displayRects(sorted)
                    : sorted.map { ($0, $0.timelineStart, $0.timelineDuration) }
                ForEach(Array(rects.enumerated()), id: \.element.clip.id) { _, rect in
                    ClipBlockView(
                        clip: rect.clip,
                        x: rect.start * Double(pps), width: max(rect.width * Double(pps), 20),
                        isSelected: rect.clip.id == state.selectedClipId,
                        onTap: {
                            onSelectClip(rect.clip.id == state.selectedClipId ? nil : rect.clip.id)
                        })
                    .offset(x: CGFloat(rect.start) * pps, y: CGFloat(rowIdx * 48) + 4)
                }
                if track.type == .main {
                    ForEach(Array(sorted.enumerated()), id: \.element.id) { _, clip in
                        if clip.transition != .none {
                            TransitionBadgeView(onTap: {
                                if let handler = onOpenTransition {
                                    handler(clip.id)
                                } else {
                                    onSelectClip(clip.id)
                                }
                            })
                            .offset(x: CGFloat(clip.timelineEnd) * pps - 8,
                                    y: CGFloat(rowIdx * 48) + 20)
                        }
                    }
                }
            }
        }
        .frame(width: filmWidth, height: CGFloat(state.project.tracks.count * 48 + 8))
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

// MARK: - 固定播放头（钉死滚动视口正中央的竖线 + 顶部手柄，对齐 Android CenterPlayhead）

private struct CenterPlayhead: View {
    var body: some View {
        ZStack {
            HStack {
                Spacer(minLength: 0)
                Rectangle()
                    .fill(Color.white.opacity(0.9))
                    .frame(width: 2)
                Spacer(minLength: 0)
            }
            VStack(spacing: 0) {
                HStack {
                    Spacer(minLength: 0)
                    Image(systemName: "arrowtriangle.down.fill")
                        .font(.system(size: 12))
                        .foregroundColor(Color(hex: 0xFF2196F3))
                    Spacer(minLength: 0)
                }
                .offset(y: -2)
                Spacer(minLength: 0)
            }
        }
    }
}

// MARK: - 中央滚动宿主（UIScrollView：胶片滚动 ↔ 播放头/预览同步，对齐 Android 滚动模型）
//
// 内容宽度 = 胶片宽 + 视口宽（左右各半个视口留白），使 t=0 与 t=结尾都能精确居中。
// contentOffset.x(t) = t × pps；播放头固定在视口中央。
// - 播放中：playhead 变化 → 直接跟滚（10Hz 步进，实测足够顺滑）。
// - 暂停态拖动 → onScrub(offset/pps) 回写播放头；tap 定位 → playhead 变化 → 动画居中。
// - 播放中拖动 → 先 onPauseForScrub 暂停（剪映式手感），再按暂停态刮擦，保证拖到哪预览跟到哪。

private struct TimelineScrollHost: UIViewRepresentable {
    let pps: CGFloat
    let totalDuration: Double
    let viewportWidth: CGFloat
    let viewportHeight: CGFloat
    let playhead: Double
    let isPlaying: Bool
    let filmKey: String
    let onScrub: (Double) -> Void
    let onPauseForScrub: () -> Void
    let film: AnyView

    init<Content: View>(pps: CGFloat, totalDuration: Double, viewportWidth: CGFloat,
                        viewportHeight: CGFloat, playhead: Double, isPlaying: Bool,
                        filmKey: String,
                        onScrub: @escaping (Double) -> Void,
                        onPauseForScrub: @escaping () -> Void = {},
                        @ViewBuilder content: () -> Content) {
        self.pps = pps
        self.totalDuration = totalDuration
        self.viewportWidth = viewportWidth
        self.viewportHeight = viewportHeight
        self.playhead = playhead
        self.isPlaying = isPlaying
        self.filmKey = filmKey
        self.onScrub = onScrub
        self.onPauseForScrub = onPauseForScrub
        self.film = AnyView(content())
    }

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    func makeUIView(context: Context) -> UIScrollView {
        let sv = UIScrollView()
        sv.showsHorizontalScrollIndicator = false
        sv.showsVerticalScrollIndicator = false
        sv.alwaysBounceHorizontal = true
        sv.alwaysBounceVertical = false
        sv.delegate = context.coordinator
        context.coordinator.scrollView = sv
        let hc = UIHostingController(rootView: film)
        hc.view.backgroundColor = .clear
        context.coordinator.hostingController = hc
        sv.addSubview(hc.view)
        return sv
    }

    func updateUIView(_ sv: UIScrollView, context: Context) {
        let c = context.coordinator
        c.pps = pps
        c.totalDuration = totalDuration
        c.isPlaying = isPlaying
        c.onScrub = onScrub
        c.onPauseForScrub = onPauseForScrub

        // ★ 只有胶片结构变化才重建宿主内容（见 filmArea 的 filmKey 注释）
        if c.lastFilmKey != filmKey {
            c.lastFilmKey = filmKey
            c.hostingController?.rootView = film
            c.hostingController?.view.setNeedsLayout()

            let filmWidth = CGFloat(totalDuration) * pps
            let contentWidth = filmWidth + viewportWidth
            let contentHeight = viewportHeight
            if sv.contentSize != CGSize(width: contentWidth, height: contentHeight) {
                sv.contentSize = CGSize(width: contentWidth, height: contentHeight)
            }
            c.hostingController?.view.frame = CGRect(x: 0, y: 0, width: contentWidth, height: contentHeight)
        }

        let clamped = min(max(playhead, 0), totalDuration)
        let target = CGFloat(clamped) * pps
        // 用户手势进行中不抢夺控制权，避免刮擦抖动
        if sv.isDragging || sv.isDecelerating || sv.isTracking {
            return
        }
        guard abs(sv.contentOffset.x - target) > 0.5 else { return }
        if isPlaying {
            c.programmatic = true
            sv.setContentOffset(CGPoint(x: target, y: 0), animated: false)
            c.programmatic = false
        } else {
            c.programmatic = true
            sv.setContentOffset(CGPoint(x: target, y: 0), animated: true)
        }
    }

    final class Coordinator: NSObject, UIScrollViewDelegate {
        var pps: CGFloat = 60
        var totalDuration: Double = 1
        var isPlaying = false
        var onScrub: (Double) -> Void = { _ in }
        var onPauseForScrub: () -> Void = {}
        var programmatic = false
        weak var scrollView: UIScrollView?
        var hostingController: UIHostingController<AnyView>?
        var lastFilmKey: String?
        // ★ 刮擦节流：滚动事件 60~120Hz，直接全量回写会让主线程重建整个编辑器+精准 seek，
        //   互相取消导致画面冻住、手势/点按跟着卡。压到 ~30Hz，松手时补一次尾帧保证精确落点。
        var lastScrubFlush: Double = 0
        var pendingScrubT: Double? = nil

        func scrollViewDidScroll(_ sv: UIScrollView) {
            if programmatic { return }
            if isPlaying { return }
            if sv.isDragging || sv.isDecelerating || sv.isTracking {
                let t = min(max(Double(sv.contentOffset.x / pps), 0), totalDuration)
                let now = CFAbsoluteTimeGetCurrent()
                if now - lastScrubFlush >= 1.0 / 30.0 {
                    lastScrubFlush = now
                    pendingScrubT = nil
                    onScrub(t)
                } else {
                    pendingScrubT = t
                }
            }
        }

        func scrollViewDidEndDragging(_ sv: UIScrollView, willDecelerate decelerate: Bool) {
            if !decelerate { flushPendingScrub() }
        }

        func scrollViewDidEndDecelerating(_ sv: UIScrollView) {
            flushPendingScrub()
        }

        private func flushPendingScrub() {
            if let t = pendingScrubT {
                pendingScrubT = nil
                lastScrubFlush = CFAbsoluteTimeGetCurrent()
                if !isPlaying { onScrub(t) }
            }
        }

        func scrollViewWillBeginDragging(_ sv: UIScrollView) {
            // 用户接管：取消进行中的程序滚动，避免 programmatic 标志卡死导致后续刮擦失效
            programmatic = false
            // ★ 播放中上手拖胶片 → 先暂停再刮擦（剪映式手感），否则拖动与预览不联动
            if isPlaying {
                isPlaying = false
                onPauseForScrub()
            }
        }

        func scrollViewDidEndScrollingAnimation(_ sv: UIScrollView) {
            programmatic = false
        }
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
            // ★ 缩略图走内存缓存：刮擦时每 tick 都重建胶片，直接读盘解码会把主线程拖垮
            if let path = clip.thumbnailPath, let img = ThumbnailCache.image(atPath: path) {
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
    var onTap: (() -> Void)? = nil
    var body: some View {
        ZStack {
            Circle().fill(Color.white).frame(width: 16, height: 16)
            Image(systemName: "arrow.left.arrow.right")
                .font(.system(size: 9, weight: .bold)).foregroundColor(Color(hex: 0xFF1A1A1A))
        }
        .contentShape(Circle())
        .onTapGesture { onTap?() }
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
        base.append(.init(name: "画布", icon: "aspectratio", panel: .canvas, enabled: true))
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
