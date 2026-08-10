//
//  MergeTypes.swift
//  VideoMerger
//
//  iOS 移植自 Android VideoMerger 的 MergeTypes.kt
//

import Foundation

/// 合并类型
enum MergeType: String, CaseIterable, Identifiable {
    /// 均匀网格布局
    case grid = "GRID"
    /// 一个主窗口 + 若干副窗口
    case collage = "COLLAGE"
    /// 照片墙风格（大小错落、边框阴影）
    case photoWall = "PHOTO_WALL"

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .grid:      return "网格拼贴"
        case .collage:   return "画中画"
        case .photoWall: return "照片墙"
        }
    }

    var subtitle: String {
        switch self {
        case .grid:      return "均匀网格布局"
        case .collage:   return "一主多副布局"
        case .photoWall: return "错落有致+边框"
        }
    }
}

/// 主窗口位置（Collage 模式）
enum CollageOrient: String, CaseIterable, Identifiable {
    case left, right, top, bottom
    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .left:   return "左"
        case .right:  return "右"
        case .top:    return "上"
        case .bottom: return "下"
        }
    }
}

/// 填充方式（Collage 模式）
enum CollageFit: String, CaseIterable, Identifiable {
    case cover    // 裁剪填充无黑边
    case contain  // 缩放留黑边
    var id: String { rawValue }
}

/// 合并选项
struct MergeOptions: Equatable {
    // 通用
    var canvasWidth: Int = 1920
    var canvasHeight: Int = 1080

    // Grid 选项
    var gridCellSize: Int = 720

    // Collage 选项
    var collageMainIndex: Int = 0
    var collageMainRatio: Double = 0.62
    var collageOrient: CollageOrient = .left
    var collageFit: CollageFit = .cover

    // Photo Wall 选项
    var photoWallSeed: Int? = nil

    // 通用选项
    var gap: Int = 0
}

// MARK: - 数值辅助

extension Int {
    /// 向下对齐到偶数（libx264 要求）
    var toEven: Int { self - (self % 2) }

    /// 向下对齐到 16 的倍数（硬件编码器 macroblock 对齐）
    var toAligned16: Int { (self / 16) * 16 }
}

extension Double {
    /// 格式化浮点为指定小数位，固定用小数点（不受地区影响）
    func fmt(_ decimals: Int = 3) -> String {
        String(format: "%.\(decimals)f", self)
    }
}
