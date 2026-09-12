//
//  AdSupport.swift
//  VideoMerger
//
//  商业化基础设施（iOS 发布专用）：
//  - RemoteConfig: 拉取 GitHub Pages 上的 app-config.json（功能开关/广告位），失败用内置默认值兜底，
//    解析接口变更时可改远端 JSON 热修，无需等 App Store 审核
//  - AdsManager: UMP 同意流程 → ATT → 初始化 GoogleMobileAds → App 开屏广告（冷启 + 回前台，带冷却）
//  - BannerAdView: 首页底部自适应横幅
//  - AppAnalytics: Firebase Analytics 关键漏斗埋点（Crashlytics 由 SDK 自动收集，无需埋点）
//
//  依赖：GoogleMobileAds、FirebaseAnalytics/FirebaseCrashlytics（Podfile，v11+ Swift API；UMP 随其依赖自动引入）
//

import SwiftUI
import UIKit
import GoogleMobileAds
import GoogleUserMessagingPlatform
import AppTrackingTransparency
import FirebaseAnalytics

// MARK: - 关键漏斗埋点

enum AppAnalytics {
    private static func log(_ name: String, _ params: [String: Any] = [:]) {
        Analytics.logEvent(name, parameters: params.isEmpty ? nil : params)
    }

    // 抖音解析漏斗：start → success/error → save → share
    static func douyinParseStart() { log("douyin_parse_start") }
    static func douyinParseSuccess(durationMs: Int) {
        log("douyin_parse_success", ["duration_ms": durationMs])
    }
    static func douyinParseError(_ message: String) {
        log("douyin_parse_error", ["message": String(message.prefix(100))])
    }
    static func douyinSaveSuccess() { log("douyin_save_success") }
    static func douyinShareTap() { log("douyin_share_tap") }

    // 其他核心功能
    static func mergeExportSuccess(_ mergeType: String) {
        log("merge_export_success", ["merge_type": mergeType])
    }
    static func gifExportSuccess() { log("gif_export_success") }
    static func editorExportSuccess() { log("editor_export_success") }
}

// MARK: - 广告位配置

enum AdConfig {
    // 正式广告位（海外全球区）
    static let appOpenUnitID = "ca-app-pub-5604418926465302/1707029953"
    static let bannerUnitID = "ca-app-pub-5604418926465302/2780080651"

    /// 开屏广告展示冷却（秒）：回前台不每次都弹，避免打断
    static let appOpenCooldown: TimeInterval = 180
    /// 开屏广告有效期（秒），超时重新加载
    static let appOpenValidDuration: TimeInterval = 4 * 3600
}

// MARK: - 远程配置

final class RemoteConfig: ObservableObject {
    static let shared = RemoteConfig()

    /// 与仓库 docs/app-config.json 对应；GitHub Pages 开启后即可访问
    private static let configURL = URL(
        string: "https://moonljt521.github.io/VideosMerge/app-config.json"
    )!

    /// 抖音解析入口开关：远端置 false 可整体降级该功能（App Store 合规预案）
    @Published var douyinParserEnabled: Bool = true
    /// 广告总开关：远端可一键关闭全部广告
    @Published var adsEnabled: Bool = true

    private var lastFetchAt: Date?

    /// 冷启/回前台拉取，每小时最多一次；失败静默（保持默认值）
    func refreshIfNeeded() {
        if let last = lastFetchAt, Date().timeIntervalSince(last) < 3600 { return }
        lastFetchAt = Date()

        var request = URLRequest(url: Self.configURL)
        request.timeoutInterval = 3
        URLSession.shared.dataTask(with: request) { data, _, _ in
            guard let data,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let flags = json["feature_flags"] as? [String: Any] else { return }
            DispatchQueue.main.async {
                if let v = flags["douyin_parser_enabled"] as? Bool {
                    self.douyinParserEnabled = v
                }
                if let v = flags["ads_enabled"] as? Bool {
                    self.adsEnabled = v
                }
            }
        }.resume()
    }
}

// MARK: - 广告管理

final class AdsManager: NSObject, ObservableObject {
    static let shared = AdsManager()

    /// SDK 完成初始化且广告可用（首页横幅据此显示）
    @Published private(set) var bannerEnabled = false

    private var appOpenAd: AppOpenAd?
    private var appOpenLoadedAt: Date?
    private var lastShownAt: Date?
    private var isShowingAppOpen = false
    private var didBootstrap = false

    private override init() {
        super.init()
        // 回前台（含冷启首帧后）：首次触发走同意+初始化，之后触发展示开屏
        NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.onActive()
        }
    }

    /// 首次进入首页时也要调一次：防止冷启时 didBecomeActive 早于视图创建而错过 bootstrap
    func onActive() {
        if !didBootstrap {
            bootstrap()
            return
        }
        showAppOpenIfAvailable()
    }

    // MARK: 同意与初始化

    private func bootstrap() {
        didBootstrap = true
        let params = ConsentRequestParameters()
        ConsentInformation.shared.requestConsentInfoUpdate(with: params) { error in
            if let error {
                // 拿不到同意信息（如无网络）也继续，SDK 会按非个性化广告处理
                print("[Ads] consent info error: \(error.localizedDescription)")
            }
            // 有 GDPR 表单则弹出（需在 AdMob 后台 Privacy & messaging 里创建），没有则直接过
            ConsentForm.loadAndPresentConsentFormIfRequired(from: nil) { formError in
                if let formError {
                    print("[Ads] consent form error: \(formError.localizedDescription)")
                }
                DispatchQueue.main.async { self.requestATTThenStart() }
            }
        }
    }

    private func requestATTThenStart() {
        guard ConsentInformation.shared.canRequestAds else { return }
        ATTrackingManager.requestTrackingAuthorization { _ in
            DispatchQueue.main.async {
                MobileAds.shared.start { _ in
                    DispatchQueue.main.async {
                        self.bannerEnabled = true
                        self.preloadAppOpen()
                    }
                }
            }
        }
    }

    // MARK: App 开屏广告

    private func preloadAppOpen() {
        guard RemoteConfig.shared.adsEnabled else { return }
        AppOpenAd.load(with: AdConfig.appOpenUnitID, request: Request()) { [weak self] ad, error in
            if let error {
                print("[Ads] app open load error: \(error.localizedDescription)")
                return
            }
            ad?.fullScreenContentDelegate = self
            self?.appOpenAd = ad
            self?.appOpenLoadedAt = Date()
        }
    }

    private func showAppOpenIfAvailable() {
        guard !isShowingAppOpen,
              RemoteConfig.shared.adsEnabled,
              let ad = appOpenAd,
              let loadedAt = appOpenLoadedAt,
              Date().timeIntervalSince(loadedAt) < AdConfig.appOpenValidDuration
        else {
            // 过期则换新的
            if appOpenAd != nil { preloadAppOpen() }
            return
        }
        if let last = lastShownAt,
           Date().timeIntervalSince(last) < AdConfig.appOpenCooldown {
            return
        }
        isShowingAppOpen = true
        ad.present(from: nil)
    }

    /// 通知 banner 重载（远端开关变化时由 HomeView 调用）
    func reloadBannerIfPossible() {
        guard bannerEnabled else { return }
        NotificationCenter.default.post(name: Notification.Name("AdsReloadBanner"), object: nil)
    }
}

extension AdsManager: FullScreenContentDelegate {
    func adDidDismissFullScreenContent(_ ad: FullScreenPresentingAd) {
        isShowingAppOpen = false
        lastShownAt = Date()
        preloadAppOpen()
    }

    func ad(_ ad: FullScreenPresentingAd, didFailToPresentFullScreenContentWithError error: Error) {
        isShowingAppOpen = false
        preloadAppOpen()
    }
}

// MARK: - 首页横幅

struct BannerAdView: UIViewRepresentable {
    static var preferredHeight: CGFloat {
        GADCurrentOrientationAnchoredAdaptiveBanner(width: UIScreen.main.bounds.width).size.height
    }

    func makeUIView(context: Context) -> GADBannerView {
        let banner = GADBannerView(
            adSize: GADCurrentOrientationAnchoredAdaptiveBanner(width: UIScreen.main.bounds.width)
        )
        banner.adUnitID = AdConfig.bannerUnitID
        banner.rootViewController = Self.topViewController()
        banner.load(Request())
        return banner
    }

    func updateUIView(_ uiView: GADBannerView) {}

    private static func topViewController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first
        return scene?.keyWindow?.rootViewController
    }
}
