//
//  PreviewPanel.swift
//  VideoMerger
//
//  iOS 移植自 Android PreviewPanel.kt
//  分层渲染：单层=当前片段 AVPlayer；转场重叠区=双 AVPlayer 交叉淡化（近似动画）
//

import SwiftUI
import AVFoundation

// MARK: - 预览面板

struct PreviewPanel: View {
    let state: EditorUiState
    let playhead: Double
    let onTogglePlay: () -> Void
    let onSeek: (Double) -> Void
    /// 播完最后一段的回调（对齐 Android onPlaybackEnded → 上层暂停，单遍不循环）
    let onPlaybackEnded: () -> Void

    init(state: EditorUiState, playhead: Double,
         onTogglePlay: @escaping () -> Void,
         onSeek: @escaping (Double) -> Void,
         onPlaybackEnded: @escaping () -> Void = {}) {
        self.state = state
        self.playhead = playhead
        self.onTogglePlay = onTogglePlay
        self.onSeek = onSeek
        self.onPlaybackEnded = onPlaybackEnded
    }

    var body: some View {
        let clips = state.project.sortedMainClips
        if clips.isEmpty {
            ZStack {
                Color.black
                Text("导入视频后在此预览").foregroundColor(.gray).font(.system(size: 14))
            }
        } else {
            PreviewContent(
                state: state, clips: clips, playhead: playhead,
                onTogglePlay: onTogglePlay, onSeek: onSeek,
                onPlaybackEnded: onPlaybackEnded)
        }
    }
}

private struct PreviewContent: View {
    let state: EditorUiState
    let clips: [Clip]
    let playhead: Double
    let onTogglePlay: () -> Void
    let onSeek: (Double) -> Void
    let onPlaybackEnded: () -> Void

    var body: some View {
        let zone = findTransitionZone(clips, playhead)
        let primary = zone?.next ?? clips.first {
            playhead >= $0.timelineStart - 0.05 && playhead < $0.timelineEnd - 0.05
        } ?? clips[0]

        ZStack {
            Color.black

            // 视频层（对齐 Android key(clip.id)：同一片段跨模式保留播放器实例，切片段时重建）
            if let zone = zone {
                VideoLayerView(
                    clip: zone.prev, role: .bottom, progress: zone.progress(playhead),
                    isPlaying: state.isPlaying, playhead: playhead, clips: clips,
                    drivesPlayhead: false, onSeek: onSeek, onPlaybackEnded: onPlaybackEnded)
                .id(zone.prev.id)
                VideoLayerView(
                    clip: zone.next, role: .top, progress: zone.progress(playhead),
                    transitionEffect: zone.prev.transition,
                    isPlaying: state.isPlaying, playhead: playhead, clips: clips,
                    drivesPlayhead: true, onSeek: onSeek, onPlaybackEnded: onPlaybackEnded)
                .id(zone.next.id)
            } else {
                VideoLayerView(
                    clip: primary, role: .solo, progress: 1,
                    isPlaying: state.isPlaying, playhead: playhead, clips: clips,
                    drivesPlayhead: true, onSeek: onSeek, onPlaybackEnded: onPlaybackEnded)
                .id(primary.id)
            }

            // 淡入白/黑遮罩
            if let z = zone, z.prev.transition == .fadewhite || z.prev.transition == .fadeblack {
                let p = z.progress(playhead)
                let alpha = 1 - abs(2 * p - 1)
                if alpha > 0.01 {
                    (z.prev.transition == .fadewhite ? Color.white : Color.black)
                        .opacity(alpha)
                        .ignoresSafeArea()
                }
            }

            // 去水印区域标识（红框，按时段显隐）
            WatermarkRegionOverlayView(clip: primary, playhead: playhead)

            // 画中画叠加（静态帧：视频用缩略图、图片用原图；导出为真实动态叠加）
            PipOverlaysView(state: state, playhead: playhead)

            // 字幕预览（当前时间生效的字幕）
            let activeSubs = state.project.subtitles.filter { playhead >= $0.startTime && playhead <= $0.endTime }
            ForEach(activeSubs) { sub in
                Text(sub.text)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundColor(.white)
                    .padding(.horizontal, 10).padding(.vertical, 4)
                    .background(Color.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 4))
                    .frame(maxHeight: .infinity, alignment: .bottom)
                    .padding(.bottom, 24)
            }

            // 预览保真度提示
            let unpreviewed: [String] = {
                var list: [String] = []
                if primary.reversed { list.append("倒放") }
                if primary.rotation != 0 || primary.hflip || primary.vflip { list.append("旋转/翻转") }
                if primary.textOverlay != nil { list.append("文字水印") }
                if primary.logoCutTime != nil { list.append("截断") }
                if primary.blurBgEnabled { list.append("模糊背景") }
                if !state.project.tracks.filter({ $0.type == .picture }).flatMap(\.clips).isEmpty { list.append("画中画动态画面") }
                return list
            }()
            if !unpreviewed.isEmpty {
                Text("预览不含\(unpreviewed.joined(separator: "/"))，以导出为准")
                    .font(.system(size: 10)).foregroundColor(Color(hex: 0xFFCCCCCC))
                    .padding(.horizontal, 8).padding(.vertical, 4)
                    .background(Capsule().fill(Color.black.opacity(0.6)))
                    .frame(maxHeight: .infinity, alignment: .top)
                    .padding(.top, 6)
            }

            // 时间（右上角小字）
            Text("\(timeStr(playhead)) / \(timeStr(state.project.totalDuration))")
                .font(.system(size: 10))
                .foregroundColor(Color.white.opacity(0.8))
                .padding(.horizontal, 6).padding(.vertical, 2)
                .background(Capsule().fill(Color.black.opacity(0.4)))
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                .padding(.top, 6).padding(.trailing, 8)

            // 暂停指示（纯展示、不可点：点按由外层统一处理；
            //   若用 Button 会与外层 onTapGesture 双重触发，toggle 两次等于没点）
            if !state.isPlaying {
                Image(systemName: "play.fill")
                    .font(.system(size: 28))
                    .foregroundColor(.white)
                    .frame(width: 60, height: 60)
                    .background(Circle().fill(Color.black.opacity(0.55)))
                    .allowsHitTesting(false)
            }
        }
        .contentShape(Rectangle())
        .onTapGesture { onTogglePlay() }
    }

    private func timeStr(_ s: Double) -> String {
        String(format: "%02d:%02d", Int(s) / 60, Int(s) % 60)
    }
}

// MARK: - 层角色

enum LayerRole {
    case solo, bottom, top
}

// MARK: - 单个视频层（一个 AVPlayer）

private struct VideoLayerView: View {
    let clip: Clip
    let role: LayerRole
    let progress: Double
    var transitionEffect: TransitionEffect? = nil
    let isPlaying: Bool
    let playhead: Double
    let clips: [Clip]
    let drivesPlayhead: Bool
    let onSeek: (Double) -> Void
    /// 最后一段播完的回调（对齐 Android onPlaybackEnded → 上层暂停）
    let onPlaybackEnded: () -> Void

    init(clip: Clip, role: LayerRole, progress: Double,
         transitionEffect: TransitionEffect? = nil,
         isPlaying: Bool, playhead: Double, clips: [Clip],
         drivesPlayhead: Bool,
         onSeek: @escaping (Double) -> Void,
         onPlaybackEnded: @escaping () -> Void = {}) {
        self.clip = clip
        self.role = role
        self.progress = progress
        self.transitionEffect = transitionEffect
        self.isPlaying = isPlaying
        self.playhead = playhead
        self.clips = clips
        self.drivesPlayhead = drivesPlayhead
        self.onSeek = onSeek
        self.onPlaybackEnded = onPlaybackEnded
    }

    @State private var player: AVPlayer? = nil
    @State private var timeObserver: Any? = nil
    @State private var boundaryToken: NSObjectProtocol? = nil
    @State private var lastSeekedPos: Double = 0
    /// 暂停态刮擦的尾帧补齐 work（拖动停顿后精准落最后一次位置）
    @State private var pendingScrubSeek: DispatchWorkItem? = nil
    /// 上一次限流直发 seek 的时间（限流窗口 ~80ms，保证拖动中预览持续跟手）
    @State private var lastSeekIssueAt: CFTimeInterval = 0
    /// ★ 易变输入的镜像：SwiftUI 合并下发变更时，事件闭包里直接读 let 属性可能拿到旧值，
    ///   一律在各 onChange 首行用新值刷新镜像，后续逻辑只读镜像/显式传参。
    @State private var latestPlayhead: Double = 0
    @State private var latestPlaying = false

    // 调色参数（预设+手动，与 FilterBuilder/Android 预览一致）
    private var filterBrightness: Float {
        Float(min(max(clip.filterPreset.brightness + clip.brightness, -1), 1))
    }
    private var filterContrast: Float {
        Float(min(max(clip.filterPreset.contrast + clip.contrast, -1), 1))
    }
    private var filterSaturation: Float {
        Float(min(max(clip.filterPreset.saturation + clip.saturation, -1), 1))
    }
    private var filterHue: Float {
        Float(clip.filterPreset.hue)
    }
    private var blurSigma: Float {
        clip.blurBgEnabled ? Float(min(max(clip.blurStrength, 2), 40)) / 4 : 0
    }
    private var hasFilter: Bool {
        filterBrightness != 0 || filterContrast != 0 || filterSaturation != 0 ||
        filterHue != 0 || blurSigma > 0
    }

    var body: some View {
        ZStack {
            // AVPlayerLayer 由 PlayerContainerView 承载
            PlayerContainerView(player: player)
                .ignoresSafeArea()
        }
        .modifier(TransitionTransform(effect: transitionEffect, progress: progress))
        .opacity(role == .bottom ? min(1 - progress, 1) : (role == .top ? progress : 1))
        .onAppear {
            if player == nil {
                let item = AVPlayerItem(url: URL(fileURLWithPath: clip.mediaPath))
                let p = AVPlayer(playerItem: item)
                p.rate = Float(clip.speed)
                p.isMuted = false
                applyFilters(to: item)
                // 初始定位到播放头对应的源时间
                let src = sourceTime(clip, playhead)
                p.seek(to: CMTime(seconds: src, preferredTimescale: 600))
                p.volume = role == .bottom ? Float(1 - progress) : (role == .top ? Float(progress) : 1)
                player = p
                lastSeekedPos = playhead
                latestPlayhead = playhead
                latestPlaying = isPlaying
                startDriver(p, driverPlaying: isPlaying, driverClip: clip, driverClips: clips)
                // ★ 对齐 Android playWhenReady=isPlaying：导入/草稿恢复时 isPlaying 已为 true，
                //   onChange 不会因初始值触发，这里创建即起播（bottom 转场层保持暂停，与 onChange 守卫一致）
                if isPlaying && (drivesPlayhead || role != .bottom) {
                    p.play()
                }
            }
        }
        .onChange(of: filterBrightness) { _ in applyFilters() }
        .onChange(of: filterContrast) { _ in applyFilters() }
        .onChange(of: filterSaturation) { _ in applyFilters() }
        .onChange(of: filterHue) { _ in applyFilters() }
        .onChange(of: blurSigma) { _ in applyFilters() }
        .onDisappear {
            pendingScrubSeek?.cancel()
            pendingScrubSeek = nil
            stopDriver()
            player?.pause()
            player = nil
        }
        .onChange(of: isPlaying) { playing in
            latestPlaying = playing
            guard drivesPlayhead || role != .bottom else { return }
            if playing {
                pendingScrubSeek?.cancel()
                pendingScrubSeek = nil
                if let pl = player {
                    startDriver(pl, driverPlaying: playing, driverClip: clip, driverClips: clips)
                }
                // ★ 起播对齐推迟到下一 runloop：本轮合并更新（含播完重播的 playhead 回 0）
                //   全部落定后再读镜像 seek。AVPlayer 停在 item 尾端时 play() 是空操作，
                //   不先离开尾端就会 timelines 回 0、画面冻尾帧、看起来像点不动。
                DispatchQueue.main.async {
                    guard self.latestPlaying else { return } // 极速又点停了就别复活
                    self.syncPlayer(to: self.latestPlayhead)
                    self.player?.play()
                }
            } else {
                player?.pause()
                // ★ 驱动闭包按本次新值重注册（对齐 Android LaunchedEffect(isPlaying, …) 重启语义）
                if let pl = player {
                    startDriver(pl, driverPlaying: playing, driverClip: clip, driverClips: clips)
                }
            }
        }
        // ★ 片段/轨道变化（裁剪/变速/增删）同样重注册，保证出点与下一段索引是最新的
        .onChange(of: clip) { newClip in
            if let pl = player {
                startDriver(pl, driverPlaying: latestPlaying, driverClip: newClip, driverClips: clips)
            }
        }
        .onChange(of: clips) { newClips in
            if let pl = player {
                startDriver(pl, driverPlaying: latestPlaying, driverClip: clip, driverClips: newClips)
            }
        }
        .onChange(of: progress) { p in
            // 转场音量交叉衰减
            if role == .bottom { player?.volume = Float(min(1 - p, 1)) }
            if role == .top { player?.volume = Float(min(p, 1)) }
        }
        .onChange(of: playhead) { pos in
            // 镜像先行（与 isPlaying 合并变更时顺序不定，后面的起播对齐只认镜像）
            latestPlayhead = pos
            // 暂停态：拖动时间轴重新定位（限流直发 + 尾帧补齐）
            schedulePausedSeek(to: pos)
        }
    }

    /// 暂停态刮擦 seek：限流直发 + 停顿后尾帧精准补齐。
    /// AVPlayer 精准 seek 单次几十毫秒，逐帧全发会互相排队、画面冻结（Android ExoPlayer 无此问题，
    /// 所以 Android 每帧直发即可）；但纯尾帧合并又会让拖动全程预览不动。
    /// 折中：事件间隔超过 ~80ms 就直发（拖动中预览以 ~12Hz 跟手），事件停顿 ~90ms 后补一次精准尾帧。
    private func schedulePausedSeek(to pos: Double) {
        guard !latestPlaying, pos != lastSeekedPos else { return }
        lastSeekedPos = pos
        let now = CFAbsoluteTimeGetCurrent()
        if now - lastSeekIssueAt > 0.08 {
            lastSeekIssueAt = now
            pendingScrubSeek?.cancel()
            pendingScrubSeek = nil
            issuePausedSeek(to: pos)
        } else {
            pendingScrubSeek?.cancel()
            let work = DispatchWorkItem {
                lastSeekIssueAt = CFAbsoluteTimeGetCurrent()
                issuePausedSeek(to: pos)
            }
            pendingScrubSeek = work
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.09, execute: work)
        }
    }

    private func issuePausedSeek(to pos: Double) {
        guard let pl = player else { return }
        let cur = pl.currentTime().seconds
        let src = min(max(sourceTime(clip, pos), clip.trimStart), endOf(clip))
        if abs(cur - src) > 0.03 {
            pl.seek(to: CMTime(seconds: src, preferredTimescale: 600),
                    toleranceBefore: .zero, toleranceAfter: .zero)
        }
    }

    /// 把播放器对齐到指定时间轴位置（显式传值；差值>0.1s 才 seek，避免高频抖动）
    private func syncPlayer(to timelinePos: Double) {
        let src = min(max(sourceTime(clip, timelinePos), clip.trimStart), endOf(clip))
        if let cur = player?.currentTime().seconds, abs(cur - src) > 0.1,
           let pl = player {
            pl.seek(to: CMTime(seconds: src, preferredTimescale: 600),
                    toleranceBefore: .zero, toleranceAfter: .zero)
        }
    }

    /// 启动播放驱动（10Hz 播放器位置 → 播放头 + 播完换段/收尾）。
    /// 易变输入全部显式传参快照（对齐 Android `LaunchedEffect(isPlaying, drivesPlayhead, clip.id)` 重启语义），
    /// 驱动闭包只捕获这些快照——事件闭包里直接读 let 属性可能拿到合并更新前的旧值。
    private func startDriver(_ p: AVPlayer, driverPlaying: Bool, driverClip: Clip, driverClips: [Clip]) {
        stopDriver()
        timeObserver = p.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 0.1, preferredTimescale: 600), queue: .main
        ) { [driverPlaying, driverClip, driverClips, drivesPlayhead, onSeek, onPlaybackEnded] time in
            // p.rate 实时读播放器：暂停瞬间的在途 tick 不再把播放头多推 0.1s
            guard drivesPlayhead, driverPlaying, p.rate != 0 else { return }
            let pos = time.seconds
            if driverClip.trimEnd > 0 && pos >= driverClip.trimEnd - 0.03 {
                let idx = driverClips.firstIndex { $0.id == driverClip.id } ?? -1
                if idx >= 0, idx < driverClips.count - 1 {
                    // 硬边界直接跳下一段起点
                    onSeek(driverClips[idx + 1].timelineStart)
                } else {
                    // ★ 对齐 Android：最后一段播完 → 停在结尾（单遍不循环），通知上层暂停
                    onSeek(driverClip.timelineEnd)
                    onPlaybackEnded()
                }
                return
            }
            let tl = min(max(driverClip.timelinePosOf(pos), driverClip.timelineStart), driverClip.timelineEnd)
            onSeek(tl)
        }
        // 自然播到文件尾的兜底（正常走上面的 trimEnd 预判，这个只在竞态时触发，同语义）
        boundaryToken = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime, object: p.currentItem, queue: .main
        ) { [driverClip, driverClips, drivesPlayhead, onSeek, onPlaybackEnded] _ in
            guard drivesPlayhead else { return }
            let idx = driverClips.firstIndex { $0.id == driverClip.id } ?? -1
            if idx >= 0, idx < driverClips.count - 1 {
                onSeek(driverClips[idx + 1].timelineStart)
            } else {
                onSeek(driverClip.timelineEnd)
                onPlaybackEnded()
            }
        }
    }

    /// 拆除播放驱动（重注册前与视图消失时调用，避免泄漏的旧观察者用过期快照回写播放头）
    private func stopDriver() {
        if let t = timeObserver { player?.removeTimeObserver(t); timeObserver = nil }
        if let token = boundaryToken { NotificationCenter.default.removeObserver(token); boundaryToken = nil }
    }
}

/// AVPlayerLayer 容器（UIViewRepresentable）
private struct PlayerContainerView: UIViewRepresentable {
    let player: AVPlayer?

    final class PlayerUIView: UIView {
        override static var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }

    func makeUIView(context: Context) -> PlayerUIView {
        let v = PlayerUIView()
        v.playerLayer.videoGravity = .resizeAspect
        return v
    }

    func updateUIView(_ uiView: PlayerUIView, context: Context) {
        // ★ 同一实例不重复设置：高频重组下反复 set player 会打断 paused-seek 的 preroll，
        //   画面永远落不了帧（Android 侧 PlayerView 只在实例变化时 setPlayer）
        if uiView.playerLayer.player !== player {
            uiView.playerLayer.player = player
        }
    }
}

// MARK: - 转场变换（上层近似动画）

struct TransitionTransform: ViewModifier {
    let effect: TransitionEffect?
    let progress: Double

    func body(content: Content) -> some View {
        let q = CGFloat(min(max(progress, 0), 1))
        GeometryReader { geo in
            let size = geo.size
            content
                .modifier(RawTransform(effect: effect, q: q, size: size))
                .opacity(effect == nil ? 1 : q)
        }
    }
}

private struct RawTransform: ViewModifier {
    let effect: TransitionEffect?
    let q: CGFloat
    let size: CGSize

    func body(content: Content) -> some View {
        switch effect {
        case .slideleft, .smoothleft:
            content.offset(x: size.width * (1 - q))
        case .slideright, .smoothright:
            content.offset(x: -size.width * (1 - q))
        case .slideup:
            content.offset(y: size.height * (1 - q))
        case .slidedown:
            content.offset(y: -size.height * (1 - q))
        case .wipeleft:
            content.clipShape(LeftWipeShape(progress: q))
        case .wiperight:
            content.clipShape(RightWipeShape(progress: q))
        case .wipeup:
            content.clipShape(TopWipeShape(progress: q))
        case .wipedown:
            content.clipShape(BottomWipeShape(progress: q))
        case .circleopen:
            content.clipShape(Circle().size(width: size.width * q, height: size.height * q)
                .offset(x: (size.width - size.width * q) / 2, y: (size.height - size.height * q) / 2))
        default:
            content
        }
    }
}

private struct LeftWipeShape: Shape {   // 从右向左展开
    let progress: CGFloat
    func path(in rect: CGRect) -> Path {
        Path(CGRect(x: rect.width * (1 - progress), y: 0, width: rect.width * progress, height: rect.height))
    }
}
private struct RightWipeShape: Shape {  // 从左向右展开
    let progress: CGFloat
    func path(in rect: CGRect) -> Path {
        Path(CGRect(x: 0, y: 0, width: rect.width * progress, height: rect.height))
    }
}
private struct TopWipeShape: Shape {    // 从下向上展开
    let progress: CGFloat
    func path(in rect: CGRect) -> Path {
        Path(CGRect(x: 0, y: rect.height * (1 - progress), width: rect.width, height: rect.height * progress))
    }
}
private struct BottomWipeShape: Shape { // 从上向下展开
    let progress: CGFloat
    func path(in rect: CGRect) -> Path {
        Path(CGRect(x: 0, y: 0, width: rect.width, height: rect.height * progress))
    }
}

// MARK: - 去水印区域红框

private struct WatermarkRegionOverlayView: View {
    let clip: Clip
    let playhead: Double

    var body: some View {
        let srcTime = clip.sourceTimeAt(playhead)
        let active = clip.watermarkRegions.filter { $0.activeAt(srcTime) }
        if !active.isEmpty {
            GeometryReader { geo in
                ForEach(Array(active.enumerated()), id: \.offset) { i, r in
                    Text("水印\(i + 1)")
                        .font(.system(size: 9)).foregroundColor(.white)
                        .padding(.horizontal, 3).padding(.vertical, 1)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                        .background(
                            RoundedRectangle(cornerRadius: 2)
                                .fill(Color.red.opacity(0.13))
                                .overlay(RoundedRectangle(cornerRadius: 2).stroke(Color.red, lineWidth: 1))
                        )
                        .frame(width: geo.size.width * r.w, height: geo.size.height * r.h)
                        .offset(x: geo.size.width * r.x, y: geo.size.height * r.y)
                }
            }
            .allowsHitTesting(false)
        }
    }
}

// MARK: - 辅助

private func endOf(_ clip: Clip) -> Double {
    clip.trimEnd > 0 ? clip.trimEnd : clip.mediaDuration
}

/// 时间轴位置 → 预览源时间（倒放按正放近似映射）
private func sourceTime(_ clip: Clip, _ timelinePos: Double) -> Double {
    if clip.reversed {
        return clip.trimStart + (timelinePos - clip.timelineStart) * clip.speed
    }
    return min(max(clip.sourceTimeAt(timelinePos), clip.trimStart), endOf(clip))
}

// MARK: - 画中画叠加预览（静态帧）

private struct PipOverlaysView: View {
    let state: EditorUiState
    let playhead: Double

    var body: some View {
        let pipClips = state.project.tracks
            .filter { $0.type == .picture }
            .flatMap { $0.clips }
            .filter { $0.pipEnabled && playhead >= $0.timelineStart - 0.05 && playhead < $0.timelineEnd - 0.05 }
        if pipClips.isEmpty { return AnyView(EmptyView()) }
        return AnyView(
            GeometryReader { geo in
                ForEach(Array(pipClips.enumerated()), id: \.element.id) { _, pip in
                    let srcPath = pip.isImage ? pip.mediaPath : pip.thumbnailPath
                    if let srcPath = srcPath, let img = UIImage(contentsOfFile: srcPath) {
                        let pipW = geo.size.width * pip.pipWidth
                        let pipH = pipW * (img.size.height / max(img.size.width, 1))
                        let (nx, ny) = interpolatePipPosition(pip.pipKeyframes, playhead, pip.pipX, pip.pipY)
                        let shape: AnyShape = {
                            switch pip.pipShape {
                            case .circle: return AnyShape(Circle())
                            case .rounded:
                                return AnyShape(RoundedRectangle(cornerRadius: pipW * pip.pipCornerRadius))
                            default: return AnyShape(Rectangle())
                            }
                        }()
                        Image(uiImage: img)
                            .resizable()
                            .scaledToFill()
                            .frame(width: pipW, height: pipH)
                            .clipShape(shape)
                            .overlay(
                                shape.stroke(Color.white, lineWidth: pip.pipBorder ? geo.size.width * pip.pipBorderWidth : 0)
                            )
                            .opacity(pip.pipOpacity)
                            .offset(x: (geo.size.width - pipW) * nx, y: (geo.size.height - pipH) * ny)
                            .allowsHitTesting(false)
                    }
                }
            }
        )
    }
}

// MARK: - 实时调色（AVVideoComposition + CIFilter，与导出 eq/huesaturation 近似）

extension VideoLayerView {

    fileprivate func applyFilters(to item: AVPlayerItem? = nil) {
        guard let item = item ?? player?.currentItem else { return }
        guard hasFilter else {
            item.videoComposition = nil
            return
        }
        let brightness = filterBrightness
        let contrast = 1 + filterContrast
        let saturation = 1 + filterSaturation
        let hue = filterHue
        let blur = blurSigma

        // asset 初始化：自动带 renderSize/帧率
        let asset = AVURLAsset(url: URL(fileURLWithPath: clip.mediaPath))
        let composition = AVMutableVideoComposition(asset: asset, applyingCIFiltersWithHandler: { request in
            var image = request.sourceImage
            // 模糊背景（整帧近似，与 Android 预览策略一致；导出按 split/overlay 精确处理）
            if blur > 0 {
                if let f = CIFilter(name: "CIGaussianBlur") {
                    f.setValue(image, forKey: kCIInputImageKey)
                    f.setValue(blur, forKey: kCIInputRadiusKey)
                    if let out = f.outputImage { image = out.cropped(to: request.sourceImage.extent) }
                }
            }
            if brightness != 0 || contrast != 1 || saturation != 1 {
                if let f = CIFilter(name: "CIColorControls") {
                    f.setValue(image, forKey: kCIInputImageKey)
                    f.setValue(saturation, forKey: "inputSaturation")
                    f.setValue(contrast, forKey: "inputContrast")
                    f.setValue(brightness, forKey: "inputBrightness")
                    if let out = f.outputImage { image = out }
                }
            }
            if hue != 0 {
                if let f = CIFilter(name: "CIHueAdjust") {
                    f.setValue(image, forKey: kCIInputImageKey)
                    f.setValue(hue, forKey: kCIInputAngleKey)
                    if let out = f.outputImage { image = out }
                }
            }
            request.finish(with: image, context: nil)
        })
        item.videoComposition = composition
    }
}
