//
//  VideoHistoryStore.swift
//  VideoMerger
//
//  iOS 移植自 Android VideoMerger 的 VideoHistoryStore.kt
//
//  文件存放在 Documents/merged_videos/ 下：
//    - merged_<timestamp>.mp4   视频文件
//    - thumb_<timestamp>.jpg    缩略图
//    - history.json             元数据索引
//

import Foundation

enum VideoHistoryStore {

    // MARK: - 路径

    /// 历史目录
    static var dir: URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        let d = docs.appendingPathComponent("merged_videos", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    /// history.json
    static var historyFile: URL {
        dir.appendingPathComponent("history.json")
    }

    // MARK: - API

    /// 将一个已生成的视频文件加入历史记录（复制到 Documents 持久化）
    @discardableResult
    static func addToHistory(videoURL: URL,
                             thumbnailURL: URL?,
                             mergeType: String,
                             duration: Double,
                             width: Int,
                             height: Int) -> HistoryEntry {
        let timestamp = Int(Date().timeIntervalSince1970 * 1000)
        let destVideo = dir.appendingPathComponent("merged_\(timestamp).mp4")
        if FileManager.default.fileExists(atPath: destVideo.path) {
            try? FileManager.default.removeItem(at: destVideo)
        }
        try? FileManager.default.copyItem(at: videoURL, to: destVideo)

        var destThumbOpt: URL? = nil
        if let thumbURL = thumbnailURL, FileManager.default.fileExists(atPath: thumbURL.path) {
            let destThumb = dir.appendingPathComponent("thumb_\(timestamp).jpg")
            try? FileManager.default.copyItem(at: thumbURL, to: destThumb)
            destThumbOpt = destThumb
        }

        let entry = HistoryEntry(
            id: "\(timestamp)",
            fileURL: destVideo,
            thumbnailURL: destThumbOpt,
            mergeType: mergeType,
            timestamp: Double(timestamp),
            duration: duration,
            width: width,
            height: height
        )

        var list = loadHistory()
        list.insert(entry, at: 0)
        saveHistory(list)
        return entry
    }

    /// 读取历史列表，过滤已不存在的文件
    static func loadHistory() -> [HistoryEntry] {
        guard FileManager.default.fileExists(atPath: historyFile.path),
              let data = try? Data(contentsOf: historyFile),
              let arr = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]]
        else { return [] }

        let entries = arr.compactMap { entryDict($0) }
        return entries.filter { FileManager.default.fileExists(atPath: $0.fileURL.path) }
    }

    /// 删除一条历史
    static func deleteEntry(entry: HistoryEntry) {
        try? FileManager.default.removeItem(at: entry.fileURL)
        if let thumb = entry.thumbnailURL {
            try? FileManager.default.removeItem(at: thumb)
        }
        let rawList = loadHistoryRaw()
        let filtered = rawList.filter { $0.id != entry.id }
        saveHistory(filtered)
    }

    // MARK: - 内部

    private static func loadHistoryRaw() -> [HistoryEntry] {
        guard FileManager.default.fileExists(atPath: historyFile.path),
              let data = try? Data(contentsOf: historyFile),
              let arr = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]]
        else { return [] }
        return arr.compactMap { entryDict($0) }
    }

    private static func saveHistory(_ list: [HistoryEntry]) {
        let arr: [[String: Any]] = list.map { e in
            [
                "filePath": e.fileURL.path,
                "thumbnailPath": e.thumbnailURL?.path ?? "",
                "mergeType": e.mergeType,
                "timestamp": e.timestamp,
                "duration": e.duration,
                "width": e.width,
                "height": e.height
            ]
        }
        if let data = try? JSONSerialization.data(withJSONObject: arr, options: [.prettyPrinted]) {
            try? data.write(to: historyFile)
        }
    }

    private static func entryDict(_ d: [String: Any]) -> HistoryEntry? {
        guard let filePath = d["filePath"] as? String,
              let mergeType = d["mergeType"] as? String,
              let timestamp = d["timestamp"] as? Double,
              let duration = d["duration"] as? Double,
              let width = d["width"] as? Int,
              let height = d["height"] as? Int
        else { return nil }

        let thumbPath = d["thumbnailPath"] as? String ?? ""
        let thumbURL = (thumbPath.isEmpty ? nil : URL(fileURLWithPath: thumbPath))

        return HistoryEntry(
            id: "\(Int(timestamp))",
            fileURL: URL(fileURLWithPath: filePath),
            thumbnailURL: thumbURL,
            mergeType: mergeType,
            timestamp: timestamp,
            duration: duration,
            width: width,
            height: height
        )
    }
}
