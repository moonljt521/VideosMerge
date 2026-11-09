//
//  L10nRepair.swift
//  VideoMerger
//
//  视频去水印（内容修复）模块文案
//

import Foundation

enum L10nRepair {
    static let table: L10nModuleTable = (
        zh: [
            "home.inpaint_title": "画面去水印",
            "home.inpaint_subtitle": "智能抹除本地视频里的固定水印",

            "repair.nav_title": "画面去水印",
            "repair.pick_title": "选择视频开始修复",
            "repair.pick_hint": "适合水印位置固定、背景较简单的视频",
            "repair.repick": "换一个",
            "repair.source_info": "%.1fs · %lld×%lld",

            "repair.preview_title": "水印位置",
            "repair.bbox_hint": "拖动红框移动位置，拖右下角圆点缩放；可先点「自动检测」",
            "repair.auto_detect": "自动检测",
            "repair.detecting": "检测中…",
            "repair.refresh": "刷新预览",
            "repair.preparing": "生成预览…",
            "repair.probe_time": "探测帧",
            "repair.probe_time_value": "%.1f 秒",
            "repair.show_after": "修复后",
            "repair.show_mask": "遮罩",
            "repair.mask_pixels": "遮罩 %lld 像素",
            "repair.edge_expand": "边缘扩展",
            "repair.advanced": "高级参数",
            "repair.pos_x": "水平",
            "repair.pos_y": "垂直",
            "repair.width": "宽度",
            "repair.height": "高度",

            "repair.start": "开始修复",
            "repair.processing": "正在修复",
            "repair.cancel": "取消",
            "repair.cancelling": "正在取消…",
            "repair.phase_analyze": "分析画面",
            "repair.phase_process": "逐帧修复",
            "repair.phase_mux": "合成音轨",
            "repair.phase_done": "完成",
            "repair.need_preview": "请先生成修复预览",

            "repair.result_title": "修复完成",
            "repair.result_info": "%lld×%lld · %.1fs · %@",
            "repair.save": "保存到相册",
            "repair.saving": "保存中…",
            "repair.saved": "已保存到相册",
            "repair.again": "重新处理",
            "repair.save_filename": "去水印_%lld.mp4",
            "repair.save_failed": "保存失败：%@",
            "repair.history_type": "去水印",

            "repair.error_probe": "无法读取视频画面，请换一个视频试试",
            "repair.error_decode": "视频解码失败",
            "repair.error_encode": "视频编码失败",
            "repair.error_io": "临时文件读写失败，可能空间不足",
            "repair.error_output": "输出文件为空，修复失败",
            "repair.error_no_mask": "没有找到可修复的水印像素，请调整水印框或换一帧",
            "repair.error_mask_image": "预览生成失败",
            "repair.cancelled": "已取消",
            "repair.vfr_warning": "检测到可变帧率视频，处理后时长可能有轻微偏差",
            "repair.default_box_hint": "未自动检测到水印，已放置默认位置，请手动调整",
            "repair.auto_failed": "自动检测失败，请手动框选水印",

            "repair.tips_title": "使用提示",
            "repair.tip1": "适合水印位置固定、背景较简单（墙面 / 桌面 / 虚化背景）的视频。",
            "repair.tip2": "背景纹理复杂时可能出现涂抹痕迹，请先看预览再处理。",
            "repair.tip3": "处理会重新编码画面，时长与帧率保持不变；音频与 AI 生成标识等元数据会保留。",
        ],
        en: [
            "home.inpaint_title": "Watermark Repair",
            "home.inpaint_subtitle": "Erase a fixed watermark from a local video",

            "repair.nav_title": "Watermark Repair",
            "repair.pick_title": "Pick a video to repair",
            "repair.pick_hint": "Best for fixed watermarks on simple backgrounds",
            "repair.repick": "Change",
            "repair.source_info": "%.1fs · %lld×%lld",

            "repair.preview_title": "Watermark area",
            "repair.bbox_hint": "Drag the red box to move, drag the corner dot to resize; tap Auto Detect first",
            "repair.auto_detect": "Auto Detect",
            "repair.detecting": "Detecting…",
            "repair.refresh": "Refresh Preview",
            "repair.preparing": "Preparing…",
            "repair.probe_time": "Probe frame",
            "repair.probe_time_value": "%.1f s",
            "repair.show_after": "Repaired",
            "repair.show_mask": "Mask",
            "repair.mask_pixels": "%lld mask pixels",
            "repair.edge_expand": "Edge expand",
            "repair.advanced": "Advanced",
            "repair.pos_x": "X",
            "repair.pos_y": "Y",
            "repair.width": "Width",
            "repair.height": "Height",

            "repair.start": "Start Repair",
            "repair.processing": "Repairing",
            "repair.cancel": "Cancel",
            "repair.cancelling": "Cancelling…",
            "repair.phase_analyze": "Analyzing",
            "repair.phase_process": "Repairing frames",
            "repair.phase_mux": "Muxing audio",
            "repair.phase_done": "Done",
            "repair.need_preview": "Generate a preview first",

            "repair.result_title": "Repair Complete",
            "repair.result_info": "%lld×%lld · %.1fs · %@",
            "repair.save": "Save to Photos",
            "repair.saving": "Saving…",
            "repair.saved": "Saved to Photos",
            "repair.again": "Process Again",
            "repair.save_filename": "WatermarkRemoved_%lld.mp4",
            "repair.save_failed": "Save failed: %@",
            "repair.history_type": "Watermark Remover",

            "repair.error_probe": "Cannot read video frames. Try another video.",
            "repair.error_decode": "Video decoding failed",
            "repair.error_encode": "Video encoding failed",
            "repair.error_io": "Temp file I/O failed (storage may be full)",
            "repair.error_output": "Output file is empty. Repair failed.",
            "repair.error_no_mask": "No watermark pixels found. Adjust the box or choose another frame.",
            "repair.error_mask_image": "Preview generation failed",
            "repair.cancelled": "Cancelled",
            "repair.vfr_warning": "Variable frame rate detected; duration may shift slightly",
            "repair.default_box_hint": "No watermark detected. A default box was placed; please adjust it.",
            "repair.auto_failed": "Auto detect failed. Please select the watermark manually.",

            "repair.tips_title": "Tips",
            "repair.tip1": "Best for fixed watermarks on simple backgrounds (wall, desk, blurred background).",
            "repair.tip2": "Textured backgrounds may show smudges. Check the preview before processing.",
            "repair.tip3": "The video is re-encoded; duration and frame rate stay the same, audio and AI-content metadata are preserved.",
        ]
    )
}
