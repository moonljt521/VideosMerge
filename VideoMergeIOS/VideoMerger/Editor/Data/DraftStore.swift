//
//  DraftStore.swift
//  VideoMerger
//
//  iOS 移植自 Android DraftStore.kt
//  编辑器草稿存储 —— 单槽位自动保存（JSON），返回首页不丢内容
//

import Foundation

enum DraftStore {

    struct DraftInfo {
        let name: String
        let clipCount: Int
        let subtitleCount: Int
        let updatedAt: Double
    }

    static var draftURL: URL {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("editor_draft", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("current.json")
    }

    static func save(_ project: EditorProject) {
        do {
            let data = try JSONEncoder().encode(project)
            try data.write(to: draftURL, options: .atomic)
        } catch {
            print("[DraftStore] 保存失败: \(error)")
        }
    }

    static func load() -> EditorProject? {
        guard let data = try? Data(contentsOf: draftURL) else { return nil }
        return try? JSONDecoder().decode(EditorProject.self, from: data)
    }

    static func clear() {
        try? FileManager.default.removeItem(at: draftURL)
    }

    /// 首页展示用轻量信息；无有效草稿返回 nil
    static func peek() -> DraftInfo? {
        guard let p = load() else { return nil }
        let clipCount = p.mainTrack?.clips.count ?? 0
        if clipCount == 0 && p.subtitles.isEmpty { return nil }
        return DraftInfo(name: p.name, clipCount: clipCount,
                         subtitleCount: p.subtitles.count, updatedAt: p.updatedAt)
    }
}
