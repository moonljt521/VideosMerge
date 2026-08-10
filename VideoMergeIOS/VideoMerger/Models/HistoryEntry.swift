//
//  HistoryEntry.swift
//  VideoMerger
//

import Foundation

/// 历史记录条目
struct HistoryEntry: Identifiable, Equatable {
    let id: String            // 用 timestamp 字符串作为唯一 id
    let fileURL: URL
    let thumbnailURL: URL?
    let mergeType: String
    let timestamp: Double
    let duration: Double
    let width: Int
    let height: Int

    var mergeTypeDisplay: String {
        switch mergeType {
        case "GRID":      return "网格拼贴"
        case "COLLAGE":   return "画中画"
        case "PHOTO_WALL": return "照片墙"
        default:          return mergeType
        }
    }

    var dateString: String {
        let date = Date(timeIntervalSince1970: timestamp / 1000.0)
        let fmt = DateFormatter()
        fmt.dateFormat = "MM/dd HH:mm"
        return fmt.string(from: date)
    }
}
