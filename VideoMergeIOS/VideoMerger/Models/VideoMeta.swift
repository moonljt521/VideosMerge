//
//  VideoMeta.swift
//  VideoMerger
//
//  iOS 移植自 Android VideoMerger 的 VideoMeta
//

import Foundation

/// 视频元数据
struct VideoMeta: Equatable {
    let width: Int
    let height: Int
    let duration: Double   // 秒
    let hasAudio: Bool
}
