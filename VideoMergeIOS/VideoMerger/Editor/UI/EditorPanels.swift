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
                    Button("检测片尾静止 logo 并截断") {
                        vm.detectTailLogo(clip.id)
                    }
                    .font(.system(size: 13))
                }
            } else {
                panelLabel("请先选中片段")
            }
        }
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
                Text("框选画面中的水印位置（预览红框标识），导出时对该区域做模糊/马赛克覆盖。")
                    .font(.system(size: 11)).foregroundColor(Color(hex: 0xFF888888))

                HStack {
                    Button { vm.beginEdit(); vm.addWatermarkRegion(clip.id) } label: {
                        Text("手动添加区域").font(.system(size: 13))
                    }
                    .buttonStyle(.borderedProminent)
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
