#!/usr/bin/env python3
"""
电影黑边工具 —— 为视频添加电影比例黑边或圆角边框。

用法：
    # 添加 2.35:1 电影黑边（上下黑边）
    python3 letterbox.py -i input.mp4 --ratio 2.35

    # 自定义黑边高度（上下各 60px）
    python3 letterbox.py -i input.mp4 --bar 60

    # 黑色背景 + 圆角
    python3 letterbox.py -i input.mp4 --ratio 2.35 --corner 20

参数：
    -i / --input    输入视频路径
    --ratio         目标宽高比（如 2.35/1.85/2.0），自动计算上下黑边
    --bar           上下黑边像素（直接指定，与 --ratio 二选一）
    --corner        圆角半径像素（默认 0 不加圆角）
    --color         黑边颜色（默认 black，可选 white/gray 等）
    -o / --output   输出文件名（默认 output_letterbox.mp4）

原理：
    pad 滤镜：pad=W:H:X:Y:color
    电影宽高比 2.35:1 → 画面高度裁剪为 W/2.35，上下加黑边
    圆角用 Pillow 生成蒙版 PNG 叠加
"""

import argparse
import os
import subprocess
import sys
import tempfile

try:
    from PIL import Image, ImageDraw
    _PIL_AVAILABLE = True
except ImportError:
    _PIL_AVAILABLE = False


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
    ap = argparse.ArgumentParser(description="电影黑边工具：添加电影比例黑边")
    ap.add_argument("-i", "--input", required=True, help="输入视频路径")
    ap.add_argument("--ratio", type=float, default=None,
                    help="目标宽高比（如 2.35/1.85），自动计算黑边")
    ap.add_argument("--bar", type=int, default=None,
                    help="上下黑边像素（直接指定）")
    ap.add_argument("--corner", type=int, default=0,
                    help="圆角半径像素（默认 0 不加圆角）")
    ap.add_argument("--color", default="black", help="黑边颜色（默认 black）")
    ap.add_argument("-o", "--output", default=None, help="输出文件名（默认 output_letterbox.mp4）")
    args = ap.parse_args()

    if not args.ratio and args.bar is None:
        print("❌ 请指定 --ratio 或 --bar", file=sys.stderr)
        sys.exit(1)

    input_path = os.path.abspath(args.input)
    if not os.path.isfile(input_path):
        print(f"❌ 文件不存在: {input_path}", file=sys.stderr)
        sys.exit(1)

    vw, vh = get_dimensions(input_path)
    audio_present = has_audio(input_path)

    # 计算黑边
    if args.ratio:
        target_h = round(vw / args.ratio)
        target_h -= target_h % 2
        if target_h >= vh:
            print(f"⚠️  视频已比 {args.ratio}:1 更宽，无需加黑边", file=sys.stderr)
            bar = 0
            out_h = vh
        else:
            bar = (vh - target_h) // 2
            bar -= bar % 2
            out_h = vh
    else:
        bar = args.bar
        bar -= bar % 2
        out_h = vh

    # 圆角处理
    use_corner = args.corner > 0 and _PIL_AVAILABLE

    print(f"输入: {os.path.basename(input_path)}  {vw}x{vh}")
    print(f"  黑边: {bar}px x2  颜色: {args.color}  圆角: {args.corner if use_corner else '无'}")

    output_path = args.output or os.path.join(here, "output_letterbox.mp4")
    if not os.path.isabs(output_path):
        output_path = os.path.join(here, output_path)

    if bar > 0:
        # 用 pad 添加上下黑边
        new_h = vh + bar * 2
        new_h -= new_h % 2
        vf = f"pad={vw}:{new_h}:0:{bar}:color={args.color}"
    else:
        vf = "copy"

    # 圆角蒙版
    mask_path = None
    if use_corner:
        # 生成圆角蒙版：黑色背景 + 透明圆角矩形
        mask = Image.new("RGBA", (vw, vh), (0, 0, 0, 255))
        draw = ImageDraw.Draw(mask)
        draw.rounded_rectangle([0, 0, vw - 1, vh - 1], radius=args.corner, fill=(0, 0, 0, 0))
        mask_path = tempfile.mktemp(suffix=".png")
        mask.save(mask_path, "PNG")

    if mask_path:
        if bar > 0:
            filter_complex = f"[0:v]{vf}[v0];[v0][1:v]overlay=0:{bar}[vout]"
        else:
            filter_complex = f"[0:v][1:v]overlay=0:0[vout]"
        cmd = [
            "ffmpeg", "-y",
            "-i", input_path,
            "-i", mask_path,
            "-filter_complex", filter_complex,
            "-map", "[vout]",
            "-c:v", "libx264", "-pix_fmt", "yuv420p",
            "-preset", "medium", "-crf", "20",
        ]
    else:
        cmd = [
            "ffmpeg", "-y",
            "-i", input_path,
            "-vf", vf,
            "-c:v", "libx264", "-pix_fmt", "yuv420p",
            "-preset", "medium", "-crf", "20",
        ]

    if audio_present:
        cmd.extend(["-c:a", "aac", "-b:a", "192k"])
    else:
        cmd.append("-an")
    cmd.append(output_path)

    print(f"\n运行 ffmpeg...")
    print(f"  {' '.join(cmd)}\n")
    try:
        subprocess.run(cmd, check=True)
    finally:
        if mask_path and os.path.isfile(mask_path):
            os.remove(mask_path)

    print(f"✅ 完成: {output_path}")
    if bar > 0:
        print(f"   {vw}x{vh} → {vw}x{vh+bar*2}（上下各 {bar}px 黑边）")
    else:
        print(f"   {vw}x{vh}（圆角效果）")


if __name__ == "__main__":
    main()
