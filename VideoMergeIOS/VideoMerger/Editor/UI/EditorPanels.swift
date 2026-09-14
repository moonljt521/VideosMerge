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
        PanelShell(title: L10n.t("editorui.panel_trim"), onClose: onClose) {
            if let clip = clip {
                Group {
                    HStack {
                        Text(L10n.t("editorui.trim_in_point")).font(.system(size: 13)).foregroundColor(Color(hex: 0xFFAAAAAA))
                        Slider(value: Binding(
                            get: { clip.trimStart },
                            set: { vm.beginEdit(); vm.updateTrim(clip.id, $0, clip.trimEnd) }
                        ), in: 0...max(clip.mediaDuration - 0.1, 0.1))
                        Text(String(format: "%.2f", clip.trimStart))
                            .font(.system(size: 12)).frame(width: 48)
                    }
                    HStack {
                        Text(L10n.t("editorui.trim_out_point")).font(.system(size: 13)).foregroundColor(Color(hex: 0xFFAAAAAA))
                        Slider(value: Binding(
                            get: { clip.trimEnd > 0 ? clip.trimEnd : clip.mediaDuration },
                            set: { vm.beginEdit(); vm.updateTrim(clip.id, clip.trimStart, $0) }
                        ), in: 0.1...clip.mediaDuration)
                        Text(String(format: "%.2f", clip.trimEnd > 0 ? clip.trimEnd : clip.mediaDuration))
                            .font(.system(size: 12)).frame(width: 48)
                    }
                    // 旋转 / 翻转
                    HStack(spacing: 12) {
                        Text(L10n.t("editorui.transform")).font(.system(size: 13)).foregroundColor(Color(hex: 0xFFAAAAAA))
                        ForEach([RotationMode.none, .cw90, .ccw90, .r180], id: \.self) { m in
                            Text(m == .none ? L10n.t("editorui.rotation_none") : m.displayName.replacingOccurrences(of: "°", with: ""))
                                .font(.system(size: 12))
                                .foregroundColor(clip.rotation == m.rawValue ? Color(hex: 0xFF2196F3) : Color(hex: 0xFF888888))
                                .padding(.horizontal, 8).padding(.vertical, 4)
                                .background(Capsule().fill(Color(hex: 0xFF242424)))
                                .onTapGesture { vm.beginEdit(); vm.setRotation(clip.id, m.rawValue) }
                        }
                    }
                    HStack(spacing: 12) {
                        Toggle(L10n.t("editorui.hflip"), isOn: Binding(
                            get: { clip.hflip },
                            set: { _ in vm.beginEdit(); vm.toggleHFlip(clip.id) }))
                            .font(.system(size: 13))
                        Toggle(L10n.t("editorui.vflip"), isOn: Binding(
                            get: { clip.vflip },
                            set: { _ in vm.beginEdit(); vm.toggleVFlip(clip.id) }))
                            .font(.system(size: 13))
                    }
                    // ── 片尾静止 logo/标语检测截断（对齐 Android：单片段 + 全部片段）──
                    VStack(alignment: .leading, spacing: 6) {
                        Text(L10n.t("editorui.tail_still_section")).font(.system(size: 13))
                        Text(L10n.t("editorui.tail_still_desc"))
                            .font(.system(size: 11)).foregroundColor(Color(hex: 0xFF666666))
                        HStack(spacing: 8) {
                            detectButton(title: L10n.t("editorui.current_clip"), busy: vm.uiState.isDetectingLogo) {
                                vm.detectTailLogo(clip.id)
                            }
                            detectButton(title: L10n.t("editorui.all_clips"), busy: vm.uiState.isDetectingLogo) {
                                vm.detectTailLogoAll()
                            }
                            Spacer()
                        }
                    }
                }
            } else {
                panelLabel(L10n.t("editorui.no_clip_selected"))
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
                Text(busy ? L10n.t("editorui.detecting") : title).font(.system(size: 12))
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
        PanelShell(title: L10n.t("editorui.panel_speed"), onClose: onClose) {
            if let clip = clip {
                HStack {
                    Text(L10n.t("editorui.speed")).font(.system(size: 13)).foregroundColor(Color(hex: 0xFFAAAAAA))
                    Slider(value: Binding(
                        get: { clip.speed },
                        set: { vm.beginEdit(); vm.updateSpeed(clip.id, $0) }
                    ), in: 0.25...4.0)
                    Text(String(format: "%.2fx", clip.speed)).font(.system(size: 12)).frame(width: 48)
                }
                Toggle(L10n.t("editorui.reverse"), isOn: Binding(
                    get: { clip.reversed },
                    set: { _ in vm.beginEdit(); vm.toggleReverse(clip.id) }))
                    .font(.system(size: 13))
            } else {
                panelLabel(L10n.t("editorui.no_clip_selected"))
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
        PanelShell(title: L10n.t("editorui.panel_filter"), onClose: onClose) {
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
                    slider(L10n.t("editorui.brightness"), value: clip.brightness) { vm.beginEdit(); vm.updateColorParams(clip.id, $0, clip.contrast, clip.saturation) }
                    slider(L10n.t("editorui.contrast"), value: clip.contrast) { vm.beginEdit(); vm.updateColorParams(clip.id, clip.brightness, $0, clip.saturation) }
                    slider(L10n.t("editorui.saturation"), value: clip.saturation) { vm.beginEdit(); vm.updateColorParams(clip.id, clip.brightness, clip.contrast, $0) }
                }
            } else {
                panelLabel(L10n.t("editorui.no_clip_selected"))
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
        PanelShell(title: L10n.t("editorui.panel_text"), onClose: onClose) {
            if let clip = clip {
                TextField(L10n.t("editorui.text_placeholder"), text: Binding(
                    get: { clip.textOverlay ?? "" },
                    set: { vm.setTextOverlay(clip.id, $0.isEmpty ? nil : $0) }
                ))
                .textFieldStyle(.roundedBorder)
                .font(.system(size: 14))

                HStack {
                    Text(L10n.t("editorui.font_size")).font(.system(size: 13)).foregroundColor(Color(hex: 0xFFAAAAAA)).frame(width: 52, alignment: .leading)
                    Slider(value: Binding(
                        get: { Double(clip.textSize) },
                        set: { vm.beginEdit(); vm.setTextStyle(clip.id, size: Int($0), color: clip.textColor, position: clip.textPosition, opacity: clip.textOpacity, border: clip.textBorder) }
                    ), in: 12...120)
                    Text("\(clip.textSize)").font(.system(size: 12)).frame(width: 32)
                }
                HStack {
                    Text(L10n.t("editorui.position")).font(.system(size: 13)).foregroundColor(Color(hex: 0xFFAAAAAA)).frame(width: 52, alignment: .leading)
                    ForEach(["top-left", "center", "bottom-right"], id: \.self) { pos in
                        Text(pos == "top-left" ? L10n.t("editorui.pos_top") : pos == "center" ? L10n.t("editorui.pos_center") : L10n.t("editorui.pos_bottom_right"))
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
                    Toggle(L10n.t("editorui.stroke"), isOn: Binding(
                        get: { clip.textBorder },
                        set: { nv, _ in vm.beginEdit(); vm.setTextStyle(clip.id, size: clip.textSize, color: clip.textColor,
                                                               position: clip.textPosition, opacity: clip.textOpacity, border: nv) }))
                        .font(.system(size: 13))
                }
            } else {
                panelLabel(L10n.t("editorui.no_clip_selected"))
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
        PanelShell(title: L10n.t("editorui.panel_audio"), onClose: onClose) {
            if let clip = clip {
                if !clip.hasAudio {
                    panelLabel(L10n.t("editorui.no_audio_track"))
                } else {
                    HStack {
                        Text(L10n.t("editorui.volume")).font(.system(size: 13)).foregroundColor(Color(hex: 0xFFAAAAAA)).frame(width: 52, alignment: .leading)
                        Slider(value: Binding(
                            get: { clip.volume },
                            set: { vm.beginEdit(); vm.updateVolume(clip.id, $0) }
                        ), in: 0...3)
                        Text(String(format: "%.1f", clip.volume)).font(.system(size: 12)).frame(width: 36)
                    }
                    HStack {
                        Text(L10n.t("editorui.fade_in")).font(.system(size: 13)).foregroundColor(Color(hex: 0xFFAAAAAA)).frame(width: 52, alignment: .leading)
                        Slider(value: Binding(
                            get: { clip.audioFadeIn },
                            set: { vm.beginEdit(); vm.updateAudioFade(clip.id, $0, clip.audioFadeOut) }
                        ), in: 0...max(clip.timelineDuration / 2, 0.1))
                        Text(String(format: "%.1fs", clip.audioFadeIn)).font(.system(size: 12)).frame(width: 44)
                    }
                    HStack {
                        Text(L10n.t("editorui.fade_out")).font(.system(size: 13)).foregroundColor(Color(hex: 0xFFAAAAAA)).frame(width: 52, alignment: .leading)
                        Slider(value: Binding(
                            get: { clip.audioFadeOut },
                            set: { vm.beginEdit(); vm.updateAudioFade(clip.id, clip.audioFadeIn, $0) }
                        ), in: 0...max(clip.timelineDuration / 2, 0.1))
                        Text(String(format: "%.1fs", clip.audioFadeOut)).font(.system(size: 12)).frame(width: 44)
                    }
                    HStack {
                        Text(L10n.t("editorui.pitch")).font(.system(size: 13)).foregroundColor(Color(hex: 0xFFAAAAAA)).frame(width: 52, alignment: .leading)
                        Slider(value: Binding(
                            get: { clip.pitchShift },
                            set: { vm.beginEdit(); vm.updatePitchShift(clip.id, $0) }
                        ), in: 0.5...2.0)
                        Text(clip.pitchShift == 1.0 ? L10n.t("editorui.pitch_normal") : (clip.pitchShift > 1 ? L10n.t("editorui.pitch_high") : L10n.t("editorui.pitch_low")))
                            .font(.system(size: 12)).frame(width: 40)
                    }
                    Toggle(L10n.t("editorui.noise_reduction"), isOn: Binding(
                        get: { clip.noiseReduction },
                        set: { nv, _ in vm.beginEdit(); if nv != clip.noiseReduction { vm.toggleNoiseReduction(clip.id) } }))
                        .font(.system(size: 13))
                }
            } else {
                panelLabel(L10n.t("editorui.no_clip_selected"))
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
        PanelShell(title: L10n.t("editorui.panel_transition"), onClose: onClose) {
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
                        Text(L10n.t("editorui.duration")).font(.system(size: 13)).foregroundColor(Color(hex: 0xFFAAAAAA))
                        Slider(value: Binding(
                            get: { clip.transitionDuration },
                            set: { vm.beginEdit(); vm.updateTransitionDuration(clip.id, $0) }
                        ), in: 0.2...3.0)
                        Text(String(format: "%.1fs", clip.transitionDuration)).font(.system(size: 12)).frame(width: 40)
                    }
                }
                panelLabel(L10n.t("editorui.transition_duration_note"))
            } else {
                panelLabel(L10n.t("editorui.no_clip_selected"))
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
        PanelShell(title: L10n.t("editorui.panel_watermark_remove"), onClose: onClose) {
            if let clip = clip {
                Text(L10n.t("editorui.wm_remove_desc"))
                    .font(.system(size: 11)).foregroundColor(Color(hex: 0xFF888888))

                HStack(spacing: 12) {
                    Button {
                        vm.autoDetectWatermarks(clip.id)
                    } label: {
                        HStack(spacing: 6) {
                            if vm.uiState.isDetectingWatermark { ProgressView().scaleEffect(0.6) }
                            Text(vm.uiState.isDetectingWatermark ? L10n.t("editorui.detecting") : L10n.t("editorui.auto_detect_wm"))
                                .font(.system(size: 13))
                        }
                        .padding(.horizontal, 12).padding(.vertical, 6)
                        .background(RoundedRectangle(cornerRadius: 6).fill(Color(hex: 0xFF2196F3)))
                        .foregroundColor(.white)
                    }
                    .disabled(vm.uiState.isDetectingWatermark)

                    Button { vm.beginEdit(); vm.addWatermarkRegion(clip.id) } label: {
                        Text(L10n.t("editorui.add_region_manually")).font(.system(size: 13))
                    }
                    .buttonStyle(.borderedProminent)
                }

                HStack(spacing: 8) {
                    Text(L10n.t("editorui.common_positions"))
                        .font(.system(size: 11)).foregroundColor(Color(hex: 0xFF888888))
                    ForEach(["tl", "tr", "bl", "br"], id: \.self) { corner in
                        Text(corner == "tl" ? L10n.t("editorui.corner_tl") : corner == "tr" ? L10n.t("editorui.corner_tr") : corner == "bl" ? L10n.t("editorui.corner_bl") : L10n.t("editorui.corner_br"))
                            .font(.system(size: 12)).foregroundColor(Color(hex: 0xFF2196F3))
                            .onTapGesture { vm.beginEdit(); vm.addWatermarkRegion(clip.id, corner: corner) }
                    }
                }

                ForEach(Array(clip.watermarkRegions.enumerated()), id: \.offset) { index, region in
                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            Text(L10n.t("editorui.region_index", index + 1)).font(.system(size: 13))
                            Spacer()
                            Text(L10n.t("editorui.mode_blur")).font(.system(size: 12))
                                .foregroundColor(region.mode == .blur ? Color(hex: 0xFF2196F3) : Color(hex: 0xFF888888))
                                .onTapGesture { vm.beginEdit(); vm.updateWatermarkRegion(clip.id, index, region) }
                            Text(L10n.t("editorui.mode_mosaic")).font(.system(size: 12))
                                .foregroundColor(region.mode == .mosaic ? Color(hex: 0xFF2196F3) : Color(hex: 0xFF888888))
                                .onTapGesture {
                                    vm.beginEdit()
                                    var r = region; r.mode = .mosaic
                                    vm.updateWatermarkRegion(clip.id, index, r)
                                }
                            Image(systemName: "trash").font(.system(size: 13)).foregroundColor(Color(hex: 0xFFFF7043))
                                .onTapGesture { vm.beginEdit(); vm.removeWatermarkRegion(clip.id, index) }
                        }
                        wmSlider(L10n.t("editorui.pos_x"), value: region.x) { vm.updateWatermarkRegion(clip.id, index, regionWith(region, x: $0)) }
                        wmSlider(L10n.t("editorui.pos_y"), value: region.y) { vm.updateWatermarkRegion(clip.id, index, regionWith(region, y: $0)) }
                        wmSlider(L10n.t("editorui.width"), value: region.w) { vm.updateWatermarkRegion(clip.id, index, regionWith(region, w: $0)) }
                        wmSlider(L10n.t("editorui.height"), value: region.h) { vm.updateWatermarkRegion(clip.id, index, regionWith(region, h: $0)) }
                        wmSlider(L10n.t("editorui.strength"), value: Double(region.strength), range: 1...40, format: "%.0f") {
                            vm.updateWatermarkRegion(clip.id, index, regionWith(region, strength: Int($0)))
                        }
                        HStack {
                            Text(L10n.t("editorui.time_range")).font(.system(size: 12)).foregroundColor(Color(hex: 0xFF888888))
                            Text(region.endTime <= region.startTime
                                 ? L10n.t("editorui.full_range")
                                 : String(format: "%.1fs - %.1fs", region.startTime, region.endTime))
                                .font(.system(size: 12))
                        }
                    }
                    .padding(8)
                    .background(RoundedRectangle(cornerRadius: 8).fill(Color(hex: 0xFF242424)))
                }
            } else {
                panelLabel(L10n.t("editorui.no_clip_selected"))
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
        PanelShell(title: L10n.t("editorui.panel_export"), onClose: onClose) {
            Text(L10n.t("editorui.project_label", state.project.name)).font(.system(size: 14))
            Text(L10n.t("editorui.canvas_label", state.project.canvasWidth, state.project.canvasHeight))
                .font(.system(size: 12)).foregroundColor(Color(hex: 0xFF888888))
            Text(L10n.t("editorui.duration_label", state.project.totalDuration))
                .font(.system(size: 12)).foregroundColor(Color(hex: 0xFF888888))
            Text(L10n.t("editorui.clip_count_label", state.project.mainTrack?.clips.count ?? 0))
                .font(.system(size: 12)).foregroundColor(Color(hex: 0xFF888888))

            if state.isExporting {
                ProgressView(value: Double(state.exportProgress)).tint(Color(hex: 0xFF2196F3))
                Text(state.exportMessage).font(.system(size: 12)).foregroundColor(Color(hex: 0xFF888888))
                Button(L10n.t("editorui.cancel_export")) { vm.cancelExport() }
                    .foregroundColor(Color(hex: 0xFFFF7043))
            } else {
                Button {
                    vm.export()
                } label: {
                    Text(L10n.t("editorui.export_video"))
                        .font(.system(size: 15, weight: .bold))
                        .frame(maxWidth: .infinity).frame(height: 44)
                }
                .buttonStyle(.borderedProminent)
            }

            if let path = state.outputPath {
                Text(L10n.t("editorui.exported_to", path)).font(.system(size: 11)).foregroundColor(Color(hex: 0xFF4CAF50))
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
        PanelShell(title: L10n.t("editorui.panel_pip"), onClose: onClose) {
            if let clip = clip, clip.pipEnabled {
                Group {
                    slider(L10n.t("editorui.pos_x"), clip.pipX, 0...0.99) { vm.beginEdit(); vm.updatePipTransform(clip.id, x: $0, y: clip.pipY, width: clip.pipWidth, opacity: clip.pipOpacity) }
                    slider(L10n.t("editorui.pos_y"), clip.pipY, 0...0.99) { vm.beginEdit(); vm.updatePipTransform(clip.id, x: clip.pipX, y: $0, width: clip.pipWidth, opacity: clip.pipOpacity) }
                    slider(L10n.t("editorui.size"), clip.pipWidth, 0.05...1) { vm.beginEdit(); vm.updatePipTransform(clip.id, x: clip.pipX, y: clip.pipY, width: $0, opacity: clip.pipOpacity) }
                    slider(L10n.t("editorui.opacity"), clip.pipOpacity, 0...1) { vm.beginEdit(); vm.updatePipTransform(clip.id, x: clip.pipX, y: clip.pipY, width: clip.pipWidth, opacity: $0) }
                    HStack(spacing: 12) {
                        Text(L10n.t("editorui.shape")).font(.system(size: 13)).foregroundColor(Color(hex: 0xFF888888))
                        ForEach([PipShape.rect, .rounded, .circle], id: \.self) { shape in
                            Text(shape == .rect ? L10n.t("editorui.shape_rect") : shape == .rounded ? L10n.t("editorui.shape_rounded") : L10n.t("editorui.shape_circle"))
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
                        Toggle(L10n.t("editorui.stroke"), isOn: Binding(
                            get: { clip.pipBorder },
                            set: { nv, _ in vm.beginEdit(); vm.updatePipStyle(clip.id, shape: clip.pipShape,
                                                       cornerRadius: clip.pipCornerRadius, border: nv, borderWidth: clip.pipBorderWidth) }))
                            .font(.system(size: 13))
                    }
                    slider(L10n.t("editorui.start"), clip.timelineStart, 0...max(vm.uiState.project.totalDuration, 0.5), fmt: "%.1f") {
                        vm.beginEdit(); vm.updatePipTiming(clip.id, start: $0, duration: clip.timelineDuration)
                    }
                    slider(L10n.t("editorui.duration"), clip.timelineDuration, 0.5...max(clip.mediaDuration / max(clip.speed, 0.1), 0.6), fmt: "%.1f") {
                        vm.beginEdit(); vm.updatePipTiming(clip.id, start: clip.timelineStart, duration: $0)
                    }

                    // 位置关键帧
                    HStack {
                        Text(L10n.t("editorui.keyframes")).font(.system(size: 13)).foregroundColor(Color(hex: 0xFFAAAAAA))
                        Button(L10n.t("editorui.add_keyframe")) {
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

                    Button(L10n.t("editorui.delete_pip"), role: .destructive) {
                        if let id = vm.uiState.selectedClipId { vm.deleteClip(id) }
                        onClose()
                    }
                    .font(.system(size: 13))
                }
            } else {
                VStack(alignment: .leading, spacing: 12) {
                    Text(L10n.t("editorui.pip_desc")).font(.system(size: 12)).foregroundColor(Color(hex: 0xFF888888))
                    HStack(spacing: 12) {
                        Button(L10n.t("editorui.pick_video")) { onPickVideo() }
                            .buttonStyle(.borderedProminent)
                        Button(L10n.t("editorui.pick_image")) { onPickImage() }
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
        PanelShell(title: L10n.t("editorui.panel_sticker"), onClose: onClose) {
            Text(L10n.t("editorui.sticker_desc"))
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
        PanelShell(title: L10n.t("editorui.panel_subtitle"), onClose: onClose) {
            Button {
                vm.transcribeSpeech()
            } label: {
                HStack {
                    if state.isTranscribing {
                        ProgressView().scaleEffect(0.7)
                        Text(L10n.t("editorui.transcribing")).font(.system(size: 13))
                    } else {
                        Text(L10n.t("editorui.speech_to_subtitle")).font(.system(size: 13))
                    }
                }
            }
            .buttonStyle(.borderedProminent)
            .disabled(state.isTranscribing)

            TextField(L10n.t("editorui.subtitle_placeholder"), text: $text)
                .textFieldStyle(.roundedBorder)
                .font(.system(size: 14))
            HStack {
                Text(L10n.t("editorui.start_end")).font(.system(size: 13)).foregroundColor(Color(hex: 0xFF888888))
                Slider(value: $start, in: 0...max(state.project.totalDuration, 0.5))
                    .frame(width: 90)
                Slider(value: $end, in: 0...max(state.project.totalDuration, 0.5))
                    .frame(width: 90)
                Button(L10n.t("editorui.add")) {
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
        PanelShell(title: L10n.t("editorui.panel_image_watermark"), onClose: onClose) {
            if let clip = clip {
                if clip.imageWatermarkPath == nil {
                    Button(L10n.t("editorui.pick_watermark_image")) { onPickImage() }
                        .buttonStyle(.borderedProminent)
                } else {
                    slider(L10n.t("editorui.size"), clip.imageWatermarkScale, 0.05...1) {
                        vm.beginEdit(); vm.updateImageWatermark(clip.id, scale: $0, opacity: clip.imageWatermarkOpacity, position: clip.imageWatermarkPosition)
                    }
                    slider(L10n.t("editorui.opacity"), clip.imageWatermarkOpacity, 0...1) {
                        vm.beginEdit(); vm.updateImageWatermark(clip.id, scale: clip.imageWatermarkScale, opacity: $0, position: clip.imageWatermarkPosition)
                    }
                    HStack {
                        Text(L10n.t("editorui.position")).font(.system(size: 13)).foregroundColor(Color(hex: 0xFF888888))
                        ForEach(["top-left", "top-right", "center", "bottom-left", "bottom-right"], id: \.self) { pos in
                            Text(pos == "top-left" ? L10n.t("editorui.corner_tl") : pos == "top-right" ? L10n.t("editorui.corner_tr") : pos == "center" ? L10n.t("editorui.pos_center") :
                                 pos == "bottom-left" ? L10n.t("editorui.corner_bl") : L10n.t("editorui.corner_br"))
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
                    Button(L10n.t("editorui.change_image")) { onPickImage() }.font(.system(size: 13))
                }
            } else {
                panelLabel(L10n.t("editorui.no_clip_selected"))
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
        PanelShell(title: L10n.t("editorui.panel_blur_bg"), onClose: onClose) {
            if let clip = clip {
                Toggle(L10n.t("editorui.blur_bg_toggle"), isOn: Binding(
                    get: { clip.blurBgEnabled },
                    set: { nv, _ in vm.beginEdit();
                        if nv != clip.blurBgEnabled { vm.toggleBlurBg(clip.id) } }))
                    .font(.system(size: 13))
                if clip.blurBgEnabled {
                    HStack {
                        Text(L10n.t("editorui.blur_strength")).font(.system(size: 13)).foregroundColor(Color(hex: 0xFF888888))
                        Slider(value: Binding(
                            get: { Double(clip.blurStrength) },
                            set: { vm.beginEdit(); vm.updateBlurStrength(clip.id, Int($0)) }
                        ), in: 2...40)
                        Text("\(clip.blurStrength)").font(.system(size: 12)).frame(width: 28)
                    }
                }
                Text(L10n.t("editorui.blur_bg_note"))
                    .font(.system(size: 11)).foregroundColor(Color(hex: 0xFF888888))
            } else {
                panelLabel(L10n.t("editorui.no_clip_selected"))
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
        PanelShell(title: L10n.t("editorui.panel_canvas"), onClose: onClose) {
            panelLabel(L10n.t("editorui.ratio"))
            HStack(spacing: 12) {
                ForEach(ratios, id: \.label) { r in
                    ratioCard(r)
                }
            }
            panelLabel(L10n.t("editorui.quality"))
            HStack(spacing: 8) {
                ForEach(qualities, id: \.label) { q in
                    qualityChip(q)
                }
            }
            Text(L10n.t("editorui.canvas_current", currentW, currentH))
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
