//
//  VideoMergerApp.swift
//  VideoMerger
//
//  iOS 应用入口
//

import SwiftUI
import AVFoundation
import FirebaseCore
import ffmpegkit

@main
struct VideoMergerApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    var body: some Scene {
        WindowGroup {
            HomeView()
        }
    }
}

/// AppDelegate —— 初始化 FFmpegKit、Firebase、音频会话、相册权限
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        // AV_LOG_INFO = 32
        FFmpegKitConfig.setLogLevel(32)
        print("FFmpegKit initialized: \(FFmpegKitConfig.getFFmpegVersion() ?? "?")")

        // Firebase（Crashlytics + Analytics）：配置文件缺失时跳过，避免启动崩溃
        if Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist") != nil {
            FirebaseApp.configure()
        } else {
            print("[Firebase] GoogleService-Info.plist 未打包，跳过初始化")
        }

        // 设置音频会话为 .playback：
        // - 不受静音键影响（视频合并预览应该有声音）
        // - 默认 .ambient 会被静音键静音，导致 App 内播放视频无声
        do {
            try AVAudioSession.sharedInstance().setCategory(
                .playback,
                mode: .moviePlayback,
                options: []
            )
            try AVAudioSession.sharedInstance().setActive(true)
            print("AVAudioSession category set to .playback")
        } catch {
            print("AVAudioSession 设置失败: \(error)")
        }

        return true
    }
}
