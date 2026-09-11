#!/usr/bin/env python3
"""
视频截图工具 —— 从视频中提取画面帧为图片。

支持单帧截图、多时间点截图、等间隔批量截图。

用法：
    # 截取第 3 秒的画面
    python3 screenshot.py -i input.mp4 -t 3

    # 截取多个时间点的画面（3s, 5s, 10s）
    python3 screenshot.py -i input.mp4 -t 3 5 10

    # 每隔 2 秒截一张图
    python3 screenshot.py -i input.mp4 --interval 2

    # 每秒截 1 帧，输出到指定目录
    python3 screenshot.py -i input.mp4 --fps 1 -d screenshots/

    # 截取中间画面，宽 480px
    python3 screenshot.py -i input.mp4 -t 5.9 --width 480

参数：
    -i / --input      输入视频路径
    -t / --time       截图时间点（秒，可多个，如 -t 3 5 10）
    --interval        等间隔截图（秒，如 --interval 2 每 2s 一张）
    --fps             每秒截取帧数（如 --fps 1 = 每秒 1 张，--fps 0.5 = 每 2s 1 张）
    -d / --dir        输出目录（默认 screenshots/）
    --width           输出图片宽度像素（等比缩放，默认原始分辨率）
    --format          输出图片格式（jpg/png，默认 jpg）

原理：
    单帧：ffmpeg -ss T -i input -frames:v 1 -q:v 2 output.jpg
    批量：ffmpeg -i input -vf fps=1/2 output_%04d.jpg
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
    ap = argparse.ArgumentParser(description="视频截图工具：从视频提取画面帧")
    ap.add_argument("-i", "--input", required=True, help="输入视频路径")
    ap.add_argument("-t", "--time", nargs="+", type=float, default=None,
                    help="截图时间点（秒，可多个如 -t 3 5 10）")
    ap.add_argument("--interval", type=float, default=None,
                    help="等间隔截图（秒，如 2 = 每 2s 一张）")
    ap.add_argument("--fps", type=float, default=None,
                    help="每秒截取帧数（如 1 = 每秒 1 张，0.5 = 每 2s 1 张）")
    ap.add_argument("-d", "--dir", default=None, help="输出目录（默认 screenshots/）")
    ap.add_argument("--width", type=int, default=None, help="输出图片宽度像素（等比缩放）")
    ap.add_argument("--format", choices=["jpg", "png"], default="jpg", help="输出图片格式（默认 jpg）")
    args = ap.parse_args()

    input_path = os.path.abspath(args.input)
    if not os.path.isfile(input_path):
        print(f"❌ 文件不存在: {input_path}", file=sys.stderr)
        sys.exit(1)

    duration = get_duration(input_path)
    vw, vh = get_dimensions(input_path)

    # 确定输出目录
    out_dir = args.dir or os.path.join(here, "screenshots")
    if not os.path.isabs(out_dir):
        out_dir = os.path.join(here, out_dir)
    os.makedirs(out_dir, exist_ok=True)

    # 构建缩放滤镜
    scale_filter = ""
    if args.width:
        scale_filter = f",scale={args.width}:-2"

    print(f"视频: {os.path.basename(input_path)}  {vw}x{vh}  {duration:.2f}s")
    print(f"输出目录: {out_dir}")

    # ── 模式 1：指定时间点截图 ──
    if args.time:
        print(f"模式: 指定时间点 ({args.time})")
        for ts in args.time:
            if ts < 0 or ts > duration:
                print(f"  ⚠️  时间 {ts}s 超出范围 [0, {duration:.2f}]，跳过")
                continue
            name = os.path.splitext(os.path.basename(input_path))[0]
            out_file = os.path.join(out_dir, f"{name}_{ts:.1f}s.{args.format}")
            cmd = [
                "ffmpeg", "-y",
                "-ss", f"{ts:.3f}",
                "-i", input_path,
                "-frames:v", "1",
            ]
            if scale_filter:
                cmd.extend(["-vf", scale_filter.lstrip(",")])
            if args.format == "jpg":
                cmd.extend(["-q:v", "2"])
            cmd.append(out_file)
            print(f"  截图 {ts:.1f}s → {os.path.basename(out_file)}")
            subprocess.run(cmd, check=True)
        print(f"\n✅ 完成: {len(args.time)} 张截图已保存到 {out_dir}")

    # ── 模式 2：等间隔截图 ──
    elif args.interval:
        print(f"模式: 等间隔截图（每 {args.interval}s 一张）")
        name = os.path.splitext(os.path.basename(input_path))[0]
        pattern = os.path.join(out_dir, f"{name}_%04d.{args.format}")
        fps_val = 1.0 / args.interval
        vf = f"fps={fps_val}"
        if scale_filter:
            vf += scale_filter
        cmd = [
            "ffmpeg", "-y",
            "-i", input_path,
            "-vf", vf,
        ]
        if args.format == "jpg":
            cmd.extend(["-q:v", "2"])
        cmd.append(pattern)
        print(f"  fps={fps_val:.4f}")
        subprocess.run(cmd, check=True)
        count = len([f for f in os.listdir(out_dir) if f.startswith(name) and f.endswith(f".{args.format}")])
        print(f"\n✅ 完成: {count} 张截图已保存到 {out_dir}")

    # ── 模式 3：按 FPS 截图 ──
    elif args.fps:
        print(f"模式: 按 FPS 截图（{args.fps} 帧/秒）")
        name = os.path.splitext(os.path.basename(input_path))[0]
        pattern = os.path.join(out_dir, f"{name}_%04d.{args.format}")
        vf = f"fps={args.fps}"
        if scale_filter:
            vf += scale_filter
        cmd = [
            "ffmpeg", "-y",
            "-i", input_path,
            "-vf", vf,
        ]
        if args.format == "jpg":
            cmd.extend(["-q:v", "2"])
        cmd.append(pattern)
        subprocess.run(cmd, check=True)
        count = len([f for f in os.listdir(out_dir) if f.startswith(name) and f.endswith(f".{args.format}")])
        print(f"\n✅ 完成: {count} 张截图已保存到 {out_dir}")

    else:
        print("❌ 请指定 --time / --interval / --fps 之一", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
