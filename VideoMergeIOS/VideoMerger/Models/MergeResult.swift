//
//  MergeResult.swift
//  VideoMerger
//

import Foundation

/// 合并结果 —— 包含输出文件和元数据信息
struct MergeResult: Equatable {
    let outputFileURL: URL
    let thumbnailFileURL: URL?
    let mergeType: String
    let duration: Double
    let width: Int
    let height: Int
}
