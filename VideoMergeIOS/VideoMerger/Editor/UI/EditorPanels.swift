//
//  EditorPanels.swift
//  VideoMerger
//
//  iOS 移植自 Android ToolPanels.kt：剪辑/变速/滤镜/文字/音频/转场/去水印/导出面板
//

import SwiftUI

// MARK: - 面板通用外壳

private struct PanelShell<Content: View>: View {
    let title: String
    let onClose: () -> Void
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(title).font(.system(size: 16, weight: .semibold))
                Spacer()
                Button(action: onClose) {
                    Image(systemName: "xmark").font(.system(size: 14))
                }
            }
            content
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(hex: 0xFF1A1A1A))
    }
}

private func panelLabel(_ text: String) -> some View {
    Text(text).font(.system(size: 12)).foregroundColor(Color(hex: 0xFF888888))
}

// MARK: - 剪辑面板

struct TrimPanelView: View {
    let clip: Clip?
    @ObservedObject var vm: EditorViewModel
    let onClose: () -> Void

    var body: some View {
        PanelShell(title: "剪辑", onClose: onClose) {
            if let clip = clip {
                Group {
                    HStack {
                        Text("入点").font(.system(size: 13)).foregroundColor(Color(hex: 0xFFAAAAAA))
                        Slider(value: Binding(
                            get: { clip.trimStart },
                            set: { vm.beginEdit(); vm.updateTrim(clip.id, $0, clip.trimEnd) }
                        ), in: 0...max(clip.mediaDuration - 0.1, 0.1))
                        Text(String(format: "%.2f", clip.trimStart))
                            .font(.system(size: 12)).frame(width: 48)
                    }
                    HStack {
                        Text("出点").font(.system(size: 13)).foregroundColor(Color(hex: 0xFFAAAAAA))
                        Slider(value: Binding(
                            get: { clip.trimEnd > 0 ? clip.trimEnd : clip.mediaDuration },
                            set: { vm.beginEdit(); vm.updateTrim(clip.id, clip.trimStart, $0) }
                        ), in: 0.1...clip.mediaDuration)
                        Text(String(format: "%.2f", clip.trimEnd > 0 ? clip.trimEnd : clip.mediaDuration))
                            .font(.system(size: 12)).frame(width: 48)
                    }
                    // 旋转 / 翻转
                    HStack(spacing: 12) {
                        Text("变换").font(.system(size: 13)).foregroundColor(Color(hex: 0xFFAAAAAA))
                        ForEach([RotationMode.none, .cw90, .ccw90, .r180], id: \.self) { m in
                            Text(m == .none ? "原图" : m.displayName.replacingOccurrences(of: "°", with: ""))
                                .font(.system(size: 12))
                                .foregroundColor(clip.rotation == m.rawValue ? Color(hex: 0xFF2196F3) : Color(hex: 0xFF888888))
                                .padding(.horizontal, 8).padding(.vertical, 4)
                                .background(Capsule().fill(Color(hex: 0xFF242424)))
                                .onTapGesture { vm.beginEdit(); vm.setRotation(clip.id, m.rawValue) }
                        }
                    }
                    HStack(spacing: 12) {
                        Toggle("水平镜像", isOn: Binding(
                            get: { clip.hflip },
                            set: { _ in vm.beginEdit(); vm.toggleHFlip(clip.id) }))
                            .font(.system(size: 13))
                        Toggle("垂直镜像", isOn: Binding(
                            get: { clip.vflip },
                            set: { _ in vm.beginEdit(); vm.toggleVFlip(clip.id) }))
                            .font(.system(size: 13))
                    }
                    // ── 片尾静止 logo/标语检测截断（对齐 Android：单片段 + 全部片段）──
                    VStack(alignment: .leading, spacing: 6) {
                        Text("片尾静止片段").font(.system(size: 13))
                        Text("检测并去掉视频末尾静止的 logo/标语画面")
                            .font(.system(size: 11)).foregroundColor(Color(hex: 0xFF666666))
                        HStack(spacing: 8) {
                            detectButton(title: "当前片段", busy: vm.uiState.isDetectingLogo) {
                                vm.detectTailLogo(clip.id)
                            }
                            detectButton(title: "全部片段", busy: vm.uiState.isDetectingLogo) {
                                vm.detectTailLogoAll()
                            }
                            Spacer()
                        }
                    }
                }
            } else {
                panelLabel("请先选中片段")
            }
        }
    }

    /// 片尾检测按钮：检测中显示转圈并禁用，避免重复触发
    private func detectButton(title: String, busy: Bool, action: @escaping () -> Void) -> some View {
        Button {
            action()
        } label: {
            HStack(spacing: 6) {
                if busy { ProgressView().scaleEffect(0.6) }
                Text(busy ? "检测中..." : title).font(.system(size: 12))
            }
            .padding(.horizontal, 12).padding(.vertical, 6)
            .background(RoundedRectangle(cornerRadius: 6).fill(Color(hex: 0xFF2196F3)))
            .foregroundColor(.white)
        }
        .disabled(busy)
    }
}

// MARK: - 变速面板

struct SpeedPanelView: View {
    let clip: Clip?
    @ObservedObject var vm: EditorViewModel
    let onClose: () -> Void

    var body: some View {
        PanelShell(title: "变速", onClose: onClose) {
            if let clip = clip {
                HStack {
                    Text("速度").font(.system(size: 13)).foregroundColor(Color(hex: 0xFFAAAAAA))
                    Slider(value: Binding(
                        get: { clip.speed },
                        set: { vm.beginEdit(); vm.updateSpeed(clip.id, $0) }
                    ), in: 0.25...4.0)
                    Text(String(format: "%.2fx", clip.speed)).font(.system(size: 12)).frame(width: 48)
                }
                Toggle("倒放", isOn: Binding(
                    get: { clip.reversed },
                    set: { _ in vm.beginEdit(); vm.toggleReverse(clip.id) }))
                    .font(.system(size: 13))
            } else {
                panelLabel("请先选中片段")
            }
        }
    }
}

// MARK: - 滤镜面板

struct FilterPanelView: View {
    let clip: Clip?
    @ObservedObject var vm: EditorViewModel
    let onClose: () -> Void

    var body: some View {
        PanelShell(title: "滤镜", onClose: onClose) {
            if let clip = clip {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(FilterPreset.allCases, id: \.self) { preset in
                            Text(preset.displayName)
                                .font(.system(size: 12))
                                .foregroundColor(clip.filterPreset == preset ? .white : Color(hex: 0xFF888888))
                                .padding(.horizontal, 12).padding(.vertical, 6)
                                .background(RoundedRectangle(cornerRadius: 8).fill(
                                    clip.filterPreset == preset ? Color(hex: 0xFF2196F3) : Color(hex: 0xFF242424)))
                                .onTapGesture { vm.beginEdit(); vm.setFilterPreset(clip.id, preset) }
                        }
                    }
                }
                Group {
                    slider("亮度", value: clip.brightness) { vm.beginEdit(); vm.updateColorParams(clip.id, $0, clip.contrast, clip.saturation) }
                    slider("对比度", value: clip.contrast) { vm.beginEdit(); vm.updateColorParams(clip.id, clip.brightness, $0, clip.saturation) }
                    slider("饱和度", value: clip.saturation) { vm.beginEdit(); vm.updateColorParams(clip.id, clip.brightness, clip.contrast, $0) }
                }
            } else {
                panelLabel("请先选中片段")
            }
        }
    }

    private func slider(_ label: String, value: Double, onChange: @escaping (Double) -> Void) -> some View {
        HStack {
            Text(label).font(.system(size: 13)).foregroundColor(Color(hex: 0xFFAAAAAA)).frame(width: 52, alignment: .leading)
            Slider(value: Binding(get: { value }, set: { vm.beginEdit(); onChange($0) }), in: -1...1)
            Text(String(format: "%.2f", value)).font(.system(size: 12)).frame(width: 44)
        }
    }
}

// MARK: - 文字水印面板

struct TextPanelView: View {
    let clip: Clip?
    @ObservedObject var vm: EditorViewModel
    let onClose: () -> Void
    @State private var text: String = ""

    var body: some View {
        PanelShell(title: "文字水印", onClose: onClose) {
            if let clip = clip {
                TextField("输入水印文字", text: Binding(
                    get: { clip.textOverlay ?? "" },
                    set: { vm.setTextOverlay(clip.id, $0.isEmpty ? nil : $0) }
                ))
                .textFieldStyle(.roundedBorder)
                .font(.system(size: 14))

                HStack {
                    Text("字号").font(.system(size: 13)).foregroundColor(Color(hex: 0xFFAAAAAA)).frame(width: 52, alignment: .leading)
                    Slider(value: Binding(
                        get: { Double(clip.textSize) },
                        set: { vm.beginEdit(); vm.setTextStyle(clip.id, size: Int($0), color: clip.textColor, position: clip.textPosition, opacity: clip.textOpacity, border: clip.textBorder) }
                    ), in: 12...120)
                    Text("\(clip.textSize)").font(.system(size: 12)).frame(width: 32)
                }
                HStack {
                    Text("位置").font(.system(size: 13)).foregroundColor(Color(hex: 0xFFAAAAAA)).frame(width: 52, alignment: .leading)
                    ForEach(["top-left", "center", "bottom-right"], id: \.self) { pos in
                        Text(pos == "top-left" ? "上" : pos == "center" ? "中" : "下右")
                            .font(.system(size: 12))
                            .foregroundColor(clip.textPosition == pos ? Color(hex: 0xFF2196F3) : Color(hex: 0xFF888888))
                            .padding(.horizontal, 10).padding(.vertical, 4)
                            .background(Capsule().fill(Color(hex: 0xFF242424)))
                            .onTapGesture {
                                vm.beginEdit()
                                vm.setTextStyle(clip.id, size: clip.textSize, color: clip.textColor,
                                                position: pos, opacity: clip.textOpacity, border: clip.textBorder)
                            }
                    }
                    Toggle("描边", isOn: Binding(
                        get: { clip.textBorder },
                        set: { nv, _ in vm.beginEdit(); vm.setTextStyle(clip.id, size: clip.textSize, color: clip.textColor,
                                                               position: clip.textPosition, opacity: clip.textOpacity, border: nv) }))
                        .font(.system(size: 13))
                }
            } else {
                panelLabel("请先选中片段")
            }
        }
    }
}

// MARK: - 音频面板

struct AudioPanelView: View {
    let clip: Clip?
    @ObservedObject var vm: EditorViewModel
    let onClose: () -> Void

    var body: some View {
        PanelShell(title: "音频", onClose: onClose) {
            if let clip = clip {
                if !clip.hasAudio {
                    panelLabel("该片段没有音轨")
                } else {
                    HStack {
                        Text("音量").font(.system(size: 13)).foregroundColor(Color(hex: 0xFFAAAAAA)).frame(width: 52, alignment: .leading)
                        Slider(value: Binding(
                            get: { clip.volume },
                            set: { vm.beginEdit(); vm.updateVolume(clip.id, $0) }
                        ), in: 0...3)
                        Text(String(format: "%.1f", clip.volume)).font(.system(size: 12)).frame(width: 36)
                    }
                    HStack {
                        Text("淡入").font(.system(size: 13)).foregroundColor(Color(hex: 0xFFAAAAAA)).frame(width: 52, alignment: .leading)
                        Slider(value: Binding(
                            get: { clip.audioFadeIn },
                            set: { vm.beginEdit(); vm.updateAudioFade(clip.id, $0, clip.audioFadeOut) }
                        ), in: 0...max(clip.timelineDuration / 2, 0.1))
                        Text(String(format: "%.1fs", clip.audioFadeIn)).font(.system(size: 12)).frame(width: 44)
                    }
                    HStack {
                        Text("淡出").font(.system(size: 13)).foregroundColor(Color(hex: 0xFFAAAAAA)).frame(width: 52, alignment: .leading)
                        Slider(value: Binding(
                            get: { clip.audioFadeOut },
                            set: { vm.beginEdit(); vm.updateAudioFade(clip.id, clip.audioFadeIn, $0) }
                        ), in: 0...max(clip.timelineDuration / 2, 0.1))
                        Text(String(format: "%.1fs", clip.audioFadeOut)).font(.system(size: 12)).frame(width: 44)
                    }
                    HStack {
                        Text("变声").font(.system(size: 13)).foregroundColor(Color(hex: 0xFFAAAAAA)).frame(width: 52, alignment: .leading)
                        Slider(value: Binding(
                            get: { clip.pitchShift },
                            set: { vm.beginEdit(); vm.updatePitchShift(clip.id, $0) }
                        ), in: 0.5...2.0)
                        Text(clip.pitchShift == 1.0 ? "原声" : (clip.pitchShift > 1 ? "高音" : "低音"))
                            .font(.system(size: 12)).frame(width: 40)
                    }
                    Toggle("降噪", isOn: Binding(
                        get: { clip.noiseReduction },
                        set: { nv, _ in vm.beginEdit(); if nv != clip.noiseReduction { vm.toggleNoiseReduction(clip.id) } }))
                        .font(.system(size: 13))
                }
            } else {
                panelLabel("请先选中片段")
            }
        }
    }
}

// MARK: - 转场面板

struct TransitionPanelView: View {
    let clip: Clip?
    @ObservedObject var vm: EditorViewModel
    let onClose: () -> Void

    private let effects: [TransitionEffect] = [
        .none, .fade, .wipeleft, .wiperight, .wipeup, .wipedown,
        .slideleft, .slideright, .slideup, .slidedown,
        .circleopen, .circleclose, .dissolve, .fadewhite, .fadeblack,
    ]

    var body: some View {
        PanelShell(title: "转场", onClose: onClose) {
            if let clip = clip {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 72), spacing: 8)], spacing: 8) {
                    ForEach(effects, id: \.self) { effect in
                        Text(effect.displayName)
                            .font(.system(size: 12))
                            .lineLimit(1)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 8)
                            .background(RoundedRectangle(cornerRadius: 8).fill(
                                clip.transition == effect ? Color(hex: 0xFF2196F3) : Color(hex: 0xFF242424)))
                            .onTapGesture {
                                vm.beginEdit()
                                vm.setTransition(clip.id, effect)
                            }
                    }
                }
                if clip.transition != .none {
                    HStack {
                        Text("时长").font(.system(size: 13)).foregroundColor(Color(hex: 0xFFAAAAAA))
                        Slider(value: Binding(
                            get: { clip.transitionDuration },
                            set: { vm.beginEdit(); vm.updateTransitionDuration(clip.id, $0) }
                        ), in: 0.2...3.0)
                        Text(String(format: "%.1fs", clip.transitionDuration)).font(.system(size: 12)).frame(width: 40)
                    }
                }
                panelLabel("转场时长会缩短总时长（片段重叠播放），导出与时间轴一致")
            } else {
                panelLabel("请先选中片段")
            }
        }
    }
}

// MARK: - 去水印面板

struct WatermarkRemovePanelView: View {
    let clip: Clip?
    @ObservedObject var vm: EditorViewModel
    let onClose: () -> Void

    var body: some View {
        PanelShell(title: "去水印", onClose: onClose) {
            if let clip = clip {
                Text("框选画面中的水印位置（预览红框标识），导出时对该区域做模糊/马赛克覆盖。\n" +
                     "自动检测识别静态水印（如平台 logo/昵称），检测不到可手动添加。")
                    .font(.system(size: 11)).foregroundColor(Color(hex: 0xFF888888))

                HStack(spacing: 12) {
                    Button {
                        vm.autoDetectWatermarks(clip.id)
                    } label: {
                        HStack(spacing: 6) {
                            if vm.uiState.isDetectingWatermark { ProgressView().scaleEffect(0.6) }
                            Text(vm.uiState.isDetectingWatermark ? "检测中..." : "自动检测水印")
                                .font(.system(size: 13))
                        }
                        .padding(.horizontal, 12).padding(.vertical, 6)
                        .background(RoundedRectangle(cornerRadius: 6).fill(Color(hex: 0xFF2196F3)))
                        .foregroundColor(.white)
                    }
                    .disabled(vm.uiState.isDetectingWatermark)

                    Button { vm.beginEdit(); vm.addWatermarkRegion(clip.id) } label: {
                        Text("手动添加区域").font(.system(size: 13))
                    }
                    .buttonStyle(.borderedProminent)
                }

                HStack(spacing: 8) {
                    Text("常用水印位置：")
                        .font(.system(size: 11)).foregroundColor(Color(hex: 0xFF888888))
                    ForEach(["tl", "tr", "bl", "br"], id: \.self) { corner in
                        Text(corner == "tl" ? "左上" : corner == "tr" ? "右上" : corner == "bl" ? "左下" : "右下")
                            .font(.system(size: 12)).foregroundColor(Color(hex: 0xFF2196F3))
                            .onTapGesture { vm.beginEdit(); vm.addWatermarkRegion(clip.id, corner: corner) }
                    }
                }

                ForEach(Array(clip.watermarkRegions.enumerated()), id: \.offset) { index, region in
                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            Text("区域 \(index + 1)").font(.system(size: 13))
                            Spacer()
                            Text("模糊").font(.system(size: 12))
                                .foregroundColor(region.mode == .blur ? Color(hex: 0xFF2196F3) : Color(hex: 0xFF888888))
                                .onTapGesture { vm.beginEdit(); vm.updateWatermarkRegion(clip.id, index, region) }
                            Text("马赛克").font(.system(size: 12))
                                .foregroundColor(region.mode == .mosaic ? Color(hex: 0xFF2196F3) : Color(hex: 0xFF888888))
                                .onTapGesture {
                                    vm.beginEdit()
                                    var r = region; r.mode = .mosaic
                                    vm.updateWatermarkRegion(clip.id, index, r)
                                }
                            Image(systemName: "trash").font(.system(size: 13)).foregroundColor(Color(hex: 0xFFFF7043))
                                .onTapGesture { vm.beginEdit(); vm.removeWatermarkRegion(clip.id, index) }
                        }
                        wmSlider("位置X", value: region.x) { vm.updateWatermarkRegion(clip.id, index, regionWith(region, x: $0)) }
                        wmSlider("位置Y", value: region.y) { vm.updateWatermarkRegion(clip.id, index, regionWith(region, y: $0)) }
                        wmSlider("宽度", value: region.w) { vm.updateWatermarkRegion(clip.id, index, regionWith(region, w: $0)) }
                        wmSlider("高度", value: region.h) { vm.updateWatermarkRegion(clip.id, index, regionWith(region, h: $0)) }
                        wmSlider("强度", value: Double(region.strength), range: 1...40, format: "%.0f") {
                            vm.updateWatermarkRegion(clip.id, index, regionWith(region, strength: Int($0)))
                        }
                        HStack {
                            Text("时间段").font(.system(size: 12)).foregroundColor(Color(hex: 0xFF888888))
                            Text(region.endTime <= region.startTime
                                 ? "整段生效"
                                 : String(format: "%.1fs - %.1fs", region.startTime, region.endTime))
                                .font(.system(size: 12))
                        }
                    }
                    .padding(8)
                    .background(RoundedRectangle(cornerRadius: 8).fill(Color(hex: 0xFF242424)))
                }
            } else {
                panelLabel("请先选中片段")
            }
        }
    }

    private func regionWith(_ region: WatermarkRegion,
                            x: Double? = nil, y: Double? = nil, w: Double? = nil, h: Double? = nil,
                            strength: Int? = nil) -> WatermarkRegion {
        var r = region
        if let v = x { r.x = v }
        if let v = y { r.y = v }
        if let v = w { r.w = v }
        if let v = h { r.h = v }
        if let v = strength { r.strength = v }
        return r
    }

    private func wmSlider(_ label: String, value: Double, range: ClosedRange<Double> = 0...0.99,
                          format: String = "%.2f", onChange: @escaping (Double) -> Void) -> some View {
        HStack {
            Text(label).font(.system(size: 12)).foregroundColor(Color(hex: 0xFFAAAAAA)).frame(width: 44, alignment: .leading)
            Slider(value: Binding(get: { value }, set: { vm.beginEdit(); onChange($0) }), in: range)
            Text(String(format: format, value)).font(.system(size: 11)).frame(width: 40)
        }
    }
}

// MARK: - 导出面板

struct ExportPanelView: View {
    let state: EditorUiState
    @ObservedObject var vm: EditorViewModel
    let onClose: () -> Void

    var body: some View {
        PanelShell(title: "导出", onClose: onClose) {
            Text("项目: \(state.project.name)").font(.system(size: 14))
            Text("画布: \(state.project.canvasWidth)x\(state.project.canvasHeight)")
                .font(.system(size: 12)).foregroundColor(Color(hex: 0xFF888888))
            Text(String(format: "时长: %.2fs", state.project.totalDuration))
                .font(.system(size: 12)).foregroundColor(Color(hex: 0xFF888888))
            Text("片段数: \(state.project.mainTrack?.clips.count ?? 0)")
                .font(.system(size: 12)).foregroundColor(Color(hex: 0xFF888888))

            if state.isExporting {
                ProgressView(value: Double(state.exportProgress)).tint(Color(hex: 0xFF2196F3))
                Text(state.exportMessage).font(.system(size: 12)).foregroundColor(Color(hex: 0xFF888888))
                Button("取消导出") { vm.cancelExport() }
                    .foregroundColor(Color(hex: 0xFFFF7043))
            } else {
                Button {
                    vm.export()
                } label: {
                    Text("导出视频")
                        .font(.system(size: 15, weight: .bold))
                        .frame(maxWidth: .infinity).frame(height: 44)
                }
                .buttonStyle(.borderedProminent)
            }

            if let path = state.outputPath {
                Text("✅ 已导出: \(path)").font(.system(size: 11)).foregroundColor(Color(hex: 0xFF4CAF50))
            }
        }
    }
}

// MARK: - 画中画面板

struct PicturePanelView: View {
    let clip: Clip?
    @ObservedObject var vm: EditorViewModel
    let onClose: () -> Void
    let onPickVideo: () -> Void
    let onPickImage: () -> Void

    var body: some View {
        PanelShell(title: "画中画", onClose: onClose) {
            if let clip = clip, clip.pipEnabled {
                Group {
                    slider("位置X", clip.pipX, 0...0.99) { vm.beginEdit(); vm.updatePipTransform(clip.id, x: $0, y: clip.pipY, width: clip.pipWidth, opacity: clip.pipOpacity) }
                    slider("位置Y", clip.pipY, 0...0.99) { vm.beginEdit(); vm.updatePipTransform(clip.id, x: clip.pipX, y: $0, width: clip.pipWidth, opacity: clip.pipOpacity) }
                    slider("大小", clip.pipWidth, 0.05...1) { vm.beginEdit(); vm.updatePipTransform(clip.id, x: clip.pipX, y: clip.pipY, width: $0, opacity: clip.pipOpacity) }
                    slider("透明度", clip.pipOpacity, 0...1) { vm.beginEdit(); vm.updatePipTransform(clip.id, x: clip.pipX, y: clip.pipY, width: clip.pipWidth, opacity: $0) }
                    HStack(spacing: 12) {
                        Text("形状").font(.system(size: 13)).foregroundColor(Color(hex: 0xFF888888))
                        ForEach([PipShape.rect, .rounded, .circle], id: \.self) { shape in
                            Text(shape == .rect ? "矩形" : shape == .rounded ? "圆角" : "圆形")
                                .font(.system(size: 12))
                                .foregroundColor(clip.pipShape == shape ? Color(hex: 0xFF2196F3) : Color(hex: 0xFF888888))
                                .padding(.horizontal, 8).padding(.vertical, 4)
                                .background(Capsule().fill(Color(hex: 0xFF242424)))
                                .onTapGesture {
                                    vm.beginEdit()
                                    vm.updatePipStyle(clip.id, shape: shape, cornerRadius: clip.pipCornerRadius,
                                                      border: clip.pipBorder, borderWidth: clip.pipBorderWidth)
                                }
                        }
                        Toggle("描边", isOn: Binding(
                            get: { clip.pipBorder },
                            set: { nv, _ in vm.beginEdit(); vm.updatePipStyle(clip.id, shape: clip.pipShape,
                                                       cornerRadius: clip.pipCornerRadius, border: nv, borderWidth: clip.pipBorderWidth) }))
                            .font(.system(size: 13))
                    }
                    slider("开始", clip.timelineStart, 0...max(vm.uiState.project.totalDuration, 0.5), fmt: "%.1f") {
                        vm.beginEdit(); vm.updatePipTiming(clip.id, start: $0, duration: clip.timelineDuration)
                    }
                    slider("时长", clip.timelineDuration, 0.5...max(clip.mediaDuration / max(clip.speed, 0.1), 0.6), fmt: "%.1f") {
                        vm.beginEdit(); vm.updatePipTiming(clip.id, start: clip.timelineStart, duration: $0)
                    }

                    // 位置关键帧
                    HStack {
                        Text("关键帧").font(.system(size: 13)).foregroundColor(Color(hex: 0xFFAAAAAA))
                        Button("在播放头添加") {
                            vm.beginEdit(); vm.addPipKeyframe(clip.id)
                        }
                        .font(.system(size: 12))
                        Spacer()
                    }
                    ForEach(Array(clip.pipKeyframes.enumerated()), id: \.offset) { index, kf in
                        HStack {
                            Text(String(format: "@%.1fs (%.2f, %.2f)", kf.time, kf.x, kf.y))
                                .font(.system(size: 12)).foregroundColor(Color(hex: 0xFFAAAAAA))
                            Spacer()
                            Image(systemName: "trash")
                                .font(.system(size: 12)).foregroundColor(Color(hex: 0xFFFF7043))
                                .onTapGesture { vm.beginEdit(); vm.removePipKeyframe(clip.id, index) }
                        }
                    }

                    Button("删除此画中画", role: .destructive) {
                        if let id = vm.uiState.selectedClipId { vm.deleteClip(id) }
                        onClose()
                    }
                    .font(.system(size: 13))
                }
            } else {
                VStack(alignment: .leading, spacing: 12) {
                    Text("把视频或图片叠加在主画面之上").font(.system(size: 12)).foregroundColor(Color(hex: 0xFF888888))
                    HStack(spacing: 12) {
                        Button("选择视频") { onPickVideo() }
                            .buttonStyle(.borderedProminent)
                        Button("选择图片") { onPickImage() }
                            .buttonStyle(.bordered)
                    }
                }
            }
        }
    }

    private func slider(_ label: String, _ value: Double, _ range: ClosedRange<Double>,
                        fmt: String = "%.2f", onChange: @escaping (Double) -> Void) -> some View {
        HStack {
            Text(label).font(.system(size: 13)).foregroundColor(Color(hex: 0xFFAAAAAA)).frame(width: 48, alignment: .leading)
            Slider(value: Binding(get: { value }, set: { vm.beginEdit(); onChange($0) }), in: range)
            Text(String(format: fmt, value)).font(.system(size: 12)).frame(width: 44)
        }
    }
}

// MARK: - 贴纸面板

struct StickerPanelView: View {
    @ObservedObject var vm: EditorViewModel
    let onClose: () -> Void

    private let emojis = ["😀", "😂", "🥰", "😎", "🤔", "😭", "😡", "👍", "👎", "👏",
                          "❤️", "💔", "🔥", "✨", "🎉", "🎁", "⭐", "🌈", "🍀", "🌸",
                          "🐶", "🐱", "🐼", "🦄", "🍉", "🍔", "🍺", "☕", "⚽", "🎮"]

    var body: some View {
        PanelShell(title: "贴纸", onClose: onClose) {
            Text("点击贴纸添加到播放头位置（时长 3s，可在画中画面板调整）")
                .font(.system(size: 11)).foregroundColor(Color(hex: 0xFF888888))
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 48), spacing: 8)], spacing: 8) {
                ForEach(emojis, id: \.self) { emoji in
                    Text(emoji)
                        .font(.system(size: 30))
                        .frame(width: 48, height: 48)
                        .background(RoundedRectangle(cornerRadius: 8).fill(Color(hex: 0xFF242424)))
                        .onTapGesture { vm.addSticker(emoji) }
                }
            }
        }
    }
}

// MARK: - 字幕面板（手动 + 语音转字）

struct SubtitlePanelView: View {
    let state: EditorUiState
    @ObservedObject var vm: EditorViewModel
    let onClose: () -> Void
    @State private var text = String()
    @State private var start = 0.0
    @State private var end = 2.0

    var body: some View {
        PanelShell(title: "字幕", onClose: onClose) {
            Button {
                vm.transcribeSpeech()
            } label: {
                HStack {
                    if state.isTranscribing {
                        ProgressView().scaleEffect(0.7)
                        Text("识别中...").font(.system(size: 13))
                    } else {
                        Text("🎙 语音转字幕（识别第一个片段）").font(.system(size: 13))
                    }
                }
            }
            .buttonStyle(.borderedProminent)
            .disabled(state.isTranscribing)

            TextField("手动添加字幕文字", text: $text)
                .textFieldStyle(.roundedBorder)
                .font(.system(size: 14))
            HStack {
                Text("起止").font(.system(size: 13)).foregroundColor(Color(hex: 0xFF888888))
                Slider(value: $start, in: 0...max(state.project.totalDuration, 0.5))
                    .frame(width: 90)
                Slider(value: $end, in: 0...max(state.project.totalDuration, 0.5))
                    .frame(width: 90)
                Button("添加") {
                    vm.addSubtitle(text, start, end)
                    text = String()
                }
                .disabled(text.isEmpty)
                .font(.system(size: 13))
            }

            ForEach(state.project.subtitles) { sub in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(sub.text).font(.system(size: 13))
                        Text(String(format: "%.1fs - %.1fs", sub.startTime, sub.endTime))
                            .font(.system(size: 11)).foregroundColor(Color(hex: 0xFF888888))
                    }
                    Spacer()
                    Image(systemName: "trash")
                        .font(.system(size: 13)).foregroundColor(Color(hex: 0xFFFF7043))
                        .onTapGesture { vm.beginEdit(); vm.removeSubtitle(sub.id) }
                }
                .padding(.vertical, 2)
            }
        }
    }
}

// MARK: - 图片水印面板

struct ImageWatermarkPanelView: View {
    let clip: Clip?
    @ObservedObject var vm: EditorViewModel
    let onClose: () -> Void
    let onPickImage: () -> Void

    var body: some View {
        PanelShell(title: "图片水印", onClose: onClose) {
            if let clip = clip {
                if clip.imageWatermarkPath == nil {
                    Button("选择水印图片") { onPickImage() }
                        .buttonStyle(.borderedProminent)
                } else {
                    slider("大小", clip.imageWatermarkScale, 0.05...1) {
                        vm.beginEdit(); vm.updateImageWatermark(clip.id, scale: $0, opacity: clip.imageWatermarkOpacity, position: clip.imageWatermarkPosition)
                    }
                    slider("透明度", clip.imageWatermarkOpacity, 0...1) {
                        vm.beginEdit(); vm.updateImageWatermark(clip.id, scale: clip.imageWatermarkScale, opacity: $0, position: clip.imageWatermarkPosition)
                    }
                    HStack {
                        Text("位置").font(.system(size: 13)).foregroundColor(Color(hex: 0xFF888888))
                        ForEach(["top-left", "top-right", "center", "bottom-left", "bottom-right"], id: \.self) { pos in
                            Text(pos == "top-left" ? "左上" : pos == "top-right" ? "右上" : pos == "center" ? "中" :
                                 pos == "bottom-left" ? "左下" : "右下")
                                .font(.system(size: 12))
                                .foregroundColor(clip.imageWatermarkPosition == pos ? Color(hex: 0xFF2196F3) : Color(hex: 0xFF888888))
                                .padding(.horizontal, 6).padding(.vertical, 4)
                                .background(Capsule().fill(Color(hex: 0xFF242424)))
                                .onTapGesture {
                                    vm.beginEdit()
                                    vm.updateImageWatermark(clip.id, scale: clip.imageWatermarkScale,
                                                            opacity: clip.imageWatermarkOpacity, position: pos)
                                }
                        }
                    }
                    Button("更换图片") { onPickImage() }.font(.system(size: 13))
                }
            } else {
                panelLabel("请先选中片段")
            }
        }
    }

    private func slider(_ label: String, _ value: Double, _ range: ClosedRange<Double>,
                        onChange: @escaping (Double) -> Void) -> some View {
        HStack {
            Text(label).font(.system(size: 13)).foregroundColor(Color(hex: 0xFFAAAAAA)).frame(width: 48, alignment: .leading)
            Slider(value: Binding(get: { value }, set: { vm.beginEdit(); onChange($0) }), in: range)
            Text(String(format: "%.2f", value)).font(.system(size: 12)).frame(width: 44)
        }
    }
}

// MARK: - 模糊背景面板

struct BlurBgPanelView: View {
    let clip: Clip?
    @ObservedObject var vm: EditorViewModel
    let onClose: () -> Void

    var body: some View {
        PanelShell(title: "模糊背景", onClose: onClose) {
            if let clip = clip {
                Toggle("开启模糊背景（竖屏转横屏时用模糊画面填充黑边）", isOn: Binding(
                    get: { clip.blurBgEnabled },
                    set: { nv, _ in vm.beginEdit();
                        if nv != clip.blurBgEnabled { vm.toggleBlurBg(clip.id) } }))
                    .font(.system(size: 13))
                if clip.blurBgEnabled {
                    HStack {
                        Text("模糊强度").font(.system(size: 13)).foregroundColor(Color(hex: 0xFF888888))
                        Slider(value: Binding(
                            get: { Double(clip.blurStrength) },
                            set: { vm.beginEdit(); vm.updateBlurStrength(clip.id, Int($0)) }
                        ), in: 2...40)
                        Text("\(clip.blurStrength)").font(.system(size: 12)).frame(width: 28)
                    }
                }
                Text("预览不实时呈现模糊背景，以导出为准")
                    .font(.system(size: 11)).foregroundColor(Color(hex: 0xFF888888))
            } else {
                panelLabel("请先选中片段")
            }
        }
    }
}

// MARK: - 画布面板（对齐 Android v1.1 CanvasPanel）

struct CanvasPanelView: View {
    @ObservedObject var vm: EditorViewModel
    let onClose: () -> Void

    private struct RatioOpt { let label: String; let w: Int; let h: Int }
    private let ratios = [RatioOpt(label: "9:16", w: 9, h: 16),
                          RatioOpt(label: "1:1", w: 1, h: 1),
                          RatioOpt(label: "16:9", w: 16, h: 9)]
    private struct QualityOpt { let label: String; let shortEdge: Int }
    private let qualities = [QualityOpt(label: "720P", shortEdge: 720),
                             QualityOpt(label: "1080P", shortEdge: 1088),
                             QualityOpt(label: "2K", shortEdge: 1440)]

    private var currentW: Int { vm.uiState.project.canvasWidth }
    private var currentH: Int { vm.uiState.project.canvasHeight }

    private func alignUp16(_ v: Int) -> Int { max(16, ((v + 15) / 16) * 16) }

    /// 短边对齐到清晰度档位，长边按比例换算，两端 16 对齐
    private func canvasDims(_ rw: Int, _ rh: Int, _ shortEdge: Int) -> (Int, Int) {
        if rw >= rh {
            return (alignUp16(shortEdge), alignUp16(shortEdge * rw / rh))
        } else {
            return (alignUp16(shortEdge * rw / rh), alignUp16(shortEdge))
        }
    }

    // 反推当前选中的比例与清晰度；对不上（自定义/遗留值）时取最接近档位
    private var matchedShortEdge: Int? {
        qualities.first { q in
            ratios.contains { canvasDims($0.w, $0.h, q.shortEdge) == (currentW, currentH) }
        }?.shortEdge
    }

    private var curShort: Int {
        matchedShortEdge
            ?? qualities.min { abs($0.shortEdge - min(currentW, currentH)) < abs($1.shortEdge - min(currentW, currentH)) }?.shortEdge
            ?? 1088
    }

    private var curRatioLabel: String {
        ratios.first { canvasDims($0.w, $0.h, curShort) == (currentW, currentH) }?.label ?? "9:16"
    }

    var body: some View {
        PanelShell(title: "画布", onClose: onClose) {
            panelLabel("比例")
            HStack(spacing: 12) {
                ForEach(ratios, id: \.label) { r in
                    ratioCard(r)
                }
            }
            panelLabel("清晰度")
            HStack(spacing: 8) {
                ForEach(qualities, id: \.label) { q in
                    qualityChip(q)
                }
            }
            Text("当前画布：\(currentW)×\(currentH)（预览以导出为准）")
                .font(.system(size: 11)).foregroundColor(Color(hex: 0xFF666666))
        }
    }

    private func ratioCard(_ r: RatioOpt) -> some View {
        let dims = canvasDims(r.w, r.h, curShort)
        let selected = r.label == curRatioLabel && dims == (currentW, currentH)
        let maxSide: CGFloat = 52
        let pw: CGFloat = r.h >= r.w ? maxSide * CGFloat(r.w) / CGFloat(r.h) : maxSide
        let ph: CGFloat = r.h >= r.w ? maxSide : maxSide * CGFloat(r.h) / CGFloat(r.w)
        return Button {
            vm.beginEdit()
            vm.setCanvasSize(width: dims.0, height: dims.1)
        } label: {
            VStack(spacing: 6) {
                RoundedRectangle(cornerRadius: 3)
                    .fill(Color(hex: 0xFF111111))
                    .frame(width: pw, height: ph)
                    .overlay(
                        RoundedRectangle(cornerRadius: 3)
                            .stroke(selected ? Color(hex: 0xFF2196F3) : Color(hex: 0xFF888888),
                                    lineWidth: selected ? 2 : 1)
                    )
                    .frame(height: 56)
                Text(r.label)
                    .font(.system(size: 13))
                    .foregroundColor(selected ? Color(hex: 0xFF2196F3) : .white)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            .background(RoundedRectangle(cornerRadius: 10)
                .fill(selected ? Color(hex: 0xFF123A5C) : Color(hex: 0xFF2A2A2A)))
        }
        .buttonStyle(.plain)
    }

    private func qualityChip(_ q: QualityOpt) -> some View {
        let r = ratios.first { $0.label == curRatioLabel } ?? ratios[0]
        let dims = canvasDims(r.w, r.h, q.shortEdge)
        let selected = matchedShortEdge == q.shortEdge && dims == (currentW, currentH)
        return Button {
            vm.beginEdit()
            vm.setCanvasSize(width: dims.0, height: dims.1)
        } label: {
            Text(q.label)
                .font(.system(size: 13))
                .foregroundColor(selected ? .white : Color(hex: 0xFFCCCCCC))
                .padding(.horizontal, 16).padding(.vertical, 8)
                .background(Capsule().fill(selected ? Color(hex: 0xFF2196F3) : Color(hex: 0xFF2A2A2A)))
        }
        .buttonStyle(.plain)
    }
}
