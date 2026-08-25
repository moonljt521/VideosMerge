//
//  EditorViewModel+Features.swift
//  VideoMerger
//
//  iOS 移植：画中画 / 贴纸 / 图片水印 / 字幕(手动+语音转字) /
//  变声降噪 / 模糊背景 / 片尾logo批量截断 / 去水印自动检测
//

import Foundation
import UIKit

// MARK: - 画中画位置关键帧（模型）

struct PipKeyframeVM { }

extension EditorViewModel {

    // MARK: 画中画

    /// 添加画中画（视频）：默认从播放头开始，占满源时长
    func addPipOverlay(urls: [URL]) {
        guard !urls.isEmpty else { return }
        Task {
            beginImport("准备添加画中画...")
            do {
                var newClips: [Clip] = []
                for (i, url) in urls.enumerated() {
                    let clip = try await importClip(url: url, seed: Int(Date().timeIntervalSince1970 * 1000) + i)
                    var c = clip
                    c.pipEnabled = true
                    c.isImage = false
                    newClips.append(c)
                    _ = i
                    uiState.importProgress = Float(i + 1) / Float(urls.count)
                }
                placePipClips(newClips)
                uiState.isImporting = false
                uiState.currentPanel = .picture
            } catch {
                endImportOnError(error)
            }
        }
    }

    /// 添加画中画（图片）：默认 3 秒，可循环拉长（上限 300s，与贴纸一致）
    func addPipImage(data: Data) {
        let seed = Int(Date().timeIntervalSince1970 * 1000)
        let url = Self.editorMediaDir.appendingPathComponent("pip_image_\(seed).png")
        do {
            try data.write(to: url)
            guard let img = UIImage(contentsOfFile: url.path) else {
                showError("图片读取失败")
                return
            }
            var clip = Clip(
                mediaPath: url.path, mediaName: url.lastPathComponent,
                mediaDuration: 300.0, width: Int(img.size.width * img.scale),
                height: Int(img.size.height * img.scale), hasAudio: false,
                trimEnd: 3.0, thumbnailPath: url.path
            )
            clip.pipEnabled = true
            clip.isImage = true
            pushUndo()
            placePipClips([clip])
            uiState.currentPanel = .picture
        } catch {
            showError("图片保存失败：\(error.localizedDescription)")
        }
    }

    /// 添加贴纸：emoji 渲染 PNG → 图片叠加（时长 3s，宽 20%）
    func addSticker(_ emoji: String) {
        Task {
            guard let png = StickerRenderer.renderToPng(emoji) else {
                showError("贴纸渲染失败")
                return
            }
            var clip = Clip(
                mediaPath: png.path, mediaName: emoji,
                mediaDuration: 300.0, width: 256, height: 256,
                hasAudio: false, trimEnd: 3.0, thumbnailPath: png.path
            )
            clip.pipEnabled = true
            clip.isImage = true
            clip.pipWidth = 0.2
            pushUndo()
            placePipClips([clip])
            uiState.currentPanel = .picture
        }
    }

    /// 画中画片段落位：从播放头开始依次排布，选中并打开面板
    private func placePipClips(_ newClips: [Clip]) {
        var pos = playhead
        let placed = newClips.map { c -> Clip in
            var p = c
            p.timelineStart = pos
            pos += p.timelineDuration
            return p
        }
        if let idx = uiState.project.tracks.firstIndex(where: { $0.type == .picture }) {
            uiState.project.tracks[idx].clips.append(contentsOf: placed)
        } else {
            uiState.project.tracks.append(Track(type: .picture, clips: placed))
        }
        uiState.selectedClipId = placed.first?.id
        uiState.project.updatedAt = Date().timeIntervalSince1970 * 1000
    }

    func updatePipTransform(_ clipId: String, x: Double, y: Double, width: Double, opacity: Double) {
        updateClip(clipId) { clip in
            var c = clip
            c.pipX = min(max(x, 0), 1)
            c.pipY = min(max(y, 0), 1)
            c.pipWidth = min(max(width, 0.05), 1)
            c.pipOpacity = min(max(opacity, 0), 1)
            return c
        }
    }

    func updatePipStyle(_ clipId: String, shape: PipShape, cornerRadius: Double, border: Bool, borderWidth: Double) {
        updateClip(clipId) { clip in
            var c = clip
            c.pipShape = shape
            c.pipCornerRadius = min(max(cornerRadius, 0), 0.5)
            c.pipBorder = border
            c.pipBorderWidth = min(max(borderWidth, 0), 0.2)
            return c
        }
    }

    func updatePipTiming(_ clipId: String, start: Double, duration: Double) {
        updateClip(clipId) { clip in
            var c = clip
            let safeStart = max(start, 0)
            let safeDur = min(max(duration, 0.5), 300.0)
            c.timelineStart = safeStart
            if clip.isImage {
                c.trimEnd = min(safeDur, clip.mediaDuration)
            } else {
                c.trimEnd = min(clip.trimStart + safeDur * clip.speed, clip.mediaDuration)
            }
            return c
        }
    }

    func addPipKeyframe(_ clipId: String) {
        let time = playhead
        updateClip(clipId) { clip in
            var c = clip
            let kf = PipKeyframe(time: time,
                                 x: min(max(clip.pipX, 0), 1),
                                 y: min(max(clip.pipY, 0), 1))
            c.pipKeyframes = (clip.pipKeyframes + [kf]).sorted { $0.time < $1.time }
            return c
        }
    }

    func removePipKeyframe(_ clipId: String, _ index: Int) {
        updateClip(clipId) { clip in
            guard clip.pipKeyframes.indices.contains(index) else { return clip }
            var c = clip
            c.pipKeyframes.remove(at: index)
            return c
        }
    }

    // MARK: 图片水印

    func setImageWatermark(_ clipId: String, data: Data) {
        let seed = Int(Date().timeIntervalSince1970 * 1000)
        let url = Self.editorMediaDir.appendingPathComponent("image_wm_\(seed).png")
        do {
            try data.write(to: url)
        } catch {
            showError("图片保存失败")
            return
        }
        pushUndo()
        updateClip(clipId) { clip in
            var c = clip
            c.imageWatermarkPath = url.path
            return c
        }
    }

    func updateImageWatermark(_ clipId: String, scale: Double, opacity: Double, position: String) {
        updateClip(clipId) { clip in
            var c = clip
            c.imageWatermarkScale = min(max(scale, 0.05), 1)
            c.imageWatermarkOpacity = min(max(opacity, 0), 1)
            c.imageWatermarkPosition = position
            return c
        }
    }

    // MARK: 模糊背景

    func toggleBlurBg(_ clipId: String) {
        updateClip(clipId) { clip in
            var c = clip
            c.blurBgEnabled.toggle()
            return c
        }
    }

    func updateBlurStrength(_ clipId: String, _ strength: Int) {
        updateClip(clipId) { clip in
            var c = clip
            c.blurStrength = min(max(strength, 2), 40)
            return c
        }
    }

    // MARK: 变声 / 降噪

    func updatePitchShift(_ clipId: String, _ pitch: Double) {
        updateClip(clipId) { clip in
            var c = clip
            c.pitchShift = min(max(pitch, 0.5), 2.0)
            return c
        }
    }

    func toggleNoiseReduction(_ clipId: String) {
        updateClip(clipId) { clip in
            var c = clip
            c.noiseReduction.toggle()
            return c
        }
    }

    // MARK: 字幕

    func addSubtitle(_ text: String, _ start: Double, _ end: Double) {
        let trimmed = text.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty, end > start else { return }
        pushUndo()
        var sub = Subtitle(text: trimmed, startTime: max(start, 0), endTime: end)
        sub.id = UUID().uuidString
        uiState.project.subtitles = (uiState.project.subtitles + [sub]).sorted { $0.startTime < $1.startTime }
        uiState.project.updatedAt = Date().timeIntervalSince1970 * 1000
    }

    func removeSubtitle(_ id: String) {
        pushUndo()
        uiState.project.subtitles.removeAll { $0.id == id }
        uiState.project.updatedAt = Date().timeIntervalSince1970 * 1000
    }

    /// 语音转字幕（iOS Speech 框架，主轨第一个片段）
    func transcribeSpeech() {
        guard let mainClip = uiState.project.mainTrack?.clips.first else {
            showError("没有视频片段")
            return
        }
        uiState.isTranscribing = true
        Task {
            do {
                let subs = try await SpeechRecognizerService.transcribe(videoPath: mainClip.mediaPath) { [weak self] status in
                    Task { @MainActor in
                        self?.uiState.exportMessage = status
                    }
                }
                await MainActor.run {
                    uiState.isTranscribing = false
                    if subs.isEmpty {
                        showError("未识别到语音")
                    } else {
                        pushUndo()
                        uiState.project.subtitles = (uiState.project.subtitles + subs)
                            .sorted { $0.startTime < $1.startTime }
                        showError("已生成 \(subs.count) 条字幕")
                    }
                }
            } catch {
                await MainActor.run {
                    uiState.isTranscribing = false
                    showError("语音识别失败：\(error.localizedDescription)", severity: .error)
                }
            }
        }
    }

    // MARK: 片尾 logo 批量截断

    func detectTailLogoAll() {
        let clips = uiState.project.sortedMainClips
        guard !clips.isEmpty else { return }
        uiState.isDetectingLogo = true
        Task.detached {
            var cuts: [(String, Double?)] = []
            for c in clips {
                cuts.append((c.id, MediaUtils.detectLogoCut(path: c.mediaPath)))
            }
            await MainActor.run {
                self.uiState.isDetectingLogo = false
                var removed = 0
                let willCut = cuts.contains { id, cut in
                    guard let cut = cut,
                          let c = self.uiState.project.mainTrack?.clips.first(where: { $0.id == id }) else { return false }
                    return cut < c.mediaDuration - 0.1
                }
                if willCut { self.pushUndo() }
                for (id, cut) in cuts {
                    guard let cut = cut,
                          let c = self.uiState.project.mainTrack?.clips.first(where: { $0.id == id }),
                          cut < c.mediaDuration - 0.1 else { continue }
                    self.updateTrim(id, c.trimStart, cut)
                    removed += 1
                }
                self.showError(removed > 0 ? "已去除 \(removed) 个片段的片尾静止片段" : "未检测到片尾静止片段")
            }
        }
    }

    // MARK: 去水印自动检测

    func autoDetectWatermarks(_ clipId: String) {
        if uiState.isDetectingWatermark { return }
        guard let clip = uiState.selectedClip else { return }
        uiState.isDetectingWatermark = true
        Task.detached {
            let found = WatermarkDetector.detect(videoPath: clip.mediaPath)
            await MainActor.run {
                self.uiState.isDetectingWatermark = false
                if found.isEmpty {
                    self.showError("未检测到静态水印，可手动框选区域")
                    return
                }
                self.pushUndo()
                self.updateClip(clipId) { c in
                    var cc = c
                    let add = found.filter { r in !c.watermarkRegions.contains { $0.overlaps(r) } }
                    cc.watermarkRegions += add
                    return cc
                }
                self.showError("检测到 \(found.count) 处水印，可微调位置和强度")
            }
        }
    }
}
