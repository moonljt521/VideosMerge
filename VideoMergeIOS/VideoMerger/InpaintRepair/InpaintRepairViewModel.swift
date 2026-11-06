//
//  InpaintRepairViewModel.swift
//  VideoMerger
//
//  视频去水印（内容修复）页状态机：选片 → 检测/框选 → 探测帧预览 → 逐帧修复 → 保存
//

import Foundation
import UIKit
import Combine
import ffmpegkit

struct InpaintRepairUiState {
    var sourceURL: URL?
    var sourceName = ""
    var meta: InpaintVideoMeta?

    var probeImage: UIImage?
    var afterROI: UIImage?
    var maskROI: UIImage?
    var maskRect: InpaintRect?
    var maskPixels = 0
    var hasPreview = false

    /// 归一化水印框（显示方向）
    var bbox = CGRect(x: 0.60, y: 0.82, width: 0.36, height: 0.14)
    var probeTime: Double = 0
    var dilate = 13
    var showAfter = true
    var showMask = false

    var isDetecting = false
    var isPreparing = false
    var hint: String?

    var isProcessing = false
    var progress: Double = 0
    var phase = ""
    var resultURL: URL?

    var isSaving = false
    var isSaved = false
    var errorMessage: String?
}

@MainActor
final class InpaintRepairViewModel: ObservableObject {

    @Published var uiState = InpaintRepairUiState()

    private let engine = InpaintEngine.shared
    private var prepared: InpaintPrepared?
    private var previewTask: Task<Void, Never>?
    private var previewGeneration = 0
    private var cancelFlag = false
    private var probeCache = FileManager.default.temporaryDirectory
        .appendingPathComponent("inpaint_probe_current.png")

    // MARK: - 选片

    func onVideoPicked(url: URL) {
        guard !uiState.isProcessing else { return }
        cancelFlag = false
        previewTask?.cancel()
        prepared = nil
        uiState = InpaintRepairUiState()
        uiState.sourceURL = url
        uiState.sourceName = url.lastPathComponent

        Task { [weak self] in
            guard let self else { return }
            do {
                let (first, w, h) = try await inpaintBackground { () -> (UIImage, Int, Int) in
                    guard let frame = InpaintEngine.shared.extractProbeFrame(
                        path: url.path, time: 0, to: FileManager.default.temporaryDirectory
                            .appendingPathComponent("inpaint_first.png")) else {
                        throw InpaintEngineError.probeFailed
                    }
                    return frame
                }
                guard let meta = self.engine.probeMeta(path: url.path, displayWidth: w, displayHeight: h) else {
                    throw InpaintEngineError.probeFailed
                }
                self.uiState.meta = meta
                self.uiState.probeImage = first
                self.uiState.probeTime = min(meta.duration * 0.3, max(meta.duration - 0.05, 0))
                if meta.isVFR { self.uiState.hint = L10n.t("repair.vfr_warning") }
                self.autoDetect()
            } catch {
                self.uiState.errorMessage = error.localizedDescription
            }
        }
    }

    /// 自动检测水印框 + 自动挑探测帧
    func autoDetect() {
        guard let url = uiState.sourceURL, let meta = uiState.meta, !uiState.isDetecting else { return }
        uiState.isDetecting = true

        Task { [weak self] in
            guard let self else { return }
            do {
                let regions = try await inpaintBackground {
                    WatermarkDetector.detect(videoPath: url.path)
                }
                var bbox = self.uiState.bbox
                if let best = regions.max(by: { $0.w * $0.h < $1.w * $1.h }) {
                    bbox = CGRect(x: best.x, y: best.y, width: best.w, height: best.h)
                    bbox = Self.clampBBox(bbox)
                } else {
                    bbox = Self.clampBBox(bbox)
                    self.uiState.hint = L10n.t("repair.default_box_hint")
                }
                self.uiState.bbox = bbox
                let t = try await inpaintBackground {
                    InpaintEngine.shared.autoProbeTime(path: url.path, duration: meta.duration,
                                                       fps: meta.fps, bboxNorm: bbox)
                }
                if t > 0 { self.uiState.probeTime = t }
                self.uiState.isDetecting = false
                self.refreshPreview()
            } catch {
                self.uiState.isDetecting = false
                self.uiState.errorMessage = error.localizedDescription
            }
        }
    }

    // MARK: - 框选 / 探测帧

    func setBBox(_ bbox: CGRect, generate: Bool = true) {
        uiState.bbox = Self.clampBBox(bbox)
        if generate { schedulePreview() }
    }

    func setProbeTime(_ t: Double) {
        uiState.probeTime = min(max(t, 0), max((uiState.meta?.duration ?? 1) - 0.05, 0))
        schedulePreview()
    }

    func setDilate(_ v: Int) {
        uiState.dilate = min(max(v, 5), 25)
        schedulePreview()
    }

    func toggleAfter() { uiState.showAfter.toggle() }
    func toggleMask() { uiState.showMask.toggle() }

    private func schedulePreview() {
        previewTask?.cancel()
        previewTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 380_000_000)
            guard !Task.isCancelled else { return }
            self?.refreshPreview()
        }
    }

    /// 生成单帧修复预览（遮罩 + 修复结果）
    func refreshPreview() {
        guard let url = uiState.sourceURL, let meta = uiState.meta else { return }
        previewGeneration += 1
        let gen = previewGeneration
        let bboxPx = Self.bboxPixels(uiState.bbox, meta: meta)
        let time = uiState.probeTime
        let dilate = uiState.dilate
        let cache = probeCache
        uiState.isPreparing = true
        uiState.errorMessage = nil

        Task { [weak self] in
            guard let self else { return }
            do {
                let result = try await inpaintBackground {
                    InpaintEngine.shared.prepare(sourcePath: url.path, meta: meta,
                                                 probeTime: time, bboxPx: bboxPx,
                                                 dilate: dilate, probeCache: cache)
                }
                guard gen == self.previewGeneration else { return }
                switch result {
                case .success(let p):
                    self.prepared = p
                    self.uiState.probeImage = p.probeImage
                    self.uiState.afterROI = p.afterROI
                    self.uiState.maskROI = p.maskROI
                    self.uiState.maskRect = p.roi
                    self.uiState.maskPixels = p.mask.pixelCount
                    self.uiState.hasPreview = true
                case .failure(let err):
                    self.prepared = nil
                    self.uiState.hasPreview = false
                    self.uiState.hint = err.localizedDescription
                }
                self.uiState.isPreparing = false
            } catch {
                guard gen == self.previewGeneration else { return }
                self.uiState.isPreparing = false
                self.uiState.errorMessage = error.localizedDescription
            }
        }
    }

    // MARK: - 修复

    func startRepair() {
        guard let url = uiState.sourceURL else { return }
        guard let prepared = prepared, uiState.hasPreview else {
            uiState.errorMessage = L10n.t("repair.need_preview")
            return
        }
        guard !uiState.isProcessing else { return }
        if let old = uiState.resultURL { try? FileManager.default.removeItem(at: old) }

        let out = FileManager.default.temporaryDirectory
            .appendingPathComponent("inpaint_out_\(Int(Date().timeIntervalSince1970 * 1000)).mp4")
        cancelFlag = false
        uiState.isProcessing = true
        uiState.isSaved = false
        uiState.resultURL = nil
        uiState.progress = 0
        uiState.phase = L10n.t("repair.phase_analyze")
        uiState.errorMessage = nil

        Task { [weak self] in
            guard let self else { return }
            let result = await inpaintBackgroundValue { () -> Result<URL, Error> in
                InpaintEngine.shared.repair(
                    sourcePath: url.path,
                    prepared: prepared,
                    outputPath: out.path,
                    onProgress: { p, phase in
                        Task { @MainActor [weak self] in
                            self?.uiState.progress = p
                            self?.uiState.phase = phase
                        }
                    },
                    isCancelled: { [weak self] in self?.cancelFlag ?? true }
                )
            }
            self.uiState.isProcessing = false
            switch result {
            case .success(let outURL):
                self.uiState.progress = 1
                self.uiState.resultURL = outURL
            case .failure(let err):
                if let e = err as? InpaintEngineError, case .cancelled = e {
                    self.uiState.progress = 0
                    self.uiState.phase = ""
                } else {
                    self.uiState.errorMessage = err.localizedDescription
                    self.uiState.progress = 0
                }
            }
        }
    }

    func cancel() {
        guard uiState.isProcessing else { return }
        cancelFlag = true
        FFmpegKit.cancel()
        uiState.phase = L10n.t("repair.cancelling")
    }

    func clearResult() {
        if let url = uiState.resultURL { try? FileManager.default.removeItem(at: url) }
        uiState.resultURL = nil
        uiState.isSaved = false
        uiState.progress = 0
    }

    func saveResult() {
        guard let url = uiState.resultURL, let meta = uiState.meta, !uiState.isSaving else { return }
        uiState.isSaving = true
        Task { [weak self] in
            guard let self else { return }
            do {
                let name = L10n.t("repair.save_filename", Int(Date().timeIntervalSince1970 * 1000))
                _ = try await inpaintBackground {
                    try MediaUtils.saveToGallery(fileURL: url, displayName: name)
                }
                self.recordHistory(url: url, meta: meta)
                self.uiState.isSaving = false
                self.uiState.isSaved = true
            } catch {
                self.uiState.isSaving = false
                self.uiState.errorMessage = L10n.t("repair.save_failed", error.localizedDescription)
            }
        }
    }

    private func recordHistory(url: URL, meta: InpaintVideoMeta) {
        var thumbURL: URL?
        if let thumb = MediaUtils.loadThumbnailFromFile(path: url.path) {
            let u = FileManager.default.temporaryDirectory
                .appendingPathComponent("repair_thumb_\(Int(Date().timeIntervalSince1970 * 1000)).jpg")
            if MediaUtils.saveImageAsJpeg(thumb, to: u) {
                thumbURL = u
            }
        }
        _ = VideoHistoryStore.addToHistory(
            videoURL: url,
            thumbnailURL: thumbURL,
            mergeType: L10n.t("repair.history_type"),
            duration: meta.duration,
            width: meta.displayWidth,
            height: meta.displayHeight
        )
    }

    func dismissError() {
        uiState.errorMessage = nil
    }

    // MARK: - 几何工具

    static func clampBBox(_ r: CGRect) -> CGRect {
        var x = min(max(r.origin.x, 0), 0.98)
        var y = min(max(r.origin.y, 0), 0.98)
        var w = min(max(r.size.width, 0.02), 0.98 - x)
        var h = min(max(r.size.height, 0.01), 0.98 - y)
        x = min(x, 0.98 - w)
        y = min(y, 0.98 - h)
        if w < 0.02 { w = 0.02 }
        if h < 0.01 { h = 0.01 }
        return CGRect(x: x, y: y, width: w, height: h)
    }

    static func bboxPixels(_ norm: CGRect, meta: InpaintVideoMeta) -> InpaintRect {
        InpaintRect(x: Int((norm.origin.x * Double(meta.displayWidth)).rounded()),
                    y: Int((norm.origin.y * Double(meta.displayHeight)).rounded()),
                    w: max(Int((norm.size.width * Double(meta.displayWidth)).rounded()), 4),
                    h: max(Int((norm.size.height * Double(meta.displayHeight)).rounded()), 4))
    }
}
