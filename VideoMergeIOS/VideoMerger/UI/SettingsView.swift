//
//  SettingsView.swift
//  VideoMerger
//
//  设置页：语言 / 广告隐私选项 / 跟踪授权 / 隐私政策 / 反馈 / 版本 / 数据清理
//

import SwiftUI
import AppTrackingTransparency
import UIKit

struct SettingsView: View {
    var onBack: () -> Void
    /// 清除草稿后同步首页角标（HomeView 重新 peek）
    var onDraftCleared: (() -> Void)? = nil

    @ObservedObject private var ads = AdsManager.shared
    @ObservedObject private var language = LanguageManager.shared
    @Environment(\.openURL) private var openURL

    @State private var trackingStatus = ATTrackingManager.trackingAuthorizationStatus
    @State private var showClearDraftAlert = false
    @State private var showClearHistoryAlert = false
    @State private var showLanguageDialog = false
    @State private var toast: String? = nil

    private let privacyPolicyURL = URL(string: "https://moonljt521.github.io/VideosMerge/")!
    private let feedbackURL = URL(string: "https://github.com/moonljt521/VideosMerge/issues")!

    private var appVersion: String {
        let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
        let b = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "1"
        return "\(v) (\(b))"
    }

    private var languageDisplayName: String {
        switch language.current {
        case LanguageManager.zhHans: return L10n.t("settings.language.zh")
        case LanguageManager.en: return L10n.t("settings.language.en")
        default: return L10n.t("settings.language.system")
        }
    }

    private var trackingDetail: String {
        switch trackingStatus {
        case .authorized: return L10n.t("settings.tracking.allowed")
        case .denied: return L10n.t("settings.tracking.denied")
        case .restricted: return L10n.t("settings.tracking.restricted")
        default: return L10n.t("settings.tracking.not_determined")
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            // 顶栏（与历史记录页一致）
            HStack {
                Button(action: onBack) {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 18, weight: .semibold))
                }
                Text(L10n.t("settings.title"))
                    .font(.headline)
                    .fontWeight(.bold)
                Spacer()
            }
            .padding()
            .background(Color(.secondarySystemBackground))

            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    privacySection
                    generalSection
                    dataSection
                }
                .padding(.vertical, 16)
            }
        }
        .background(Color.black.ignoresSafeArea())
        .preferredColorScheme(.dark)
        .overlay(alignment: .bottom) {
            if let toast {
                Text(toast)
                    .font(.system(size: 13))
                    .foregroundColor(.white)
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .background(Capsule().fill(Color(hex: 0xFF323232).opacity(0.95)))
                    .padding(.bottom, 30)
                    .task {
                        try? await Task.sleep(nanoseconds: 2_000_000_000)
                        self.toast = nil
                    }
            }
        }
        .confirmationDialog(L10n.t("settings.language"), isPresented: $showLanguageDialog, titleVisibility: .visible) {
            Button(L10n.t("settings.language.system")) { setLanguage(LanguageManager.system) }
            Button(L10n.t("settings.language.zh")) { setLanguage(LanguageManager.zhHans) }
            Button(L10n.t("settings.language.en")) { setLanguage(LanguageManager.en) }
            Button(L10n.t("settings.cancel"), role: .cancel) {}
        }
        .alert(L10n.t("settings.clear_draft.alert_title"), isPresented: $showClearDraftAlert) {
            Button(L10n.t("settings.confirm_clear"), role: .destructive) {
                DraftStore.clear()
                onDraftCleared?()
                toast = L10n.t("settings.toast.draft_cleared")
            }
            Button(L10n.t("settings.cancel"), role: .cancel) {}
        } message: {
            Text(L10n.t("settings.clear_draft.alert_message"))
        }
        .alert(L10n.t("settings.clear_history.alert_title"), isPresented: $showClearHistoryAlert) {
            Button(L10n.t("settings.confirm_clear"), role: .destructive) {
                let all = VideoHistoryStore.loadHistory()
                for entry in all { VideoHistoryStore.deleteEntry(entry: entry) }
                toast = all.isEmpty
                    ? L10n.t("settings.toast.history_empty")
                    : L10n.t("settings.toast.history_cleared_fmt", all.count)
            }
            Button(L10n.t("settings.cancel"), role: .cancel) {}
        } message: {
            Text(L10n.t("settings.clear_history.alert_message"))
        }
    }

    /// 切换语言：持久化后由根视图 .id(resolvedCode) 触发整树重建
    private func setLanguage(_ code: String) {
        guard code != language.current else { return }
        language.current = code
    }

    // MARK: - 分区视图（拆小以降低 SwiftUI 类型检查复杂度）

    @ViewBuilder private var privacySection: some View {
        sectionHeader(L10n.t("settings.section.ads_privacy"))

        // 广告隐私选项：UMP 拉到同意配置后才展示（GDPR / 美国州级法规"改主意"入口）
        if ads.canManagePrivacy {
            row(icon: "hand.raised.fill", iconColor: Color(hex: 0xFF42A5F5),
                title: L10n.t("settings.privacy_options"),
                subtitle: L10n.t("settings.privacy_options.subtitle")) {
                ads.presentPrivacyOptions()
            }
        }

        // ATT 授权状态 + 跳系统设置（ATT 只弹一次，拒绝后唯一修改途径）
        row(icon: "app.badge.fill", iconColor: Color(hex: 0xFFFFA726),
            title: L10n.t("settings.tracking"),
            subtitle: trackingDetail,
            trailing: AnyView(
                Group {
                    if trackingStatus == .authorized {
                        Image(systemName: "checkmark").foregroundColor(.green)
                    } else {
                        Image(systemName: "chevron.right").foregroundColor(.gray)
                    }
                }
            )) {
            trackingStatus = ATTrackingManager.trackingAuthorizationStatus
            if trackingStatus != .authorized, trackingStatus != .restricted,
               let url = URL(string: UIApplication.openSettingsURLString) {
                openURL(url)
            }
        }
        .onAppear { trackingStatus = ATTrackingManager.trackingAuthorizationStatus }
    }

    @ViewBuilder private var generalSection: some View {
        sectionHeader(L10n.t("settings.section.general"))

        row(icon: "globe", iconColor: Color(hex: 0xFF26C6DA),
            title: L10n.t("settings.language"),
            subtitle: L10n.t("settings.language.subtitle_fmt", languageDisplayName),
            trailing: AnyView(Image(systemName: "chevron.right")
                .font(.system(size: 13))
                .foregroundColor(Color(hex: 0xFF555555)))) {
            showLanguageDialog = true
        }

        row(icon: "doc.text.fill", iconColor: Color(hex: 0xFF66BB6A),
            title: L10n.t("settings.privacy_policy"),
            subtitle: L10n.t("settings.privacy_policy.subtitle")) {
            openURL(privacyPolicyURL)
        }

        row(icon: "envelope.fill", iconColor: Color(hex: 0xFFAB47BC),
            title: L10n.t("settings.feedback"),
            subtitle: L10n.t("settings.feedback.subtitle")) {
            openURL(feedbackURL)
        }

        row(icon: "info.circle.fill", iconColor: Color(hex: 0xFF78909C),
            title: L10n.t("settings.version"),
            subtitle: L10n.t("settings.app_name"),
            trailing: AnyView(Text(appVersion)
                .font(.system(size: 13))
                .foregroundColor(Color(hex: 0xFF888888)))) {}
    }

    @ViewBuilder private var dataSection: some View {
        sectionHeader(L10n.t("settings.section.data"))

        row(icon: "trash.fill", iconColor: Color(hex: 0xFFEF5350),
            title: L10n.t("settings.clear_draft"),
            subtitle: L10n.t("settings.clear_draft.subtitle")) {
            showClearDraftAlert = true
        }

        row(icon: "clock.arrow.circlepath", iconColor: Color(hex: 0xFFEF5350),
            title: L10n.t("settings.clear_history"),
            subtitle: L10n.t("settings.clear_history.subtitle")) {
            showClearHistoryAlert = true
        }
    }

    private func sectionHeader(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 13, weight: .semibold))
            .foregroundColor(Color(hex: 0xFF888888))
            .padding(.horizontal, 20)
    }

    private func row(icon: String, iconColor: Color, title: String,
                     subtitle: String, trailing: AnyView? = nil,
                     action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .font(.system(size: 18))
                    .foregroundColor(iconColor)
                    .frame(width: 26)
                VStack(alignment: .leading, spacing: 3) {
                    Text(title)
                        .font(.system(size: 15, weight: .medium))
                        .foregroundColor(.white)
                    Text(subtitle)
                        .font(.system(size: 12))
                        .foregroundColor(Color(hex: 0xFF888888))
                }
                Spacer()
                trailing ?? AnyView(Image(systemName: "chevron.right")
                    .font(.system(size: 13))
                    .foregroundColor(Color(hex: 0xFF555555)))
            }
            .padding(.horizontal, 16)
            .frame(height: 64)
            .background(RoundedRectangle(cornerRadius: 14).fill(Color(hex: 0xFF1A1A1A)))
        }
        .padding(.horizontal, 16)
    }
}
