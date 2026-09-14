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
        case "GRID":      return L10n.t("model.merge_grid")
        case "COLLAGE":   return L10n.t("model.merge_collage")
        case "PHOTO_WALL": return L10n.t("model.merge_photo_wall")
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
