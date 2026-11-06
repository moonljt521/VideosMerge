//
//  InpaintRepairView.swift
//  VideoMerger
//
//  视频去水印（内容修复）页：选片 → 自动检测/框选 → 探测帧前后对比 → 修复 → 保存
//

import SwiftUI

struct InpaintRepairView: View {

    @StateObject private var vm = InpaintRepairViewModel()
    @State private var showPicker = false
    @State private var showFullscreen = false
    @State private var moveStart: CGRect?
    @State private var resizeStart: CGRect?

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                if vm.uiState.sourceURL == nil {
                    emptyPickSection
                } else {
                    sourceSection
                    previewSection
                    if let url = vm.uiState.resultURL {
                        resultSection(url: url)
                    } else {
                        processSection
                    }
                }
                if let msg = vm.uiState.errorMessage { errorSection(msg) }
                if let hint = vm.uiState.hint { hintSection(hint) }
                tipsSection
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .background(Color.black)
        .navigationTitle(L10n.t("repair.nav_title"))
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showPicker) {
            VideoPicker(maxSelection: 1) { ids in
                Task { @MainActor in
                    let urls = await MediaUtils.loadVideoURLs(assetIdentifiers: ids)
                    if let url = urls.first { vm.onVideoPicked(url: url) }
                }
            }
        }
        .fullScreenCover(isPresented: $showFullscreen) {
            if let url = vm.uiState.resultURL {
                FullscreenVideoPlayer(
                    videoURL: url,
                    onSaveClick: { showFullscreen = false; vm.saveResult() },
                    onDismiss: { showFullscreen = false }
                )
            }
        }
    }

    // MARK: - 空态

    private var emptyPickSection: some View {
        Button { showPicker = true } label: {
            VStack(spacing: 10) {
                Image(systemName: "wand.and.stars")
                    .font(.system(size: 44))
                    .foregroundColor(Color(hex: 0xFFAB47BC))
                Text(L10n.t("repair.pick_title"))
                    .font(.system(size: 16, weight: .bold))
                Text(L10n.t("repair.pick_hint"))
                    .font(.system(size: 12))
                    .foregroundColor(Color(hex: 0xFF666666))
            }
            .frame(maxWidth: .infinity)
            .frame(height: 200)
            .background(RoundedRectangle(cornerRadius: 16).fill(Color(hex: 0xFF1A1A1A)))
        }
    }

    // MARK: - 源信息

    private var sourceSection: some View {
        HStack(spacing: 10) {
            Image(systemName: "video.fill").foregroundColor(Color(hex: 0xFFAB47BC))
            VStack(alignment: .leading, spacing: 2) {
                Text(vm.uiState.sourceName)
                    .font(.system(size: 14, weight: .medium)).lineLimit(1)
                if let meta = vm.uiState.meta {
                    Text(L10n.t("repair.source_info", meta.duration,
                                meta.displayWidth, meta.displayHeight))
                        .font(.system(size: 12)).foregroundColor(Color(hex: 0xFF888888)).lineLimit(1)
                }
            }
            Spacer()
            Button(vm.uiState.isProcessing ? L10n.t("repair.processing") : L10n.t("repair.repick")) {
                showPicker = true
            }
            .font(.system(size: 13))
            .disabled(vm.uiState.isProcessing || vm.uiState.isDetecting)
        }
        .padding(.horizontal, 16).padding(.vertical, 12)
        .background(RoundedRectangle(cornerRadius: 16).fill(Color(hex: 0xFF1A1A1A)))
    }

    // MARK: - 预览 / 框选

    private var previewSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(L10n.t("repair.preview_title")).font(.system(size: 15, weight: .bold))
                Spacer()
                Button {
                    vm.autoDetect()
                } label: {
                    HStack(spacing: 5) {
                        if vm.uiState.isDetecting { ProgressView().scaleEffect(0.6) }
                        Text(vm.uiState.isDetecting ? L10n.t("repair.detecting") : L10n.t("repair.auto_detect"))
                            .font(.system(size: 12))
                    }
                    .padding(.horizontal, 10).padding(.vertical, 5)
                    .background(RoundedRectangle(cornerRadius: 6).fill(Color(hex: 0xFF2196F3)))
                    .foregroundColor(.white)
                }
                .disabled(vm.uiState.isDetecting || vm.uiState.isProcessing)
            }

            bboxEditor

            Text(L10n.t("repair.bbox_hint"))
                .font(.system(size: 11)).foregroundColor(Color(hex: 0xFF888888))

            HStack(spacing: 10) {
                chip("tl")
                chip("tr")
                chip("bl")
                chip("br")
                Spacer()
                if vm.uiState.isPreparing {
                    HStack(spacing: 4) {
                        ProgressView().scaleEffect(0.6)
                        Text(L10n.t("repair.preparing")).font(.system(size: 11))
                            .foregroundColor(Color(hex: 0xFF888888))
                    }
                } else if vm.uiState.hasPreview {
                    Text(L10n.t("repair.mask_pixels", vm.uiState.maskPixels))
                        .font(.system(size: 11)).foregroundColor(Color(hex: 0xFF888888))
                }
            }

            HStack(spacing: 8) {
                toggleChip(L10n.t("repair.show_after"), on: vm.uiState.showAfter) { vm.toggleAfter() }
                toggleChip(L10n.t("repair.show_mask"), on: vm.uiState.showMask) { vm.toggleMask() }
                Spacer()
                Button(L10n.t("repair.refresh")) { vm.refreshPreview() }
                    .font(.system(size: 12)).foregroundColor(Color(hex: 0xFF2196F3))
                    .disabled(vm.uiState.isPreparing || vm.uiState.isProcessing)
            }

            sliderRow(L10n.t("repair.pos_x"), value: vm.uiState.bbox.origin.x) { v in
                var b = vm.uiState.bbox; b.origin.x = v; vm.setBBox(b, generate: false)
            } onEnd: { vm.refreshPreview() }

            sliderRow(L10n.t("repair.pos_y"), value: vm.uiState.bbox.origin.y) { v in
                var b = vm.uiState.bbox; b.origin.y = v; vm.setBBox(b, generate: false)
            } onEnd: { vm.refreshPreview() }

            sliderRow(L10n.t("repair.width"), value: vm.uiState.bbox.size.width) { v in
                var b = vm.uiState.bbox; b.size.width = v; vm.setBBox(b, generate: false)
            } onEnd: { vm.refreshPreview() }

            sliderRow(L10n.t("repair.height"), value: vm.uiState.bbox.size.height) { v in
                var b = vm.uiState.bbox; b.size.height = v; vm.setBBox(b, generate: false)
            } onEnd: { vm.refreshPreview() }

            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(L10n.t("repair.probe_time")).font(.system(size: 12))
                        .foregroundColor(Color(hex: 0xFFAAAAAA)).frame(width: 60, alignment: .leading)
                    Slider(value: Binding(
                        get: { vm.uiState.probeTime },
                        set: { vm.setProbeTime($0) }
                    ), in: 0...max((vm.uiState.meta?.duration ?? 1) - 0.02, 0.1))
                    Text(L10n.t("repair.probe_time_value", vm.uiState.probeTime))
                        .font(.system(size: 11)).frame(width: 46)
                }
            }

            VStack(alignment: .leading, spacing: 4) {
                Text(L10n.t("repair.advanced")).font(.system(size: 12)).foregroundColor(Color(hex: 0xFF888888))
                HStack {
                    Text(L10n.t("repair.edge_expand")).font(.system(size: 12))
                        .foregroundColor(Color(hex: 0xFFAAAAAA)).frame(width: 60, alignment: .leading)
                    Slider(value: Binding(
                        get: { Double(vm.uiState.dilate) },
                        set: { vm.setDilate(Int($0)) }
                    ), in: 5...25, step: 2)
                    Text("\(vm.uiState.dilate)").font(.system(size: 11)).frame(width: 30)
                }
            }
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 16).fill(Color(hex: 0xFF1A1A1A)))
    }

    // MARK: - 画面 + 红框

    private var bboxEditor: some View {
        GeometryReader { geo in
            let fit = Self.fittedRect(imageSize: imagePixelSize, in: geo.size)
            let bbox = vm.uiState.bbox
            let r = CGRect(x: fit.minX + bbox.minX * fit.width,
                           y: fit.minY + bbox.minY * fit.height,
                           width: bbox.width * fit.width,
                           height: bbox.height * fit.height)
            ZStack(alignment: .topLeading) {
                Color(hex: 0xFF101010)
                if let img = vm.uiState.probeImage {
                    Image(uiImage: img)
                        .resizable()
                        .frame(width: fit.width, height: fit.height)
                        .position(x: fit.midX, y: fit.midY)
                }
                // 修复结果/遮罩只保存 ROI，按 ROI 位置叠加，避免整帧大位图
                if vm.uiState.showAfter, let roiImg = vm.uiState.afterROI,
                   let r = Self.roiRect(fit: fit, imageSize: imagePixelSize, mask: vm.uiState.maskRect) {
                    Image(uiImage: roiImg)
                        .resizable()
                        .frame(width: r.width, height: r.height)
                        .position(x: r.midX, y: r.midY)
                        .allowsHitTesting(false)
                }
                if vm.uiState.showMask, let maskImg = vm.uiState.maskROI,
                   let r = Self.roiRect(fit: fit, imageSize: imagePixelSize, mask: vm.uiState.maskRect) {
                    Image(uiImage: maskImg)
                        .resizable()
                        .frame(width: r.width, height: r.height)
                        .position(x: r.midX, y: r.midY)
                        .allowsHitTesting(false)
                }
                Rectangle()
                    .fill(Color.white.opacity(0.001))
                    .frame(width: max(r.width, 12), height: max(r.height, 12))
                    .position(x: r.midX, y: r.midY)
                    .gesture(
                        DragGesture()
                            .onChanged { v in
                                if moveStart == nil { moveStart = vm.uiState.bbox }
                                guard let start = moveStart, fit.width > 0, fit.height > 0 else { return }
                                var b = start
                                b.origin.x += v.translation.width / fit.width
                                b.origin.y += v.translation.height / fit.height
                                vm.setBBox(b, generate: false)
                            }
                            .onEnded { _ in
                                moveStart = nil
                                vm.refreshPreview()
                            }
                    )
                Rectangle()
                    .stroke(Color(hex: 0xFFFF4D4D), lineWidth: 2)
                    .frame(width: max(r.width, 12), height: max(r.height, 12))
                    .position(x: r.midX, y: r.midY)
                    .allowsHitTesting(false)
                Circle()
                    .fill(Color(hex: 0xFFFF4D4D))
                    .frame(width: 22, height: 22)
                    .position(x: r.maxX, y: r.maxY)
                    .gesture(
                        DragGesture()
                            .onChanged { v in
                                if resizeStart == nil { resizeStart = vm.uiState.bbox }
                                guard let start = resizeStart, fit.width > 0, fit.height > 0 else { return }
                                var b = start
                                b.size.width = max(0.02, b.size.width + v.translation.width / fit.width)
                                b.size.height = max(0.01, b.size.height + v.translation.height / fit.height)
                                vm.setBBox(b, generate: false)
                            }
                            .onEnded { _ in
                                resizeStart = nil
                                vm.refreshPreview()
                            }
                    )
            }
            .clipShape(RoundedRectangle(cornerRadius: 10))
        }
        .frame(height: 330)
    }

    private var imagePixelSize: CGSize {
        guard let img = vm.uiState.probeImage, let cg = img.cgImage else { return CGSize(width: 9, height: 16) }
        return CGSize(width: cg.width, height: cg.height)
    }

    private static func roiRect(fit: CGRect, imageSize: CGSize, mask: InpaintRect?) -> CGRect? {
        guard let mask = mask, imageSize.width > 0, imageSize.height > 0 else { return nil }
        let sx = fit.width / imageSize.width
        let sy = fit.height / imageSize.height
        return CGRect(x: fit.minX + CGFloat(mask.x) * sx,
                      y: fit.minY + CGFloat(mask.y) * sy,
                      width: CGFloat(mask.w) * sx,
                      height: CGFloat(mask.h) * sy)
    }

    private static func fittedRect(imageSize: CGSize, in container: CGSize) -> CGRect {
        guard imageSize.width > 0, imageSize.height > 0,
              container.width > 0, container.height > 0 else { return .zero }
        let scale = min(container.width / imageSize.width, container.height / imageSize.height)
        let w = imageSize.width * scale
        let h = imageSize.height * scale
        return CGRect(x: (container.width - w) / 2, y: (container.height - h) / 2, width: w, height: h)
    }

    private func chip(_ corner: String) -> some View {
        let label: String = {
            switch corner {
            case "tl": return L10n.t("editorui.corner_tl")
            case "tr": return L10n.t("editorui.corner_tr")
            case "bl": return L10n.t("editorui.corner_bl")
            default: return L10n.t("editorui.corner_br")
            }
        }()
        return Button {
            var b: CGRect
            switch corner {
            case "tl": b = CGRect(x: 0.02, y: 0.03, width: 0.32, height: 0.11)
            case "tr": b = CGRect(x: 0.66, y: 0.03, width: 0.32, height: 0.11)
            case "bl": b = CGRect(x: 0.02, y: 0.85, width: 0.32, height: 0.11)
            default: b = CGRect(x: 0.66, y: 0.85, width: 0.32, height: 0.11)
            }
            vm.setBBox(b)
        } label: {
            Text(label)
                .font(.system(size: 11))
                .padding(.horizontal, 10).padding(.vertical, 5)
                .background(RoundedRectangle(cornerRadius: 6).fill(Color(hex: 0xFF2A2A2A)))
                .foregroundColor(Color(hex: 0xFFBBBBBB))
        }
        .disabled(vm.uiState.isProcessing)
    }

    private func toggleChip(_ title: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 11))
                .padding(.horizontal, 10).padding(.vertical, 5)
                .background(RoundedRectangle(cornerRadius: 6).fill(
                    on ? Color(hex: 0xFF2196F3) : Color(hex: 0xFF2A2A2A)))
                .foregroundColor(on ? .white : Color(hex: 0xFFBBBBBB))
        }
    }

    private func sliderRow(_ label: String, value: Double,
                           onChange: @escaping (Double) -> Void,
                           onEnd: @escaping () -> Void) -> some View {
        HStack {
            Text(label).font(.system(size: 12)).foregroundColor(Color(hex: 0xFFAAAAAA))
                .frame(width: 60, alignment: .leading)
            Slider(value: Binding(get: { value }, set: { onChange($0) }),
                   in: 0...0.98, onEditingChanged: { editing in
                if !editing { onEnd() }
            })
            Text(String(format: "%.2f", value)).font(.system(size: 11)).frame(width: 40)
        }
    }

    // MARK: - 处理 / 结果

    private var processSection: some View {
        VStack(spacing: 12) {
            if vm.uiState.isProcessing {
                HStack {
                    Text(vm.uiState.phase).font(.system(size: 14, weight: .medium))
                    Spacer()
                    Text("\(Int(vm.uiState.progress * 100))%").bold()
                }
                ProgressView(value: vm.uiState.progress).tint(Color(hex: 0xFFAB47BC))
                Button { vm.cancel() } label: {
                    Text(L10n.t("repair.cancel"))
                        .font(.system(size: 14)).frame(maxWidth: .infinity).padding(.vertical, 10)
                        .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color(hex: 0xFF555555)))
                }
            } else {
                Button { vm.startRepair() } label: {
                    HStack(spacing: 8) {
                        Image(systemName: "wand.and.stars")
                        Text(L10n.t("repair.start")).font(.system(size: 16, weight: .medium))
                    }
                    .frame(maxWidth: .infinity).padding(.vertical, 12)
                    .background(RoundedRectangle(cornerRadius: 8).fill(
                        vm.uiState.hasPreview ? Color(hex: 0xFFAB47BC) : Color(hex: 0xFF3A3A3A)))
                    .foregroundColor(.white)
                }
                .disabled(!vm.uiState.hasPreview || vm.uiState.isPreparing)
            }
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 16).fill(Color(hex: 0xFF1A1A1A)))
    }

    private func resultSection(url: URL) -> some View {
        let sizeText: String = {
            if let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
               let bytes = attrs[.size] as? Int64 {
                return bytes >= 1024 * 1024
                    ? String(format: "%.1fMB", Double(bytes) / 1024 / 1024)
                    : String(format: "%.0fKB", Double(bytes) / 1024)
            }
            return ""
        }()
        let meta = vm.uiState.meta
        return VStack(spacing: 12) {
            HStack(spacing: 8) {
                Image(systemName: "checkmark.circle.fill").foregroundColor(Color(hex: 0xFF4CAF50))
                Text(L10n.t("repair.result_title")).font(.system(size: 16, weight: .bold))
                Spacer()
                Button { vm.clearResult() } label: {
                    Text(L10n.t("repair.again")).font(.system(size: 12))
                        .foregroundColor(Color(hex: 0xFF999999))
                }
            }
            if let meta = meta {
                Text(L10n.t("repair.result_info", meta.displayWidth, meta.displayHeight,
                            meta.duration, sizeText))
                    .font(.system(size: 12)).foregroundColor(Color(hex: 0xFF888888))
            }
            InlineVideoPlayer(videoURL: url, onTap: { showFullscreen = true })
                .frame(maxWidth: .infinity).frame(height: 220)

            if vm.uiState.isSaving {
                HStack(spacing: 8) {
                    ProgressView().scaleEffect(0.8)
                    Text(L10n.t("repair.saving")).font(.system(size: 13))
                        .foregroundColor(Color(hex: 0xFF4CAF50))
                }
            } else if vm.uiState.isSaved {
                HStack(spacing: 6) {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 14)).foregroundColor(Color(hex: 0xFF4CAF50))
                    Text(L10n.t("repair.saved")).font(.system(size: 13))
                        .foregroundColor(Color(hex: 0xFF4CAF50))
                }
            } else {
                Button { vm.saveResult() } label: {
                    HStack(spacing: 8) {
                        Image(systemName: "square.and.arrow.down")
                        Text(L10n.t("repair.save")).font(.system(size: 15, weight: .medium))
                    }
                    .frame(maxWidth: .infinity).padding(.vertical, 12)
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
            Text(msg).font(.system(size: 13)).foregroundColor(Color(hex: 0xFFE57373))
                .multilineTextAlignment(.center)
            Button(L10n.t("gif.ok")) { vm.dismissError() }.font(.system(size: 14))
        }
        .frame(maxWidth: .infinity).padding(16)
        .background(RoundedRectangle(cornerRadius: 16).fill(Color(hex: 0xFF2A1215)))
    }

    private func hintSection(_ msg: String) -> some View {
        Text(msg)
            .font(.system(size: 12)).foregroundColor(Color(hex: 0xFFE0A030))
            .frame(maxWidth: .infinity, alignment: .leading).padding(12)
            .background(RoundedRectangle(cornerRadius: 10).fill(Color(hex: 0xFF2A2312)))
    }

    private var tipsSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(L10n.t("repair.tips_title")).font(.system(size: 14, weight: .bold))
            ForEach([L10n.t("repair.tip1"), L10n.t("repair.tip2"), L10n.t("repair.tip3")], id: \.self) { tip in
                Text(tip).font(.system(size: 12)).foregroundColor(Color(hex: 0xFF888888))
            }
        }
        .padding(16).frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 16).fill(Color(hex: 0xFF1A1A1A)))
    }
}
