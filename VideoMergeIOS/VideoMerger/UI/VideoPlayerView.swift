//
//  VideoPlayerView.swift
//  VideoMerger
//
//  iOS 移植自 Android VideoMerger 的 VideoPlayer.kt
//  - InlineVideoPlayer: 小窗口预览（点击全屏 / 长按保存）
//  - FullscreenVideoPlayer: 全屏播放
//

import SwiftUI
import AVKit

/// 内联视频播放器（小窗口预览）
struct InlineVideoPlayer: View {
    let videoURL: URL
    var onTap: () -> Void = {}
    var onLongPress: () -> Void = {}

    @State private var player: AVPlayer?

    var body: some View {
        GeometryReader { geo in
            ZStack {
                if let player = player {
                    VideoPlayerLayer(player: player)
                        .frame(width: geo.size.width, height: geo.size.height)
                        .clipped()
                } else {
                    Color.black
                }

                Text("点击全屏播放 · 长按保存到相册")
                    .font(.system(size: 11))
                    .foregroundColor(.white)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 2)
                    .background(Color.black.opacity(0.5))
                    .cornerRadius(4)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
                    .padding(.bottom, 8)
            }
            .background(Color.black)
            .cornerRadius(12)
            .contentShape(Rectangle())
            .onTapGesture { onTap() }
            .onLongPressGesture(minimumDuration: 0.6, perform: onLongPress)
        }
        .onAppear {
            let item = AVPlayerItem(url: videoURL)
            let p = AVPlayer(playerItem: item)
            p.actionAtItemEnd = .none
            p.isMuted = false
            p.play()
            // 循环播放
            NotificationCenter.default.addObserver(
                forName: .AVPlayerItemDidPlayToEndTime,
                object: item,
                queue: .main
            ) { _ in
                p.seek(to: .zero)
                p.play()
            }
            player = p
        }
        .onDisappear {
            player?.pause()
            if let item = player?.currentItem {
                NotificationCenter.default.removeObserver(
                    self, name: .AVPlayerItemDidPlayToEndTime, object: item)
            }
            player = nil
        }
    }
}

/// 全屏视频播放器
struct FullscreenVideoPlayer: View {
    let videoURL: URL
    var onSaveClick: () -> Void
    var onDismiss: () -> Void

    @State private var player: AVPlayer?

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if let player = player {
                // SwiftUI 内置 VideoPlayer：自带播放/暂停、进度拖动、时间显示等控件
                VideoPlayer(player: player)
                    .ignoresSafeArea()
            }

            // 顶部按钮栏（关闭 / 保存）
            // 放在最上层，避免被 VideoPlayer 的控件遮挡
            VStack {
                HStack {
                    Button(action: onDismiss) {
                        Image(systemName: "xmark")
                            .font(.system(size: 18, weight: .bold))
                            .foregroundColor(.white)
                            .frame(width: 44, height: 44)
                            .background(Color.black.opacity(0.5))
                            .clipShape(Circle())
                    }
                    Spacer()
                    Button(action: onSaveClick) {
                        Image(systemName: "square.and.arrow.down")
                            .font(.system(size: 18, weight: .bold))
                            .foregroundColor(.white)
                            .frame(width: 44, height: 44)
                            .background(Color.black.opacity(0.5))
                            .clipShape(Circle())
                    }
                }
                .padding(16)
                Spacer()
            }
        }
        .onAppear {
            let item = AVPlayerItem(url: videoURL)
            let p = AVPlayer(playerItem: item)
            p.play()
            NotificationCenter.default.addObserver(
                forName: .AVPlayerItemDidPlayToEndTime,
                object: item,
                queue: .main
            ) { _ in
                p.seek(to: .zero)
                p.play()
            }
            player = p
        }
        .onDisappear {
            player?.pause()
            if let item = player?.currentItem {
                NotificationCenter.default.removeObserver(
                    self, name: .AVPlayerItemDidPlayToEndTime, object: item)
            }
            player = nil
        }
    }
}

/// UIViewRepresentable 包装 AVPlayerLayer
private struct VideoPlayerLayer: UIViewRepresentable {
    let player: AVPlayer

    func makeUIView(context: Context) -> PlayerUIView {
        PlayerUIView(player: player)
    }

    func updateUIView(_ uiView: PlayerUIView, context: Context) {
        uiView.player = player
    }
}

private final class PlayerUIView: UIView {
    var player: AVPlayer? {
        didSet { playerLayer.player = player }
    }
    private let playerLayer = AVPlayerLayer()

    init(player: AVPlayer) {
        super.init(frame: .zero)
        self.player = player
        playerLayer.player = player
        playerLayer.videoGravity = .resizeAspect
        layer.addSublayer(playerLayer)
        backgroundColor = .black
    }

    required init?(coder: NSCoder) { fatalError() }

    override func layoutSubviews() {
        super.layoutSubviews()
        playerLayer.frame = bounds
    }
}
