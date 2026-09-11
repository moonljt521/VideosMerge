#!/usr/bin/env python3
"""
GIF 动图导出工具 —— 将视频片段导出为高质量 GIF 动图。

使用调色板优化技术，生成颜色丰富、体积小的 GIF。

用法：
    # 整个视频导出为 GIF（默认 480px 宽，10fps）
    python3 video_to_gif.py -i input.mp4

    # 截取 2s~8s 片段导出
    python3 video_to_gif.py -i input.mp4 --start 2 --end 8

    # 指定宽度和帧率
    python3 video_to_gif.py -i input.mp4 --width 320 --fps 15

    # 不循环播放（只播一次）
    python3 video_to_gif.py -i input.mp4 --no-loop

    # 高质量模式（更多颜色）
    python3 video_to_gif.py -i input.mp4 --quality high

参数：
    -i / --input      输入视频路径
    --start           起始时间（秒，默认 0）
    --end             结束时间（秒，默认视频末尾）
    --width           GIF 宽度像素（默认 480，等比缩放）
    --fps             帧率（默认 10，GIF 建议 8~15）
    --quality         质量：fast/normal/high（默认 normal，影响调色板大小）
    --no-loop         不循环播放（默认无限循环）
    -o / --output     输出文件名（默认 output.gif）

原理：
    高质量 GIF 需要两步：
    1. palettegen：从视频提取最优 256 色调色板
    2. paletteuse：用调色板将视频转为 GIF
    质量级别影响 palettegen 的 stats_mode 和 paletteuse 的 dither
"""

import argparse
import os
import subprocess
import sys


def get_duration(path: str) -> float:
    r = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "default=noprint_wrappers=1:nokey=1", path],
        capture_output=True, text=True, check=True,
    )
    return float(r.stdout.strip())


def get_dimensions(path: str):
    r = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0",
         "-show_entries", "stream=width,height",
         "-of", "csv=p=0:s=x", path],
        capture_output=True, text=True, check=True,
    )
    w, h = r.stdout.strip().split("x")
    return int(w), int(h)


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description="GIF 动图导出工具：视频转高质量 GIF")
    ap.add_argument("-i", "--input", required=True, help="输入视频路径")
    ap.add_argument("--start", type=float, default=0.0, help="起始时间（秒，默认 0）")
    ap.add_argument("--end", type=float, default=None, help="结束时间（秒，默认视频末尾）")
    ap.add_argument("--width", type=int, default=480, help="GIF 宽度像素（默认 480）")
    ap.add_argument("--fps", type=int, default=10, help="帧率（默认 10）")
    ap.add_argument("--quality", choices=["fast", "normal", "high"], default="normal",
                    help="质量：fast/normal/high（默认 normal）")
    ap.add_argument("--no-loop", action="store_true", help="不循环播放（默认无限循环）")
    ap.add_argument("-o", "--output", default=None, help="输出文件名（默认 output.gif）")
    args = ap.parse_args()

    input_path = os.path.abspath(args.input)
    if not os.path.isfile(input_path):
        print(f"❌ 文件不存在: {input_path}", file=sys.stderr)
        sys.exit(1)

    duration = get_duration(input_path)
    vw, vh = get_dimensions(input_path)
    start = max(0, args.start)
    end = args.end or duration
    end = min(end, duration)

    if end <= start:
        print(f"❌ 结束时间 {end} 不大于起始时间 {start}", file=sys.stderr)
        sys.exit(1)

    gif_dur = end - start
    # GIF 宽度等比缩放
    gif_w = args.width
    gif_h = round(vh * gif_w / vw)
    gif_w -= gif_w % 2
    gif_h -= gif_h % 2

    print(f"视频: {os.path.basename(input_path)}  {vw}x{vh}  {duration:.2f}s")
    print(f"GIF:  {gif_w}x{gif_h}  {gif_dur:.2f}s  {args.fps}fps  质量: {args.quality}")

    output_path = args.output or os.path.join(here, "output.gif")
    if not os.path.isabs(output_path):
        output_path = os.path.join(here, output_path)

    # ── 质量参数 ──
    if args.quality == "fast":
        palette_stats = "full"
        dither = "none"
    elif args.quality == "high":
        palette_stats = "diff"
        dither = "bayer:bayer_scale=3"
    else:  # normal
        palette_stats = "diff"
        dither = "sierra2_4a"

    # ── 构建滤镜 ──
    # 用 split 避免处理视频两次：
    # [0:v] → trim → fps → scale → split → [gif] + [palin]
    # [palin] → palettegen → [palette]
    # [gif][palette] → paletteuse → [vout]
    prechain = (
        f"[0:v]trim=start={start:.3f}:end={end:.3f},setpts=PTS-STARTPTS,"
        f"fps={args.fps},scale={gif_w}:{gif_h}:flags=lanczos,"
        f"split=2[gif][palin]"
    )
    palette_gen = f"[palin]palettegen=stats_mode={palette_stats}[palette]"
    palette_apply = f"[gif][palette]paletteuse=dither={dither}[vout]"

    if not args.no_loop:
        loop_param = "-1"  # 无限循环
    else:
        loop_param = "0"  # 不循环

    filter_complex = f"{prechain};{palette_gen};{palette_apply}"

    cmd = [
        "ffmpeg", "-y",
        "-i", input_path,
        "-filter_complex", filter_complex,
        "-map", "[vout]",
        "-loop", loop_param,  # GIF 循环
        "-f", "gif",
        output_path,
    ]

    print(f"\n运行 ffmpeg...")
    print(f"  {' '.join(cmd)}\n")
    subprocess.run(cmd, check=True)

    gif_size = os.path.getsize(output_path)
    print(f"✅ 完成: {output_path}")
    print(f"   {gif_w}x{gif_h}  {gif_dur:.2f}s  {gif_size / 1024:.0f}KB  {'循环' if not args.no_loop else '不循环'}")


if __name__ == "__main__":
    main()
