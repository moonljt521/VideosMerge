//
//  L10n.swift
//  VideoMerger
//
//  轻量级应用内多语言基础设施：
//  - LanguageManager：语言偏好持久化（system / zh-Hans / en）
//  - L10n.t：按当前语言查各模块词典（zh 词条为键的来源，en 缺失时回退中文）
//  - 各模块词典分散在 L10n*.swift（L10nHome / L10nEditorUI / ...），此处统一注册
//

import Foundation
import SwiftUI

// MARK: - 语言管理器

final class LanguageManager: ObservableObject {
    static let shared = LanguageManager()

    static let storageKey = "app_language"
    static let system = "system"
    static let zhHans = "zh-Hans"
    static let en = "en"

    /// 当前选择：system（跟随系统）/ zh-Hans / en
    @Published var current: String {
        didSet { UserDefaults.standard.set(current, forKey: Self.storageKey) }
    }

    private init() {
        current = UserDefaults.standard.string(forKey: Self.storageKey) ?? Self.system
    }

    /// 解析后的实际语言代码（"system" 时按系统首选语言判断）
    var resolvedCode: String {
        switch current {
        case Self.zhHans, Self.en:
            return current
        default:
            let preferred = Bundle.main.preferredLocalizations.first ?? "en"
            return preferred.hasPrefix("zh") ? Self.zhHans : Self.en
        }
    }

    var isChinese: Bool { resolvedCode == Self.zhHans }
}

// MARK: - 词典查询

enum L10n {

    /// 各模块词典注册表（新增模块词典后在此登记）
    static let tables: [L10nModuleTable] = [
        L10nSettings.table,
        L10nHome.table,
        L10nEditorUI.table,
        L10nEditorCore.table,
        L10nFeatures.table,
    ]

    /// 取文案：优先命中注册表的 zh 键；en 缺失时回退中文；完全未注册时原样返回键
    static func t(_ key: String, _ args: CVarArg...) -> String {
        for module in tables {
            guard let zh = module.zh[key] else { continue }
            let format = LanguageManager.shared.isChinese ? zh : (module.en[key] ?? zh)
            return args.isEmpty ? format : String(format: format, arguments: args)
        }
        return key
    }
}

/// 模块词典载体：zh 词典的键必须与 en 词典键完全一致
typealias L10nModuleTable = (zh: [String: String], en: [String: String])
