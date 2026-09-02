//
//  GifView.swift
//  VideoMerger
//
//  iOS 移植自 Android GifScreen.kt —— 视频转 GIF 页
//  选择视频 → 设置宽度/帧率/质量 → 转换（进度） → 动画预览 → 保存到相册
//

import SwiftUI
import ImageIO

struct GifView: View {
    @StateObject private var viewModel = GifViewModel()
    @State private var showPicker = false

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                if viewModel.uiState.sourceURL == nil {
                    emptyPickSection
                } else {
                    sourceInfoSection
                    optionsSection
                    if let url = viewModel.uiState.resultURL {
                        resultSection(url: url)
                    } else {
                        convertSection
                    }
                }
                if let msg = viewModel.uiState.errorMessage {
                    errorSection(msg)
                }
                hintSection
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .background(Color.black)
        .navigationTitle("视频转GIF")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showPicker) {
            VideoPicker(maxSelection: 1) { ids in
                // ★ @State/VM 必须在主线程更新，否则首次导航不生效
                Task { @MainActor in
                    let urls = await MediaUtils.loadVideoURLs(assetIdentifiers: ids)
                    if let url = urls.first {
                        viewModel.onVideoPicked(url: url)
                    }
                }
            }
        }
    }

    // MARK: - 空态：选择视频

    private var emptyPickSection: some View {
        Button { showPicker = true } label: {
            VStack(spacing: 10) {
                Image(systemName: "video.fill")
                    .font(.system(size: 44))
                    .foregroundColor(Color(hex: 0xFF2196F3))
                Text("点击选择视频")
                    .font(.system(size: 16, weight: .bold))
                Text("从相册选择要转换为 GIF 的视频")
                    .font(.system(size: 12))
                    .foregroundColor(Color(hex: 0xFF666666))
            }
            .frame(maxWidth: .infinity)
            .frame(height: 200)
            .background(RoundedRectangle(cornerRadius: 16).fill(Color(hex: 0xFF1A1A1A)))
        }
    }

    // MARK: - 源视频信息

    private var sourceInfoSection: some View {
        HStack(spacing: 10) {
            Image(systemName: "video.fill")
                .foregroundColor(Color(hex: 0xFF2196F3))
            VStack(alignment: .leading, spacing: 2) {
                Text(viewModel.uiState.sourceName)
                    .font(.system(size: 14, weight: .medium))
                    .lineLimit(1)
                Text(String(format: "%.1f 秒 · %d×%d",
                            viewModel.uiState.sourceDuration,
                            viewModel.uiState.sourceWidth,
                            viewModel.uiState.sourceHeight))
                    .font(.system(size: 12))
                    .foregroundColor(Color(hex: 0xFF888888))
            }
            Spacer()
            Button("重新选择") { showPicker = true }
                .font(.system(size: 13))
                .disabled(viewModel.uiState.isConverting)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(RoundedRectangle(cornerRadius: 16).fill(Color(hex: 0xFF1A1A1A)))
    }

    // MARK: - 参数设置

    private var optionsSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("参数设置")
                .font(.system(size: 15, weight: .bold))

            chipRow(title: "宽度",
                    options: [240, 360, 480, 720],
                    isSelected: { $0 == viewModel.uiState.targetWidth },
                    label: { "\($0)px" },
                    enabled: !viewModel.uiState.isConverting) {
                viewModel.updateTargetWidth($0)
            }
            chipRow(title: "帧率",
                    options: [8, 10, 15],
                    isSelected: { $0 == viewModel.uiState.fps },
                    label: { "\($0)fps" },
                    enabled: !viewModel.uiState.isConverting) {
                viewModel.updateFps($0)
            }
            chipRow(title: "质量",
                    options: Array(GifQuality.allCases),
                    isSelected: { $0 == viewModel.uiState.quality },
                    label: { $0.rawValue },
                    enabled: !viewModel.uiState.isConverting) {
                viewModel.updateQuality($0)
            }

            HStack {
                Text("循环播放").font(.system(size: 13))
                Spacer()
                Toggle("", isOn: Binding(
                    get: { viewModel.uiState.loop },
                    set: { viewModel.updateLoop($0) }
                ))
                .labelsHidden()
                .disabled(viewModel.uiState.isConverting)
            }
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 16).fill(Color(hex: 0xFF1A1A1A)))
    }

    private func chipRow<T: Hashable>(title: String,
                                      options: [T],
                                      isSelected: @escaping (T) -> Bool,
                                      label: @escaping (T) -> String,
                                      enabled: Bool,
                                      onSelect: @escaping (T) -> Void) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.system(size: 13))
                .foregroundColor(Color(hex: 0xFF888888))
            HStack(spacing: 8) {
                ForEach(options, id: \.self) { option in
                    Button {
                        onSelect(option)
                    } label: {
                        Text(label(option))
                            .font(.system(size: 13))
                            .padding(.horizontal, 14).padding(.vertical, 7)
                            .background(RoundedRectangle(cornerRadius: 16).fill(
                                isSelected(option)
                                    ? Color(hex: 0xFF2196F3)
                                    : Color(hex: 0xFF2A2A2A)))
                            .foregroundColor(isSelected(option) ? .white : Color(hex: 0xFFBBBBBB))
                    }
                    .disabled(!enabled)
                }
                Spacer()
            }
        }
    }

    // MARK: - 转换按钮 / 进度

    private var convertSection: some View {
        VStack(spacing: 12) {
            if viewModel.uiState.isConverting {
                HStack {
                    Text("转换中...").font(.system(size: 14, weight: .medium))
                    Spacer()
                    Text("\(Int(viewModel.uiState.progress * 100))%").bold()
                }
                ProgressView(value: viewModel.uiState.progress)
                    .tint(Color(hex: 0xFF2196F3))
                Button {
                    viewModel.cancelConvert()
                } label: {
                    Text("取消")
                        .font(.system(size: 14))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color(hex: 0xFF555555)))
                }
            } else {
                Button {
                    viewModel.convert()
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: "photo.stack")
                        Text("生成 GIF").font(.system(size: 16, weight: .medium))
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(RoundedRectangle(cornerRadius: 8).fill(Color(hex: 0xFF2196F3)))
                    .foregroundColor(.white)
                }
            }
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 16).fill(Color(hex: 0xFF1A1A1A)))
    }

    // MARK: - 结果：动画预览 + 保存

    private func resultSection(url: URL) -> some View {
        let dims = GifCommandBuilder.targetDimensions(
            sourceWidth: viewModel.uiState.sourceWidth,
            sourceHeight: viewModel.uiState.sourceHeight,
            targetWidth: viewModel.uiState.targetWidth
        )
        let sizeText: String = {
            if let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
               let bytes = attrs[.size] as? Int64 {
                return bytes >= 1024 * 1024
                    ? String(format: "%.1fMB", Double(bytes) / 1024 / 1024)
                    : String(format: "%.0fKB", Double(bytes) / 1024)
            }
            return ""
        }()

        return VStack(spacing: 12) {
            HStack(spacing: 8) {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundColor(Color(hex: 0xFF4CAF50))
                Text("GIF 生成成功")
                    .font(.system(size: 16, weight: .bold))
                Spacer()
                Button {
                    viewModel.clearResult()
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 12))
                        .foregroundColor(Color(hex: 0xFF999999))
                }
            }

            Text("\(dims.width)×\(dims.height) · \(viewModel.uiState.fps)fps · \(sizeText)")
                .font(.system(size: 12))
                .foregroundColor(Color(hex: 0xFF888888))
                .frame(maxWidth: .infinity, alignment: .center)

            GifAnimatedPreview(url: url)
                .frame(maxWidth: .infinity)
                .frame(height: 220)
                .background(RoundedRectangle(cornerRadius: 10).fill(Color(hex: 0xFF111111)))
                .clipShape(RoundedRectangle(cornerRadius: 10))

            if viewModel.uiState.isSaving {
                HStack(spacing: 8) {
                    ProgressView().scaleEffect(0.8)
                    Text("保存中...").font(.system(size: 13))
                        .foregroundColor(Color(hex: 0xFF4CAF50))
                }
            } else if viewModel.uiState.isSaved {
                HStack(spacing: 6) {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 14))
                        .foregroundColor(Color(hex: 0xFF4CAF50))
                    Text("已保存到系统相册")
                        .font(.system(size: 13))
                        .foregroundColor(Color(hex: 0xFF4CAF50))
                }
            } else {
                Button {
                    viewModel.saveResult()
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: "square.and.arrow.down")
                        Text("保存到相册").font(.system(size: 15, weight: .medium))
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(RoundedRectangle(cornerRadius: 8).fill(Color(hex: 0xFF4CAF50)))
                    .foregroundColor(.white)
                }
            }
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 16).fill(Color(hex: 0xFF1A1A1A)))
    }

    // MARK: - 错误 / 提示

    private func errorSection(_ msg: String) -> some View {
        VStack(spacing: 8) {
            Text(msg)
                .font(.system(size: 13))
                .foregroundColor(Color(hex: 0xFFE57373))
                .multilineTextAlignment(.center)
            Button("确定") { viewModel.dismissError() }
                .font(.system(size: 14))
        }
        .frame(maxWidth: .infinity)
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 16).fill(Color(hex: 0xFF2A1215)))
    }

    private var hintSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("小贴士").font(.system(size: 14, weight: .bold))
            ForEach([
                "• 宽度越小、帧率越低，GIF 体积越小、生成越快",
                "• 「高质量」使用 Bayer 抖动，色彩过渡更平滑",
                "• GIF 不含声音，太长的视频建议先在编辑器里裁剪",
            ], id: \.self) { tip in
                Text(tip)
                    .font(.system(size: 12))
                    .foregroundColor(Color(hex: 0xFF888888))
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 16).fill(Color(hex: 0xFF1A1A1A)))
    }
}

// MARK: - GIF 动画预览（ImageIO 解帧 + TimelineView 逐帧播放）

struct GifAnimatedPreview: View {
    let url: URL

    @State private var frames: [UIImage] = []
    @State private var delays: [Double] = []      // 每帧时长（秒）
    @State private var totalDuration: Double = 0

    /// 预览解码上限：限制内存（GIF 帧数可能很大）
    private static let maxFrames = 200
    private static let maxPixelSize: CGFloat = 720

    var body: some View {
        Group {
            if frames.isEmpty {
                Image(systemName: "photo")
                    .font(.system(size: 36))
                    .foregroundColor(Color(hex: 0xFF777777))
            } else if totalDuration <= 0 || delays.count <= 1 {
                Image(uiImage: frames[0])
                    .resizable().scaledToFit()
            } else {
                TimelineView(.animation) { timeline in
                    Image(uiImage: frame(at: timeline.date.timeIntervalSinceReferenceDate))
                        .resizable().scaledToFit()
                }
            }
        }
        .task { decodeFrames() }
    }

    private func frame(at time: Double) -> UIImage {
        var t = time.truncatingRemainder(dividingBy: totalDuration)
        for (i, d) in delays.enumerated() {
            if t < d { return frames[i] }
            t -= d
        }
        return frames[frames.count - 1]
    }

    private func decodeFrames() {
        guard frames.isEmpty,
              let src = CGImageSourceCreateWithURL(url as CFURL, nil) else { return }
        let count = min(CGImageSourceGetCount(src), Self.maxFrames)
        var imgs: [UIImage] = []
        var ds: [Double] = []
        imgs.reserveCapacity(count)
        ds.reserveCapacity(count)

        let thumbOpts: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: Self.maxPixelSize,
        ]
        for i in 0..<count {
            if let cg = CGImageSourceCreateThumbnailAtIndex(src, i, thumbOpts as CFDictionary) {
                imgs.append(UIImage(cgImage: cg))
                ds.append(frameDelay(src: src, index: i))
            }
        }
        frames = imgs
        delays = ds
        totalDuration = ds.reduce(0, +)
    }

    private func frameDelay(src: CGImageSource, index: Int) -> Double {
        var delay = 0.1
        if let props = CGImageSourceCopyPropertiesAtIndex(src, index, nil) as? [String: Any],
           let gif = props[kCGImagePropertyGIFDictionary as String] as? [String: Any] {
            if let d = gif[kCGImagePropertyGIFUnclampedDelayTime as String] as? Double, d > 0 {
                delay = d
            } else if let d = gif[kCGImagePropertyGIFDelayTime as String] as? Double, d > 0 {
                delay = d
            }
        }
        return delay
    }
}
