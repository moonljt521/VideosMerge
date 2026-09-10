//
//  MergeScreen.swift
//  VideoMerger
//
//  iOS 移植自 Android VideoMerger 的 MergeScreen.kt
//  主界面：视频选择 / 合并模式 / 参数 / 合并按钮 / 进度日志 / 预览 / 错误
//

import SwiftUI
import PhotosUI
import AVKit

struct MergeScreen: View {
    /// 首页带入的已选视频（可选）
    var initialURLs: [URL] = []
    @StateObject private var viewModel = MergeViewModel()
    @State private var showPicker = false
    @State private var isLoadingVideos = false
    @State private var initialApplied = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    Spacer().frame(height: 4)

                    // 1. 主视图：合并结果（有结果时）/ 布局预览（无结果时）
                    // 预览顶到首位，选完视频不用下滑即可看到排布；缩略图与添加入口并入预览卡。
                    if let result = viewModel.uiState.mergeResult {
                        PreviewSection(
                            result: result,
                            isSaved: viewModel.uiState.isSaved,
                            isSaving: viewModel.uiState.isSaving,
                            onTap: { viewModel.openFullscreen() },
                            onLongPress: { viewModel.saveResult() },
                            onSaveClick: { viewModel.saveResult() },
                            onClear: { viewModel.clearResult() }
                        )
                    } else {
                        MergeLayoutPreviewSection(
                            urls: viewModel.uiState.selectedVideoURLs,
                            mergeType: viewModel.uiState.mergeType,
                            options: viewModel.uiState.options,
                            onShuffle: { viewModel.shufflePhotoWall() },
                            onRemove: { viewModel.removeVideo(at: $0) },
                            onPickClick: { showPicker = true }
                        )
                    }

                    // 加载视频中的提示（避免用户以为卡住）
                    if isLoadingVideos {
                        HStack(spacing: 8) {
                            ProgressView()
                            Text("正在加载选中的视频到本地...")
                                .font(.subheadline)
                                .foregroundColor(.secondary)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                    }

                    // 2. 合并模式
                    MergeTypeSection(
                        selectedType: viewModel.uiState.mergeType,
                        onTypeSelected: { viewModel.onMergeTypeSelected($0) }
                    )

                    // 3. 参数
                    OptionsSection(
                        mergeType: viewModel.uiState.mergeType,
                        options: viewModel.uiState.options,
                        onOptionsChanged: { viewModel.updateOptions($0) }
                    )

                    // 4. 合并按钮
                    Button {
                        viewModel.startMerge()
                    } label: {
                        HStack {
                            Image(systemName: "video.slash")
                            Text("开始合并").font(.system(size: 18, weight: .bold))
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 56)
                        .background(viewModel.uiState.isProcessing || viewModel.uiState.selectedVideoURLs.count < 2
                                    ? Color.gray : Color.accentColor)
                        .foregroundColor(.white)
                        .cornerRadius(12)
                    }
                    .disabled(viewModel.uiState.isProcessing || viewModel.uiState.selectedVideoURLs.count < 2)

                    // 5. 进度 + 日志
                    if viewModel.uiState.isProcessing {
                        ProgressAndLogSection(
                            progress: viewModel.uiState.progress,
                            message: viewModel.uiState.statusMessage,
                            logText: viewModel.uiState.logLines
                        )
                    }

                    // 6. 错误
                    if let msg = viewModel.uiState.errorMessage {
                        ErrorSection(message: msg) { viewModel.dismissError() }
                    }

                    Spacer().frame(height: 32)
                }
                .padding(.horizontal, 16)
            }
            .background(Color(.systemBackground))
            .navigationTitle("影剪 · 视频合并")
            .navigationBarTitleDisplayMode(.inline)
            .onAppear {
                // ★ 首页带入的已选视频（只应用一次）
                guard !initialApplied, !initialURLs.isEmpty else { return }
                initialApplied = true
                viewModel.onVideosSelected(initialURLs)
            }
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button {
                        viewModel.clearAll()
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        viewModel.showHistoryPage()
                    } label: {
                        Image(systemName: "clock.arrow.circlepath")
                    }
                }
            }
            .navigationDestination(isPresented: $viewModel.uiState.showHistory) {
                HistoryView(
                    history: viewModel.uiState.history,
                    onItemClick: { entry in viewModel.openFullscreen(url: entry.fileURL) },
                    onItemDelete: { entry in viewModel.deleteHistoryEntry(entry) },
                    onBack: { viewModel.hideHistoryPage() }
                )
            }
            .fullScreenCover(isPresented: $viewModel.uiState.isFullscreen) {
                if let url = viewModel.uiState.fullscreenVideoURL {
                    FullscreenVideoPlayer(
                        videoURL: url,
                        onSaveClick: {
                            // ★ 从历史页打开时 mergeResult 为 nil，不能走 saveResult()
                            viewModel.closeFullscreen()
                            viewModel.saveVideoToGallery(url: url)
                        },
                        onDismiss: { viewModel.closeFullscreen() }
                    )
                }
            }
            .fullScreenCover(isPresented: $showPicker) {
                VideoPicker(maxSelection: 20) { assetIds in
                    // picker 内部已经 dismiss，这里再同步关闭 fullScreenCover
                    showPicker = false
                    guard !assetIds.isEmpty else { return }

                    // 立刻显示加载中，让用户知道在处理（避免以为卡住）
                    isLoadingVideos = true

                    Task {
                        // 用 PHAsset API 流式写盘（不读入内存，避免 12 个视频撑爆内存）
                        let urls = await MediaUtils.loadVideoURLs(assetIdentifiers: assetIds)
                        await MainActor.run {
                            isLoadingVideos = false
                            if !urls.isEmpty {
                                // ★ 追加而非替换：预览卡「+」是增量添加入口
                                viewModel.addVideos(urls)
                            } else {
                                viewModel.uiState.errorMessage = "视频加载失败，请重新选择"
                            }
                        }
                    }
                }
            }
        }
    }
}

// MARK: - 视频缩略图

private struct VideoThumbnailView: View {
    let url: URL
    @State private var image: UIImage?
    @State private var loaded = false

    var body: some View {
        ZStack {
            if let img = image {
                Image(uiImage: img)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
            } else {
                Color.gray.opacity(0.3)
                Image(systemName: "video")
                    .foregroundColor(.gray)
            }
        }
        .onAppear {
            guard !loaded else { return }
            loaded = true
            // 用低优先级后台队列生成缩略图，避免抢占主线程
            // 错开多个缩略图同时生成的 IO 高峰
            Task.detached(priority: .utility) {
                // 微小延迟，让 UI 刷新先完成
                try? await Task.sleep(nanoseconds: 50_000_000)
                let img = MediaUtils.loadThumbnailFromURL(url: url)
                await MainActor.run { self.image = img }
            }
        }
    }
}

// MARK: - 2. 合并模式

private struct MergeTypeSection: View {
    let selectedType: MergeType
    let onTypeSelected: (MergeType) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("合并模式")
                .font(.headline)
                .fontWeight(.bold)

            HStack(spacing: 8) {
                ForEach(MergeType.allCases) { type in
                    MergeTypeCard(
                        title: type.displayName,
                        subtitle: type.subtitle,
                        isSelected: selectedType == type,
                        onClick: { onTypeSelected(type) }
                    )
                    .frame(maxWidth: .infinity)
                }
            }
        }
        .padding(16)
        .background(Color(.secondarySystemBackground))
        .cornerRadius(16)
    }
}

private struct MergeTypeCard: View {
    let title: String
    let subtitle: String
    let isSelected: Bool
    let onClick: () -> Void

    var body: some View {
        Button(action: onClick) {
            VStack(spacing: 4) {
                Text(title)
                    .font(.system(size: 14, weight: .bold))
                Text(subtitle)
                    .font(.system(size: 11))
                    .foregroundColor(.secondary)
            }
            .padding(12)
            .frame(maxWidth: .infinity)
            .background(isSelected ? Color.accentColor.opacity(0.15) : Color(.systemBackground))
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(isSelected ? Color.accentColor : Color.clear, lineWidth: 2)
            )
            .cornerRadius(12)
        }
        .buttonStyle(.plain)
    }
}

// MARK: - 3.5 布局预览

/// 合并前的静态布局预览：直接复用 MergeLayout 计算出的矩形，
/// 把每个视频首帧缩略图按最终位置/大小摆好，所见即所得。
private struct MergeLayoutPreviewSection: View {
    let urls: [URL]
    let mergeType: MergeType
    let options: MergeOptions
    let onShuffle: () -> Void
    let onRemove: (Int) -> Void
    let onPickClick: () -> Void

    @State private var aspects: [Double] = []

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: "square.grid.2x2")
                    .foregroundColor(.accentColor)
                Text("布局预览")
                    .font(.headline)
                    .fontWeight(.bold)
                Spacer()
                if mergeType == .photoWall && urls.count >= 2 {
                    Button(action: onShuffle) {
                        HStack(spacing: 4) {
                            Image(systemName: "shuffle")
                            Text("换一批").font(.system(size: 12))
                        }
                    }
                }
            }

            Text(previewSubtitle)
                .font(.system(size: 11))
                .foregroundColor(.secondary)

            if urls.isEmpty {
                // 空态：占位区直接给主入口，选片不用再去别处找按钮
                VStack(spacing: 10) {
                    Button(action: onPickClick) {
                        HStack {
                            Image(systemName: "plus.circle.fill")
                            Text("从相册选择视频")
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                    }
                    .buttonStyle(.borderedProminent)
                    Text("选好视频后这里实时显示合并布局")
                        .font(.system(size: 11))
                        .foregroundColor(.secondary)
                }
                .frame(maxWidth: .infinity)
                .frame(height: 150)
                .background(Color(.systemBackground))
                .cornerRadius(10)
            } else if urls.count < 2 {
                // 只有 1 个：给出明确的「还差 1 个」引导
                VStack(spacing: 10) {
                    Text("已选 1 个，再选 1 个视频即可查看布局预览")
                        .font(.system(size: 12))
                        .foregroundColor(.secondary)
                    Button(action: onPickClick) {
                        HStack {
                            Image(systemName: "plus.circle.fill")
                            Text("继续添加")
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                    }
                    .buttonStyle(.bordered)
                }
                .frame(maxWidth: .infinity)
                .frame(height: 150)
                .background(Color(.systemBackground))
                .cornerRadius(10)
            } else {
                LayoutCanvasView(layout: previewLayout, isPhotoWall: mergeType == .photoWall)
            }

            // 已选视频缩略图条（紧凑版，末尾「+」继续添加）
            if !urls.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(urls.indices, id: \.self) { i in
                            VideoThumbnailView(url: urls[i])
                                .frame(width: 56, height: 100)
                                .clipped()
                                .cornerRadius(8)
                                // 右上角「−」移除按钮
                                .overlay(alignment: .topTrailing) {
                                    Button {
                                        onRemove(i)
                                    } label: {
                                        Image(systemName: "minus.circle.fill")
                                            .font(.system(size: 16))
                                            .foregroundColor(.white)
                                            .shadow(color: .black.opacity(0.55), radius: 2)
                                    }
                                    .offset(x: 6, y: -6)
                                }
                        }
                        Button(action: onPickClick) {
                            ZStack {
                                RoundedRectangle(cornerRadius: 8)
                                    .fill(Color(.systemBackground))
                                Image(systemName: "plus.circle.fill")
                                    .font(.system(size: 22))
                                    .foregroundColor(.secondary)
                            }
                        }
                        .frame(width: 56, height: 100)
                    }
                }
                Text("已选 \(urls.count) 个视频 · 点 + 添加 · 点 − 移除")
                    .font(.system(size: 11))
                    .foregroundColor(.secondary)
            }
        }
        .padding(16)
        .background(Color(.secondarySystemBackground))
        .cornerRadius(16)
        .task(id: urls) {
            await loadAspects()
        }
    }

    private var previewLayout: PreviewLayout {
        buildPreviewLayout(urls: urls, mergeType: mergeType, options: options, aspects: aspects)
    }

    private var previewSubtitle: String {
        guard urls.count >= 2 else { return "合并前先选好视频，预览会实时跟随参数变化" }
        let note: String
        switch mergeType {
        case .grid:      note = " · 按主导比例自适应"
        case .collage:   note = " · 主窗口占比可调"
        case .photoWall: note = " · 随机布局可换一批"
        }
        return "\(mergeType.displayName) · 输出 \(previewLayout.outW)×\(previewLayout.outH) · \(urls.count) 个视频\(note)"
    }

    /// 网格布局需要各视频宽高比；在后台读取，避免阻塞滚动。
    private func loadAspects() async {
        guard urls.count >= 2 else {
            aspects = []
            return
        }
        let snapshot = urls
        let loaded = await Task.detached(priority: .utility) { () -> [Double] in
            snapshot.map { MediaUtils.getVideoAspect(url: $0) ?? (16.0 / 9.0) }
        }.value
        aspects = loaded
    }
}

private struct PreviewCell {
    let url: URL
    let rect: LayoutRect
    let rotation: Double
}

private struct PreviewLayout {
    let outW: Int
    let outH: Int
    let cells: [PreviewCell]
}

private struct LayoutCanvasView: View {
    let layout: PreviewLayout
    let isPhotoWall: Bool

    var body: some View {
        GeometryReader { geo in
            let scale = min(
                geo.size.width / CGFloat(max(layout.outW, 1)),
                geo.size.height / CGFloat(max(layout.outH, 1))
            )
            let canvasW = CGFloat(layout.outW) * scale
            let canvasH = CGFloat(layout.outH) * scale

            ZStack(alignment: .topLeading) {
                ForEach(Array(layout.cells.enumerated()), id: \.offset) { _, cell in
                    VideoThumbnailView(url: cell.url)
                        .frame(width: CGFloat(cell.rect.w) * scale,
                               height: CGFloat(cell.rect.h) * scale)
                        .clipped()
                        .overlay(
                            Rectangle()
                                .stroke(isPhotoWall ? Color.white : Color.clear, lineWidth: 1.5)
                        )
                        .rotationEffect(.degrees(cell.rotation))
                        .offset(x: CGFloat(cell.rect.x) * scale,
                                y: CGFloat(cell.rect.y) * scale)
                }
            }
            .frame(width: canvasW, height: canvasH, alignment: .topLeading)
            .background(isPhotoWall ? Color(red: 40/255, green: 40/255, blue: 45/255) : Color.black)
            .cornerRadius(8)
            .frame(width: geo.size.width, height: geo.size.height)
        }
        .aspectRatio(CGFloat(max(layout.outW, 1)) / CGFloat(max(layout.outH, 1)), contentMode: .fit)
        .frame(maxWidth: .infinity)
        .frame(maxHeight: 360)
    }
}

private func buildPreviewLayout(urls: [URL], mergeType: MergeType,
                                options: MergeOptions, aspects: [Double]) -> PreviewLayout {
    guard !urls.isEmpty else { return PreviewLayout(outW: 1, outH: 1, cells: []) }

    switch mergeType {
    case .grid:
        let safeAspects = aspects.isEmpty ? Array(repeating: 16.0 / 9.0, count: urls.count) : aspects
        let spec = MergeLayout.gridSpec(n: urls.count, aspects: safeAspects,
                                        gridCellSize: options.gridCellSize)
        let rects = spec.rects()
        let cells: [PreviewCell] = urls.indices.compactMap { i in
            guard i < rects.count else { return nil }
            return PreviewCell(url: urls[i], rect: rects[i], rotation: 0)
        }
        return PreviewLayout(outW: spec.outW, outH: spec.outH, cells: cells)

    case .collage:
        let layout = MergeLayout.collageLayout(
            n: urls.count,
            canvasW: options.canvasWidth.toAligned16,
            canvasH: options.canvasHeight.toAligned16,
            mainRatio: options.collageMainRatio,
            orient: options.collageOrient.rawValue,
            gap: options.gap,
            mainIdx: options.collageMainIndex
        )
        let rects = layout.rects()
        let cells = urls.indices.map { PreviewCell(url: urls[$0], rect: rects[$0], rotation: 0) }
        return PreviewLayout(outW: layout.outW, outH: layout.outH, cells: cells)

    case .photoWall:
        let wall = MergeLayout.photoWallLayout(
            n: urls.count,
            canvasW: options.canvasWidth.toAligned16,
            canvasH: options.canvasHeight.toAligned16,
            seed: options.photoWallSeed
        )
        let cells = urls.indices.map {
            PreviewCell(url: urls[$0], rect: wall.rects[$0], rotation: wall.rotations[$0])
        }
        return PreviewLayout(outW: wall.outW, outH: wall.outH, cells: cells)
    }
}

// MARK: - 3. 参数

private struct OptionsSection: View {
    let mergeType: MergeType
    let options: MergeOptions
    let onOptionsChanged: (MergeOptions) -> Void

    @State private var canvasExpanded = false

    private let presets: [(String, Int, Int)] = [
        ("1920×1080", 1920, 1080),
        ("1080×1920", 1080, 1920),
        ("1080×1080", 1080, 1080),
        ("3840×2160", 3840, 2160)
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("参数设置")
                .font(.headline)
                .fontWeight(.bold)

            // 画布尺寸
            Button {
                canvasExpanded.toggle()
            } label: {
                HStack {
                    Image(systemName: canvasExpanded ? "chevron.up" : "chevron.down")
                    Text("画布尺寸: \(options.canvasWidth)×\(options.canvasHeight)")
                    Spacer()
                }
                .foregroundColor(.primary)
            }

            if canvasExpanded {
                ForEach(presets, id: \.0) { preset in
                    Button {
                        var newOpt = options
                        newOpt.canvasWidth = preset.1
                        newOpt.canvasHeight = preset.2
                        onOptionsChanged(newOpt)
                    } label: {
                        HStack {
                            Image(systemName:
                                (options.canvasWidth == preset.1 && options.canvasHeight == preset.2)
                                ? "largecircle.fill.circle" : "circle")
                                .foregroundColor(.accentColor)
                            Text(preset.0)
                            Spacer()
                        }
                        .foregroundColor(.primary)
                        .padding(.leading, 24)
                    }
                }
            }

            // Collage 特有
            if mergeType == .collage {
                Divider().padding(.vertical, 4)

                Text("主窗口位置:").fontWeight(.medium)
                HStack(spacing: 6) {
                    ForEach(CollageOrient.allCases) { o in
                        Button {
                            var newOpt = options
                            newOpt.collageOrient = o
                            onOptionsChanged(newOpt)
                        } label: {
                            Text(o.displayName)
                                .font(.system(size: 13, weight: .medium))
                                .padding(.horizontal, 12)
                                .padding(.vertical, 6)
                                .background(options.collageOrient == o
                                            ? Color.accentColor : Color(.systemBackground))
                                .foregroundColor(options.collageOrient == o ? .white : .primary)
                                .cornerRadius(20)
                                .overlay(
                                    Capsule().stroke(Color.accentColor, lineWidth: 1)
                                )
                        }
                    }
                }

                Text("主窗口占比: \(Int(options.collageMainRatio * 100))%")
                Slider(value: Binding(
                    get: { options.collageMainRatio },
                    set: { v in
                        var newOpt = options
                        newOpt.collageMainRatio = v
                        onOptionsChanged(newOpt)
                    }
                ), in: 0.3...0.8)
            }
        }
        .padding(16)
        .background(Color(.secondarySystemBackground))
        .cornerRadius(16)
    }
}

// MARK: - 5. 进度 + 日志

private struct ProgressAndLogSection: View {
    let progress: Float
    let message: String
    let logText: String

    // 节流：避免每次 logText 变化都触发 ScrollView 重排
    @State private var displayedLog = ""
    @State private var lastUpdate: Date = .distantPast

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text(message).fontWeight(.medium)
                Spacer()
                Text("\(Int(progress * 100))%").fontWeight(.bold)
            }
            ProgressView(value: progress)
                .tint(.accentColor)

            if !displayedLog.isEmpty {
                Divider().padding(.vertical, 2)
                Text("FFmpeg 日志")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundColor(.secondary)
                ScrollView {
                    Text(displayedLog)
                        .font(.system(size: 10, design: .monospaced))
                        .foregroundColor(Color(red: 0.3, green: 0.85, blue: 0.3))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(8)
                        .textSelection(.enabled)
                }
                .frame(maxHeight: 200)
                .background(Color(red: 0.12, green: 0.12, blue: 0.12))
                .cornerRadius(8)
            }
        }
        .padding(16)
        .background(Color(.secondarySystemBackground))
        .cornerRadius(16)
        .onChange(of: logText) { newValue in
            // 节流：最多每 200ms 更新一次 UI（避免高频 log 卡死主线程）
            let now = Date()
            if now.timeIntervalSince(lastUpdate) > 0.2 || newValue.hasSuffix("\n\n") {
                displayedLog = newValue
                lastUpdate = now
            }
        }
        .onAppear {
            displayedLog = logText
        }
    }
}

// MARK: - 6. 预览

private struct PreviewSection: View {
    let result: MergeResult
    let isSaved: Bool
    let isSaving: Bool
    let onTap: () -> Void
    let onLongPress: () -> Void
    let onSaveClick: () -> Void
    let onClear: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            HStack {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundColor(.green)
                Text("合并完成！预览视频")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundColor(Color(red: 0.18, green: 0.49, blue: 0.20))
                Spacer()
                Button(action: onClear) {
                    Image(systemName: "xmark")
                        .foregroundColor(Color(red: 0.33, green: 0.55, blue: 0.18))
                }
            }
            Text("\(result.mergeType) · \(result.width)×\(result.height) · \(String(format: "%.1f", result.duration))s")
                .font(.system(size: 12))
                .foregroundColor(Color(red: 0.33, green: 0.55, blue: 0.18))

            InlineVideoPlayer(
                videoURL: result.outputFileURL,
                onTap: onTap,
                onLongPress: onLongPress
            )
            .frame(height: 220)

            // 保存按钮
            if isSaving {
                HStack {
                    ProgressView().scaleEffect(0.7)
                    Text("保存中...").foregroundColor(Color(red: 0.33, green: 0.55, blue: 0.18))
                }
            } else if isSaved {
                HStack {
                    Image(systemName: "checkmark.circle.fill").foregroundColor(.green)
                    Text("已保存到相册").foregroundColor(Color(red: 0.33, green: 0.55, blue: 0.18))
                }
            } else {
                Button(action: onSaveClick) {
                    HStack {
                        Image(systemName: "square.and.arrow.down")
                        Text("保存到相册")
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(Color.green)
                    .foregroundColor(.white)
                    .cornerRadius(12)
                }
                Text("提示: 长按视频也可保存 · 点击视频全屏播放")
                    .font(.system(size: 11))
                    .foregroundColor(.gray)
            }
        }
        .padding(16)
        .background(Color(red: 0.91, green: 0.96, blue: 0.91))
        .cornerRadius(16)
    }
}

// MARK: - 7. 错误

private struct ErrorSection: View {
    let message: String
    let onDismiss: () -> Void

    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: "xmark.octagon.fill")
                .font(.system(size: 44))
                .foregroundColor(Color(red: 0.96, green: 0.26, blue: 0.21))
            Text("出错了")
                .font(.system(size: 18, weight: .bold))
                .foregroundColor(Color(red: 0.78, green: 0.16, blue: 0.16))
            Text(message)
                .font(.system(size: 13))
                .multilineTextAlignment(.center)
                .foregroundColor(Color(red: 0.72, green: 0.11, blue: 0.11))
            Button("确定", action: onDismiss)
                .padding(.top, 4)
        }
        .frame(maxWidth: .infinity)
        .padding(20)
        .background(Color(red: 1.0, green: 0.92, blue: 0.93))
        .cornerRadius(16)
    }
}
