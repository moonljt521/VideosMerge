#!/usr/bin/env python3
"""
图片水印工具 —— 在视频画面上叠加图片水印/logo。

支持 9 宫格位置预设和自定义坐标，可调节水印大小和透明度。

用法：
    # 右下角添加水印（默认 20% 大小）
    python3 image_watermark.py -i input.mp4 -w logo.png --pos bottom-right

    # 左上角，缩放到 15%
    python3 image_watermark.py -i input.mp4 -w logo.png --pos top-left --scale 0.15

    # 自定义位置和透明度
    python3 image_watermark.py -i input.mp4 -w logo.png -x 50 -y 50 --opacity 0.8

    # 水印缩放到 100px 宽
    python3 image_watermark.py -i input.mp4 -w logo.png --width 100 --pos bottom-right

参数：
    -i / --input     输入视频路径
    -w / --watermark  水印图片路径（PNG/JPG，PNG 支持透明）
    --pos             位置预设：top-left/top-right/bottom-left/bottom-right/center（默认 bottom-right）
    -x / --x          自定义 X 坐标（与 --pos 冲突，优先于 --pos）
    -y / --y          自定义 Y 坐标
    --scale           水印缩放比例（如 0.2 = 缩放到原尺寸 20%）
    --width           水印目标宽度像素（与 --scale 冲突）
    --opacity         透明度 0.0~1.0（默认 1.0 不透明）
    -m / --margin     边距像素（默认 20，用于位置预设）
    -o / --output     输出文件名（默认 output_watermark.mp4）

原理：
    overlay 滤镜：[0:v][1:v]overlay=x:y
    透明度：[1:v]format=rgba,colorchannelmixer=aa=opacity
    缩放：[1:v]scale=W:H
"""

import argparse
import os
import subprocess
import sys


def get_dimensions(path: str):
    r = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0",
         "-show_entries", "stream=width,height",
         "-of", "csv=p=0:s=x", path],
        capture_output=True, text=True, check=True,
    )
    w, h = r.stdout.strip().split("x")
    return int(w), int(h)


def get_image_dimensions(path: str):
    """获取图片宽高"""
    r = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0",
         "-show_entries", "stream=width,height",
         "-of", "csv=p=0:s=x", path],
        capture_output=True, text=True, check=True,
    )
    w, h = r.stdout.strip().split("x")
    return int(w), int(h)


def has_audio(path: str) -> bool:
    r = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "a",
         "-show_entries", "stream=codec_type",
         "-of", "default=noprint_wrappers=1:nokey=1", path],
        capture_output=True, text=True,
    )
    return "audio" in r.stdout.strip()


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description="图片水印工具：在视频上叠加图片水印")
    ap.add_argument("-i", "--input", required=True, help="输入视频路径")
    ap.add_argument("-w", "--watermark", required=True, help="水印图片路径")
    ap.add_argument("--pos", default="bottom-right",
                    choices=["top-left", "top-right", "bottom-left", "bottom-right", "center"],
                    help="水印位置预设（默认 bottom-right）")
    ap.add_argument("-x", type=int, default=None, help="自定义 X 坐标")
    ap.add_argument("-y", type=int, default=None, help="自定义 Y 坐标")
    ap.add_argument("--scale", type=float, default=None,
                    help="水印缩放比例（如 0.2 = 20%）")
    ap.add_argument("--width", type=int, default=None,
                    help="水印目标宽度像素（与 --scale 冲突）")
    ap.add_argument("--opacity", type=float, default=1.0,
                    help="透明度 0.0~1.0（默认 1.0）")
    ap.add_argument("-m", "--margin", type=int, default=20,
                    help="边距像素（默认 20，用于位置预设）")
    ap.add_argument("-o", "--output", default=None, help="输出文件名（默认 output_watermark.mp4）")
    args = ap.parse_args()

    input_path = os.path.abspath(args.input)
    watermark_path = os.path.abspath(args.watermark)

    if not os.path.isfile(input_path):
        print(f"❌ 视频文件不存在: {input_path}", file=sys.stderr)
        sys.exit(1)
    if not os.path.isfile(watermark_path):
        print(f"❌ 水印图片不存在: {watermark_path}", file=sys.stderr)
        sys.exit(1)

    vw, vh = get_dimensions(input_path)
    iw, ih = get_image_dimensions(watermark_path)
    audio_present = has_audio(input_path)

    # ── 计算水印缩放 ──
    if args.width:
        wm_w = args.width
        wm_h = round(ih * args.width / iw)
    elif args.scale:
        wm_w = round(iw * args.scale)
        wm_h = round(ih * args.scale)
    else:
        # 默认缩放到视频宽度的 15%
        wm_w = round(vw * 0.15)
        wm_h = round(ih * wm_w / iw)

    wm_w -= wm_w % 2
    wm_h -= wm_h % 2

    # ── 计算水印位置 ──
    margin = args.margin
    if args.x is not None and args.y is not None:
        ox, oy = args.x, args.y
    else:
        pos = args.pos
        if pos == "top-left":
            ox, oy = margin, margin
        elif pos == "top-right":
            ox, oy = vw - wm_w - margin, margin
        elif pos == "bottom-left":
            ox, oy = margin, vh - wm_h - margin
        elif pos == "bottom-right":
            ox, oy = vw - wm_w - margin, vh - wm_h - margin
        else:  # center
            ox, oy = (vw - wm_w) // 2, (vh - wm_h) // 2

    print(f"视频: {os.path.basename(input_path)}  {vw}x{vh}")
    print(f"水印: {os.path.basename(watermark_path)}  {iw}x{ih} → {wm_w}x{wm_h}")
    print(f"位置: ({ox}, {oy})  透明度: {args.opacity}  音频: {'有' if audio_present else '无'}")

    output_path = args.output or os.path.join(here, "output_watermark.mp4")
    if not os.path.isabs(output_path):
        output_path = os.path.join(here, output_path)

    # ── 构建滤镜 ──
    # 水印处理：缩放 → 透明度 → 叠加
    wm_filter = f"[1:v]scale={wm_w}:{wm_h}"
    if args.opacity < 1.0:
        wm_filter += f",format=rgba,colorchannelmixer=aa={args.opacity}"
    wm_filter += "[wm]"

    overlay_filter = f"[0:v][wm]overlay={ox}:{oy}[vout]"

    filter_complex = f"{wm_filter};{overlay_filter}"

    cmd = [
        "ffmpeg", "-y",
        "-i", input_path,
        "-i", watermark_path,
        "-filter_complex", filter_complex,
        "-map", "[vout]",
        "-c:v", "libx264",
        "-pix_fmt", "yuv420p",
        "-preset", "medium",
        "-crf", "20",
    ]
    if audio_present:
        cmd.extend(["-map", "0:a", "-c:a", "aac", "-b:a", "192k"])
    else:
        cmd.append("-an")
    cmd.append(output_path)

    print(f"\n运行 ffmpeg...")
    print(f"  {' '.join(cmd)}\n")
    subprocess.run(cmd, check=True)
    print(f"✅ 完成: {output_path}")
    print(f"   水印 {wm_w}x{wm_h} @ ({ox}, {oy})")


if __name__ == "__main__":
    main()
