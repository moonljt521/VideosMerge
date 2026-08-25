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
            // ★ 绝对路径 → 相对路径：iOS 重装/更新后容器 UUID 会变，
            //   绝对路径全部失效；相对路径加载时映射回当前容器
            var p = project
            mapPaths(&p) { base, path in
                guard path.hasPrefix(base) else { return path }
                return "editor_media" + path.dropFirst(base.count)
            }
            let data = try JSONEncoder().encode(p)
            try data.write(to: draftURL, options: .atomic)
        } catch {
            print("[DraftStore] 保存失败: \(error)")
        }
    }

    static func load() -> EditorProject? {
        guard let data = try? Data(contentsOf: draftURL) else { return nil }
        do {
            var project = try JSONDecoder().decode(EditorProject.self, from: data)
            // 相对路径 → 当前容器绝对路径
            mapPaths(&project) { base, path in
                // ★ base 已是 editor_media 目录，去掉相对路径的前缀再拼，避免重复
                guard path.hasPrefix("editor_media/") else { return path }
                return base + path.dropFirst("editor_media".count)
            }
            let clipCount = project.tracks.map(\.clips.count).reduce(0,+)
            print("[DraftStore] 解码成功 clips=\(clipCount)")
            return project
        } catch {
            print("[DraftStore] 解码失败: \(error)")
            return nil
        }
    }

    /// 对项目中所有媒体文件路径做映射（mediaPath/thumbnailPath/imageWatermarkPath）
    private static func mapPaths(_ project: inout EditorProject, _ transform: (String, String) -> String) {
        let base = EditorViewModel.editorMediaDir.path
        func map(_ path: String?) -> String? {
            guard let path = path else { return nil }
            return transform(base, path)
        }
        for ti in project.tracks.indices {
            for ci in project.tracks[ti].clips.indices {
                project.tracks[ti].clips[ci].mediaPath = map(project.tracks[ti].clips[ci].mediaPath) ?? ""
                project.tracks[ti].clips[ci].thumbnailPath = map(project.tracks[ti].clips[ci].thumbnailPath)
                project.tracks[ti].clips[ci].imageWatermarkPath = map(project.tracks[ti].clips[ci].imageWatermarkPath)
            }
        }
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
