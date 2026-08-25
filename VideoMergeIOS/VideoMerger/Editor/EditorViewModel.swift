//
//  EditorViewModel.swift
//  VideoMerger
//
//  iOS 移植自 Android EditorViewModel.kt
//  Swift struct 值语义：Clip/Track/Project 拷贝即深拷贝，撤销快照天然隔离
//

import Foundation
import UIKit
import Combine

// MARK: - 面板类型

enum ToolPanel: Equatable {
    case none
    case trim, speed, filter, text
    case imageWatermark, picture, audio, subtitle, sticker
    case watermarkRemove, blurBg, transition, export
}

enum MessageSeverity {
    case info, error
}

// MARK: - UI 状态

struct EditorUiState {
    var project = EditorProject()
    var selectedClipId: String? = nil
    var currentPanel: ToolPanel = .none
    var isPlaying = false
    var inPoint: Double? = nil
    var outPoint: Double? = nil
    var isExporting = false
    var exportProgress: Float = 0
    var exportMessage = ""
    var outputPath: String? = nil
    var isImporting = false
    var importProgress: Float = 0
    var importMessage = ""
    var errorMessage: String? = nil
    var errorSeverity: MessageSeverity = .info
    var isDetectingWatermark = false
    var isDetectingLogo = false
    var isTranscribing = false
    var canUndo = false
    var canRedo = false

    var selectedClip: Clip? {
        project.tracks.flatMap(\.clips).first { $0.id == selectedClipId }
    }

    /// 选中片段后面是否还有片段（转场需要前后两个片段）
    var selectedClipCanTransition: Bool {
        guard let sel = selectedClip else { return false }
        let sorted = project.sortedMainClips
        guard let idx = sorted.firstIndex(where: { $0.id == sel.id }) else { return false }
        return idx < sorted.count - 1
    }
}

// MARK: - ViewModel

@MainActor
final class EditorViewModel: ObservableObject {

    @Published var uiState = EditorUiState()
    /// ★ 播放头独立发布：播放时 ~10Hz 更新，避免整棵编辑器视图重算
    @Published var playhead: Double = 0.0

    private let exportEngine = ExportEngine()

    // 撤销/重做栈（struct 值语义，直接存副本）
    private var undoStack: [EditorProject] = []
    private var redoStack: [EditorProject] = []

    private var importTask: Task<Void, Never>? = nil
    private var exportTask: Task<Void, Never>? = nil

    // MARK: 素材导入

    /// 编辑器素材目录（Documents/editor_media，持久化供草稿引用）
    nonisolated static var editorMediaDir: URL {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("editor_media", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    private static func dateStamp() -> String {
        let f = DateFormatter()
        f.dateFormat = "MMdd-HHmmss"
        return f.string(from: Date())
    }

    /// 创建新项目并导入视频
    func createProject(urls: [URL]) {
        guard !urls.isEmpty else { return }
        importTask?.cancel()
        importTask = Task {
            beginImport("准备导入 \(urls.count) 个视频...")
            do {
                var clips: [Clip] = []
                for (i, url) in urls.enumerated() {
                    try Task.checkCancellation()
                    clips.append(try await importClip(url: url, seed: Int(Date().timeIntervalSince1970 * 1000) + i))
                    uiState.importProgress = Float(i + 1) / Float(urls.count)
                    uiState.importMessage = "正在导入视频 \(i + 1)/\(urls.count)"
                }
                var project = EditorProject(
                    name: "项目 \(Self.dateStamp())",
                    tracks: [Track(type: .main, clips: relayoutMainTrackClips(clips))]
                )
                project.updatedAt = Date().timeIntervalSince1970 * 1000
                undoStack.removeAll(); redoStack.removeAll()
                playhead = 0
                uiState = EditorUiState(project: project, selectedClipId: clips.first?.id)
            } catch is CancellationError {
                uiState.isImporting = false
            } catch {
                endImportOnError(error)
            }
        }
    }

    /// 添加更多视频到主轨末尾
    func addClips(urls: [URL]) {
        guard !urls.isEmpty else { return }
        importTask?.cancel()
        importTask = Task {
            beginImport("准备添加 \(urls.count) 个视频...")
            do {
                var newClips: [Clip] = []
                for (i, url) in urls.enumerated() {
                    try Task.checkCancellation()
                    newClips.append(try await importClip(url: url, seed: Int(Date().timeIntervalSince1970 * 1000) + i))
                    uiState.importProgress = Float(i + 1) / Float(urls.count)
                    uiState.importMessage = "正在添加视频 \(i + 1)/\(urls.count)"
                }
                pushUndo()
                // ★ 导入成功，清除导入中状态（否则浮层永远不消失）
                uiState.isImporting = false
                uiState.importProgress = 0
                uiState.importMessage = ""
                let state = uiState
                if var mainTrack = state.project.mainTrack {
                    mainTrack.clips = relayoutMainTrackClips(mainTrack.clips + newClips)
                    uiState.project.mainTrack = mainTrack
                } else {
                    uiState.project.tracks.append(Track(type: .main, clips: relayoutMainTrackClips(newClips)))
                }
                if uiState.selectedClipId == nil { uiState.selectedClipId = newClips.first?.id }
            } catch is CancellationError {
                uiState.isImporting = false
            } catch {
                endImportOnError(error)
            }
        }
    }

    /// 单个视频导入：拷贝到素材目录 → 元数据 → 首帧缩略图
    func importClip(url: URL, seed: Int) throws -> Clip {
        let ext = url.pathExtension.isEmpty ? "mp4" : url.pathExtension
        let dest = Self.editorMediaDir.appendingPathComponent("input_\(seed).\(ext)")
        try? FileManager.default.removeItem(at: dest)

        let needsScope = url.startAccessingSecurityScopedResource()
        defer { if needsScope { url.stopAccessingSecurityScopedResource() } }
        try FileManager.default.copyItem(at: url, to: dest)

        let meta = MediaUtils.getVideoMeta(path: dest.path) ?? VideoMeta(width: 0, height: 0, duration: 0, hasAudio: false)
        var thumbPath: String? = nil
        if let thumb = MediaUtils.loadThumbnailFromFile(path: dest.path) {
            let thumbURL = Self.editorMediaDir.appendingPathComponent("thumb_\(seed).jpg")
            if MediaUtils.saveImageAsJpeg(thumb, to: thumbURL) {
                thumbPath = thumbURL.path
            }
        }
        return Clip(
            mediaPath: dest.path, mediaName: dest.lastPathComponent,
            mediaDuration: meta.duration, width: meta.width, height: meta.height,
            hasAudio: meta.hasAudio, trimEnd: meta.duration, thumbnailPath: thumbPath
        )
    }

    /// 载入草稿（校验素材存在，剔除丢失片段）
    func loadDraft() {
        Task {
            guard var loaded = DraftStore.load() else {
                showError("草稿不存在或已损坏")
                return
            }
            // 剔除素材丢失的片段
            for ti in loaded.tracks.indices {
                loaded.tracks[ti].clips = loaded.tracks[ti].clips.filter { FileManager.default.fileExists(atPath: $0.mediaPath) }
            }
            if let mi = loaded.tracks.firstIndex(where: { $0.type == .main }) {
                if loaded.tracks[mi].clips.isEmpty {
                    loaded.tracks.remove(at: mi)
                } else {
                    loaded.tracks[mi].clips = relayoutMainTrackClips(loaded.tracks[mi].clips)
                }
            }
            undoStack.removeAll(); redoStack.removeAll()
            playhead = 0
            uiState = EditorUiState(project: loaded, selectedClipId: loaded.mainTrack?.clips.first?.id)
        }
    }

    /// 重置内存状态（草稿已由自动保存落盘）
    func resetState() {
        exportEngine.cancel()
        uiState = EditorUiState()
        playhead = 0
    }

    /// 草稿自动保存（项目变化后由 UI 层防抖调用）
    func saveDraftNow() {
        let p = uiState.project
        let hasContent = !(p.mainTrack?.clips.isEmpty ?? true) || !p.subtitles.isEmpty
        if hasContent {
            DispatchQueue.global(qos: .utility).async {
                DraftStore.save(p)
            }
        }
    }

    func beginImport(_ message: String) {
        uiState.isImporting = true
        uiState.importProgress = 0
        uiState.importMessage = message
    }

    func endImportOnError(_ e: Error) {
        uiState.isImporting = false
        uiState.importMessage = ""
        showError("导入失败：\(e.localizedDescription)")
    }

    func cancelImport() {
        importTask?.cancel()
        uiState.isImporting = false
    }

    // MARK: 选择

    func selectClip(_ clipId: String?) {
        var newPosition = playhead
        if let clipId = clipId,
           let clip = uiState.project.tracks.flatMap(\.clips).first(where: { $0.id == clipId }) {
            // 播放头不在片段内时移入该片段开头，保证预览即所编
            if newPosition < clip.timelineStart - 0.05 || newPosition >= clip.timelineEnd - 0.05 {
                newPosition = clip.timelineStart
            }
        }
        playhead = newPosition
        uiState.selectedClipId = clipId
        if clipId == nil { uiState.currentPanel = .none }
    }

    // MARK: 撤销 / 重做

    func pushUndo() {
        // 去重：栈顶与当前项目一致时跳过
        if let last = undoStack.last, last == uiState.project { return }
        undoStack.append(uiState.project)
        if undoStack.count > 30 { undoStack.removeFirst() }
        redoStack.removeAll()
        uiState.canUndo = true
        uiState.canRedo = false
    }

    /// 撤销后尽量保留选中与面板
    private func restoreSelection(in project: EditorProject) -> (String?, ToolPanel) {
        if let sel = uiState.selectedClipId,
           project.tracks.contains(where: { $0.clips.contains { $0.id == sel } }) {
            return (uiState.selectedClipId, uiState.currentPanel)
        }
        return (nil, .none)
    }

    func undo() {
        guard let prev = undoStack.popLast() else { return }
        redoStack.append(uiState.project)
        let (sel, panel) = restoreSelection(in: prev)
        uiState.project = prev
        uiState.selectedClipId = sel
        uiState.currentPanel = panel
        uiState.canUndo = !undoStack.isEmpty
        uiState.canRedo = true
    }

    func redo() {
        guard let next = redoStack.popLast() else { return }
        undoStack.append(uiState.project)
        let (sel, panel) = restoreSelection(in: next)
        uiState.project = next
        uiState.selectedClipId = sel
        uiState.currentPanel = panel
        uiState.canUndo = true
        uiState.canRedo = !redoStack.isEmpty
    }

    // MARK: 面板

    func showPanel(_ panel: ToolPanel) { uiState.currentPanel = panel }
    func closePanel() { uiState.currentPanel = .none }
    func beginEdit() { pushUndo() }

    // MARK: 播放控制

    func togglePlay() {
        let totalDur = uiState.project.totalDuration
        guard totalDur > 0 else { return }
        if playhead >= totalDur - 0.1 { playhead = 0 }
        uiState.isPlaying.toggle()
    }

    func pausePlayback() {
        if uiState.isPlaying { uiState.isPlaying = false }
    }

    func seekTo(_ position: Double) {
        playhead = min(max(position, 0), uiState.project.totalDuration)
    }

    // MARK: 通用片段更新

    func updateClip(_ clipId: String, _ transform: (Clip) -> Clip) {
        for ti in uiState.project.tracks.indices {
            if let ci = uiState.project.tracks[ti].clips.firstIndex(where: { $0.id == clipId }) {
                uiState.project.tracks[ti].clips[ci] = transform(uiState.project.tracks[ti].clips[ci])
                uiState.project.updatedAt = Date().timeIntervalSince1970 * 1000
                return
            }
        }
    }

    // MARK: 时间轴重排

    func retimelineAll() {
        guard var mainTrack = uiState.project.mainTrack else { return }
        mainTrack.clips = relayoutMainTrackClips(mainTrack.clips.sorted { $0.timelineStart < $1.timelineStart })
        uiState.project.mainTrack = mainTrack
        uiState.project.updatedAt = Date().timeIntervalSince1970 * 1000
    }

    // MARK: 裁剪 / 分割 / 删除

    func updateTrim(_ clipId: String, _ trimStart: Double, _ trimEnd: Double) {
        updateClip(clipId) { clip in
            let s = min(max(trimStart, 0), clip.mediaDuration - 0.1)
            let e = min(max(trimEnd, s + 0.1), clip.mediaDuration)
            var c = clip
            c.trimStart = s
            c.trimEnd = e
            return c
        }
        retimelineAll()
    }

    /// 播放头分割片段（优先当前选中片段，回退到主轨播放头所在片段）
    func splitAtPlayhead() {
        let pos = playhead
        let selected = uiState.selectedClip
        let clip: Clip?
        if let sel = selected, pos > sel.timelineStart + 0.05 && pos < sel.timelineEnd - 0.05 {
            clip = sel
        } else {
            clip = uiState.project.sortedMainClips.first {
                pos > $0.timelineStart + 0.05 && pos < $0.timelineEnd - 0.05
            }
        }
        guard let target = clip else { return }
        splitClip(target.id, at: pos)
    }

    private func splitClip(_ clipId: String, at position: Double) {
        guard let ti = uiState.project.tracks.firstIndex(where: { $0.clips.contains { $0.id == clipId } }) else { return }
        let ci = uiState.project.tracks[ti].clips.firstIndex { $0.id == clipId }!
        let clip = uiState.project.tracks[ti].clips[ci]
        let splitOffset = position - clip.timelineStart
        guard splitOffset > 0, splitOffset < clip.timelineDuration else { return }

        pushUndo()
        let splitSourceTime = clip.sourceTimeAt(position)
        var first = clip
        var second = clip
        if clip.reversed {
            first.trimStart = splitSourceTime
            second.trimEnd = splitSourceTime
        } else {
            first.trimEnd = splitSourceTime
            second.trimStart = splitSourceTime
        }
        second.timelineStart = clip.timelineStart + splitOffset

        uiState.project.tracks[ti].clips.replaceSubrange(ci...ci, with: [first, second])
        retimelineAll()
        uiState.selectedClipId = first.id
    }

    /// 删除片段（主轨联动字幕/画中画）
    func deleteClip(_ clipId: String) {
        guard let ti = uiState.project.tracks.firstIndex(where: { $0.clips.contains { $0.id == clipId } }) else { return }
        let track = uiState.project.tracks[ti]
        guard let ci = track.clips.firstIndex(where: { $0.id == clipId }) else { return }
        let deleted = track.clips[ci]
        pushUndo()
        let oldTotal = uiState.project.totalDuration

        var newTrack = track
        newTrack.clips.remove(at: ci)
        if track.type == .main {
            newTrack.clips = relayoutMainTrackClips(newTrack.clips)
        }
        uiState.project.tracks[ti] = newTrack

        if track.type == .main {
            let delta = uiState.project.totalDuration - oldTotal
            uiState.project = shiftOverlaysAfter(project: uiState.project, anchor: deleted.timelineStart, delta: delta)
        }

        // 选中相邻片段
        let relaid = newTrack.clips
        uiState.selectedClipId = relaid.getOrNil(ci)?.id ?? relaid.getOrNil(ci - 1)?.id
    }

    // MARK: 区间删除

    func setInPoint() {
        uiState.inPoint = playhead
        if let o = uiState.outPoint, o <= playhead + 0.05 { uiState.outPoint = nil }
    }

    func setOutPoint() {
        uiState.outPoint = playhead
        if let i = uiState.inPoint, i >= playhead - 0.05 { uiState.inPoint = nil }
    }

    func clearRange() {
        uiState.inPoint = nil
        uiState.outPoint = nil
    }

    /// 删除 [inPoint, outPoint] 区间内容（对每个片段分四种情况处理）
    func deleteRange() {
        guard let inP = uiState.inPoint, let outP = uiState.outPoint, outP - inP >= 0.05,
              var mainTrack = uiState.project.mainTrack else { return }
        let eps = 0.001
        var kept: [Clip] = []

        for clip in mainTrack.clips.sorted(by: { $0.timelineStart < $1.timelineStart }) {
            let cs = clip.timelineStart
            let ce = clip.timelineEnd
            let srcIn = clip.sourceTimeAt(inP)
            let srcOut = clip.sourceTimeAt(outP)
            let srcBegin = clip.trimStart
            let srcEnd = clip.trimEnd > 0 ? clip.trimEnd : clip.mediaDuration
            if cs >= inP - eps && ce <= outP + eps {
                // 完全在区间内 → 删除
            } else if cs < inP - eps && ce > outP + eps {
                // 区间完全在片段内部 → 拆两段
                if clip.reversed {
                    kept.append(clip.withTrim(start: srcIn, end: srcEnd))
                    kept.append(clip.withTrim(start: srcBegin, end: srcOut))
                } else {
                    kept.append(clip.withTrim(start: srcBegin, end: min(srcIn, srcEnd)))
                    kept.append(clip.withTrim(start: min(srcOut, srcEnd - 0.1), end: clip.trimEnd))
                }
            } else if cs < inP - eps && ce > inP + eps {
                // 片段尾部在区间内 → 保留头部
                if clip.reversed {
                    if srcEnd - srcIn > 0.05 { kept.append(clip.withTrim(start: srcIn, end: clip.trimEnd)) }
                } else {
                    if srcIn - srcBegin > 0.05 { kept.append(clip.withTrim(start: srcBegin, end: srcIn)) }
                }
            } else if cs < outP - eps && ce > outP + eps {
                // 片段头部在区间内 → 保留尾部
                if clip.reversed {
                    if srcOut - srcBegin > 0.05 { kept.append(clip.withTrim(start: srcBegin, end: srcOut)) }
                } else {
                    if srcEnd - srcOut > 0.05 { kept.append(clip.withTrim(start: srcOut, end: clip.trimEnd)) }
                }
            } else {
                kept.append(clip)
            }
        }

        if kept.isEmpty {
            showError("区间包含了全部内容，无法全部删除")
            return
        }

        pushUndo()
        let oldTotal = uiState.project.totalDuration
        mainTrack.clips = relayoutMainTrackClips(kept)
        uiState.project.mainTrack = mainTrack
        let delta = uiState.project.totalDuration - oldTotal
        uiState.project = shiftOverlaysAfter(project: uiState.project, anchor: inP, delta: delta)

        playhead = min(inP, uiState.project.mainTrack?.duration ?? 0)
        uiState.selectedClipId = mainTrack.clips.first { $0.timelineStart >= inP - 0.05 }?.id
            ?? mainTrack.clips.last?.id
        uiState.inPoint = nil
        uiState.outPoint = nil
    }

    // MARK: 片段属性

    func updateSpeed(_ clipId: String, _ speed: Double) {
        updateClip(clipId) { var c = $0; c.speed = min(max(speed, 0.25), 4.0); return c }
        retimelineAll()
    }

    func toggleReverse(_ clipId: String) {
        updateClip(clipId) { var c = $0; c.reversed.toggle(); return c }
    }

    func updateVolume(_ clipId: String, _ volume: Double) {
        updateClip(clipId) { var c = $0; c.volume = min(max(volume, 0), 5); return c }
    }

    func updateAudioFade(_ clipId: String, _ fadeIn: Double, _ fadeOut: Double) {
        updateClip(clipId) { clip in
            var c = clip
            let maxD = clip.timelineDuration / 2
            c.audioFadeIn = min(max(fadeIn, 0), maxD)
            c.audioFadeOut = min(max(fadeOut, 0), maxD)
            return c
        }
    }

    func setRotation(_ clipId: String, _ rotation: Int) {
        updateClip(clipId) { var c = $0; c.rotation = rotation; return c }
    }

    func toggleHFlip(_ clipId: String) {
        updateClip(clipId) { var c = $0; c.hflip.toggle(); return c }
    }

    func toggleVFlip(_ clipId: String) {
        updateClip(clipId) { var c = $0; c.vflip.toggle(); return c }
    }

    func setFilterPreset(_ clipId: String, _ preset: FilterPreset) {
        updateClip(clipId) { var c = $0; c.filterPreset = preset; return c }
    }

    func updateColorParams(_ clipId: String, _ brightness: Double, _ contrast: Double, _ saturation: Double) {
        updateClip(clipId) { var c = $0; c.brightness = brightness; c.contrast = contrast; c.saturation = saturation; return c }
    }

    func setTextOverlay(_ clipId: String, _ text: String?) {
        let oldEmpty = uiState.selectedClip?.textOverlay?.isEmpty ?? true
        let newEmpty = text?.isEmpty ?? true
        if oldEmpty != newEmpty { pushUndo() }
        updateClip(clipId) { var c = $0; c.textOverlay = text; return c }
    }

    func setTextStyle(_ clipId: String, size: Int, color: String, position: String, opacity: Double, border: Bool) {
        updateClip(clipId) { clip in
            var c = clip
            c.textSize = min(max(size, 12), 120)
            c.textColor = color
            c.textPosition = position
            c.textOpacity = min(max(opacity, 0), 1)
            c.textBorder = border
            return c
        }
    }

    // MARK: 转场

    func setTransition(_ clipId: String, _ effect: TransitionEffect) {
        updateClip(clipId) { var c = $0; c.transition = effect; return c }
        retimelineAll()
    }

    func updateTransitionDuration(_ clipId: String, _ duration: Double) {
        updateClip(clipId) { var c = $0; c.transitionDuration = min(max(duration, 0.2), 3.0); return c }
        retimelineAll()
    }

    // MARK: 去水印区域

    func addWatermarkRegion(_ clipId: String, corner: String? = nil) {
        pushUndo()
        updateClip(clipId) { clip in
            let region: WatermarkRegion
            switch corner {
            case "tl": region = WatermarkRegion(x: 0.02, y: 0.03, w: 0.45, h: 0.18)
            case "tr": region = WatermarkRegion(x: 0.53, y: 0.03, w: 0.45, h: 0.18)
            case "bl": region = WatermarkRegion(x: 0.02, y: 0.79, w: 0.45, h: 0.18)
            case "br": region = WatermarkRegion(x: 0.53, y: 0.79, w: 0.45, h: 0.18)
            default:
                let y = min(0.42 + Double(clip.watermarkRegions.count) * 0.08, 0.8)
                region = WatermarkRegion(x: 0.55, y: y, w: 0.3, h: 0.12)
            }
            var c = clip
            c.watermarkRegions.append(region)
            return c
        }
    }

    func updateWatermarkRegion(_ clipId: String, _ index: Int, _ region: WatermarkRegion) {
        updateClip(clipId) { clip in
            guard clip.watermarkRegions.indices.contains(index) else { return clip }
            var c = clip
            let end = region.endTime <= region.startTime ? region.endTime : max(region.endTime, region.startTime + 0.1)
            var r = region
            r.x = min(max(region.x, 0), 0.98)
            r.y = min(max(region.y, 0), 0.98)
            r.w = min(max(region.w, 0.01), 1.0 - r.x)
            r.h = min(max(region.h, 0.01), 1.0 - r.y)
            r.startTime = min(max(region.startTime, 0), clip.mediaDuration)
            r.endTime = min(end, clip.mediaDuration)
            c.watermarkRegions[index] = r
            return c
        }
    }

    func removeWatermarkRegion(_ clipId: String, _ index: Int) {
        pushUndo()
        updateClip(clipId) { clip in
            guard clip.watermarkRegions.indices.contains(index) else { return clip }
            var c = clip
            c.watermarkRegions.remove(at: index)
            return c
        }
    }

    // MARK: 尾部 logo 检测截断

    func detectTailLogo(_ clipId: String) {
        guard let clip = uiState.project.tracks.flatMap(\.clips).first(where: { $0.id == clipId }) else { return }
        Task.detached {
            let cut = MediaUtils.detectLogoCut(path: clip.mediaPath)
            await MainActor.run {
                if let cut = cut, cut < clip.mediaDuration - 0.1 {
                    self.pushUndo()
                    self.updateTrim(clipId, clip.trimStart, cut)
                    self.showError(String(format: "已去除片尾静止片段，出点裁至 %.2fs", cut))
                } else {
                    self.showError("未检测到片尾静止 logo")
                }
            }
        }
    }

    // MARK: 导出

    func export() {
        guard !uiState.isExporting else { return }
        let project = uiState.project
        pausePlayback()
        uiState.isExporting = true
        uiState.exportProgress = 0
        uiState.exportMessage = "准备导出..."
        uiState.errorMessage = nil

        exportTask = Task {
            let result = await exportEngine.export(project: project) { [weak self] progress in
                Task { @MainActor in
                    self?.uiState.exportProgress = progress
                    self?.uiState.exportMessage = "导出中... \(Int(progress * 100))%"
                }
            } onLog: { log in
                print("[Export] \(log)")
            }
            switch result {
            case .success(let file):
                exportEngine.recordToHistory(file: file, project: project)
                let saveResult = await exportEngine.saveToGallery(file: file, projectName: project.name)
                await MainActor.run {
                    uiState.isExporting = false
                    switch saveResult {
                    case .success:
                        uiState.exportProgress = 1
                        uiState.exportMessage = "导出完成！已保存到相册"
                        uiState.outputPath = file.path
                    case .failure(let e):
                        showError("保存失败: \(e.localizedDescription)", severity: .error)
                    }
                }
            case .failure(let e):
                await MainActor.run {
                    uiState.isExporting = false
                    showError("导出失败: \(e.localizedDescription)", severity: .error)
                }
            }
        }
    }

    func cancelExport() {
        exportEngine.cancel()
        exportTask?.cancel()
        uiState.isExporting = false
        showError("已取消导出")
    }

    // MARK: 提示

    func showError(_ message: String, severity: MessageSeverity = .info) {
        uiState.errorMessage = message
        uiState.errorSeverity = severity
    }

    func dismissError() {
        uiState.errorMessage = nil
    }
}

// MARK: - Clip 修改辅助

private extension Clip {
    func withTrim(start: Double, end: Double) -> Clip {
        var c = self
        c.trimStart = start
        c.trimEnd = end
        return c
    }
}

extension Array {
    func getOrNil(_ index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}
