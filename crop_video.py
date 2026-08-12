#!/usr/bin/env python3
"""
画面空间裁剪工具 —— 裁剪视频画面的指定区域。

支持自定义裁剪框、宽高比预设、居中裁剪。

用法：
    # 裁剪中心 1080x1080 正方形
    python3 crop_video.py -i input.mp4 -w 1080 --height 1080

    # 裁剪左上角 500x500
    python3 crop_video.py -i input.mp4 -w 500 --height 500 -x 0 -y 0

    # 按宽高比 16:9 居中裁剪
    python3 crop_video.py -i input.mp4 --ratio 16:9

    # 按宽高比 9:16（竖屏）居中裁剪
    python3 crop_video.py -i input.mp4 --ratio 9:16

    # 裁剪右上角 1/4 区域
    python3 crop_video.py -i input.mp4 -w 360 --height 480 -x 360 -y 0

参数：
    -i / --input      输入视频路径
    -w / --width      裁剪宽度像素
    --height          裁剪高度像素（与 -w 配合使用）
    -x / --x          裁剪起始 X 坐标（默认居中）
    -y / --y          裁剪起始 Y 坐标（默认居中）
    --ratio           宽高比预设（16:9 / 9:16 / 4:3 / 3:4 / 1:1），居中裁剪
    -o / --output     输出文件名（默认 output_crop.mp4）

原理：
    ffmpeg crop 滤镜：crop=W:H:X:Y
    W=裁剪宽, H=裁剪高, X/Y=起始坐标
    不指定 X/Y 时默认居中
    确保输出尺寸为偶数（libx264 要求）
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
    ap = argparse.ArgumentParser(description="画面空间裁剪工具：裁剪视频指定区域")
    ap.add_argument("-i", "--input", required=True, help="输入视频路径")
    ap.add_argument("-w", "--width", type=int, default=None, help="裁剪宽度像素")
    ap.add_argument("--height", type=int, default=None, help="裁剪高度像素")
    ap.add_argument("-x", type=int, default=None, help="裁剪起始 X 坐标（默认居中）")
    ap.add_argument("-y", type=int, default=None, help="裁剪起始 Y 坐标（默认居中）")
    ap.add_argument("--ratio", default=None,
                    help="宽高比预设（16:9/9:16/4:3/3:4/1:1），居中裁剪")
    ap.add_argument("-o", "--output", default=None, help="输出文件名（默认 output_crop.mp4）")
    args = ap.parse_args()

    input_path = os.path.abspath(args.input)
    if not os.path.isfile(input_path):
        print(f"❌ 文件不存在: {input_path}", file=sys.stderr)
        sys.exit(1)

    vw, vh = get_dimensions(input_path)
    audio_present = has_audio(input_path)

    # ── 计算裁剪尺寸 ──
    if args.ratio:
        # 宽高比模式
        parts = args.ratio.split(":")
        if len(parts) != 2:
            print(f"❌ 宽高比格式应为 W:H，如 16:9", file=sys.stderr)
            sys.exit(1)
        rw, rh = int(parts[0]), int(parts[1])
        target_ar = rw / rh
        video_ar = vw / vh

        if video_ar > target_ar:
            # 视频更宽 → 裁掉左右
            crop_w = int(vh * target_ar)
            crop_h = vh
        else:
            # 视频更高 → 裁掉上下
            crop_w = vw
            crop_h = int(vw / target_ar)
        # 居中
        cx = (vw - crop_w) // 2
        cy = (vh - crop_h) // 2
    elif args.width and args.height:
        crop_w = args.width
        crop_h = args.height
        # 默认居中
        cx = args.x if args.x is not None else (vw - crop_w) // 2
        cy = args.y if args.y is not None else (vh - crop_h) // 2
    else:
        print("❌ 请指定 --width + --height 或 --ratio", file=sys.stderr)
        sys.exit(1)

    # 边界检查
    crop_w = min(crop_w, vw)
    crop_h = min(crop_h, vh)
    cx = max(0, min(cx, vw - crop_w))
    cy = max(0, min(cy, vh - crop_h))

    # 确保偶数
    crop_w -= crop_w % 2
    crop_h -= crop_h % 2

    print(f"输入: {os.path.basename(input_path)}  {vw}x{vh}")
    print(f"裁剪: {crop_w}x{crop_h} @ ({cx},{cy})")
    if args.ratio:
        print(f"  宽高比: {args.ratio}")
    print(f"  音频: {'有' if audio_present else '无'}")

    output_path = args.output or os.path.join(here, "output_crop.mp4")
    if not os.path.isabs(output_path):
        output_path = os.path.join(here, output_path)

    # ── 构建 ffmpeg 命令 ──
    vf = f"crop={crop_w}:{crop_h}:{cx}:{cy}"

    cmd = [
        "ffmpeg", "-y",
        "-i", input_path,
        "-vf", vf,
        "-c:v", "libx264",
        "-pix_fmt", "yuv420p",
        "-preset", "medium",
        "-crf", "20",
    ]
    if audio_present:
        cmd.extend(["-c:a", "aac", "-b:a", "192k"])
    else:
        cmd.append("-an")
    cmd.append(output_path)

    print(f"\n运行 ffmpeg...")
    print(f"  {' '.join(cmd)}\n")
    subprocess.run(cmd, check=True)
    print(f"✅ 完成: {output_path}")
    print(f"   {vw}x{vh} → {crop_w}x{crop_h} @ ({cx},{cy})")


if __name__ == "__main__":
    main()
