#!/usr/bin/env python3
"""
视频旋转/翻转工具 —— 旋转视频画面或水平/垂直镜像翻转。

用法：
    # 顺时针旋转 90 度（竖屏 → 横屏）
    python3 rotate_video.py -i input.mp4 -r 90

    # 逆时针旋转 90 度
    python3 rotate_video.py -i input.mp4 -r -90

    # 旋转 180 度
    python3 rotate_video.py -i input.mp4 -r 180

    # 水平镜像（左右翻转）
    python3 rotate_video.py -i input.mp4 --hflip

    # 垂直镜像（上下翻转）
    python3 rotate_video.py -i input.mp4 --vflip

    # 旋转 90 + 水平镜像
    python3 rotate_video.py -i input.mp4 -r 90 --hflip

参数：
    -i / --input    输入视频路径
    -r / --rotate   旋转角度（90 / -90 / 180，默认 0 不旋转）
    --hflip         水平镜像（左右翻转）
    --vflip         垂直镜像（上下翻转）
    -o / --output   输出文件名（默认 output_rotate.mp4）

原理：
    transpose=0  → 逆时针 90 度（等同 -90）
    transpose=1  → 顺时针 90 度
    transpose=2  → 逆时针 90 度（保持方向）
    transpose=3  → 顺时针 90 度（保持方向）
    180 度 = transpose=1,transpose=1（两次 90 度）
    hflip → 水平镜像
    vflip → 垂直镜像
"""

import argparse
import os
import subprocess
import sys


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
    ap = argparse.ArgumentParser(description="视频旋转/翻转工具")
    ap.add_argument("-i", "--input", required=True, help="输入视频路径")
    ap.add_argument("-r", "--rotate", type=int, default=0,
                    choices=[0, 90, -90, 180, -180],
                    help="旋转角度（90/-90/180，默认 0 不旋转）")
    ap.add_argument("--hflip", action="store_true", help="水平镜像（左右翻转）")
    ap.add_argument("--vflip", action="store_true", help="垂直镜像（上下翻转）")
    ap.add_argument("-o", "--output", default=None, help="输出文件名（默认 output_rotate.mp4）")
    args = ap.parse_args()

    input_path = os.path.abspath(args.input)
    if not os.path.isfile(input_path):
        print(f"❌ 文件不存在: {input_path}", file=sys.stderr)
        sys.exit(1)

    audio_present = has_audio(input_path)

    if args.rotate == 0 and not args.hflip and not args.vflip:
        print("❌ 请指定 --rotate / --hflip / --vflip", file=sys.stderr)
        sys.exit(1)

    # ── 构建滤镜链 ──
    filters = []

    rotate = args.rotate
    if rotate in (90, -270):
        filters.append("transpose=1")  # 顺时针 90
    elif rotate in (-90, 270):
        filters.append("transpose=0")  # 逆时针 90
    elif rotate in (180, -180):
        filters.append("transpose=1,transpose=1")  # 两次顺时针 90 = 180

    if args.hflip:
        filters.append("hflip")
    if args.vflip:
        filters.append("vflip")

    vf = ",".join(filters)

    # 描述操作
    ops = []
    if rotate:
        ops.append(f"旋转 {rotate}°")
    if args.hflip:
        ops.append("水平镜像")
    if args.vflip:
        ops.append("垂直镜像")

    print(f"输入: {os.path.basename(input_path)}")
    print(f"  操作: {' + '.join(ops)}")
    print(f"  滤镜: {vf}")
    print(f"  音频: {'有' if audio_present else '无'}")

    output_path = args.output or os.path.join(here, "output_rotate.mp4")
    if not os.path.isabs(output_path):
        output_path = os.path.join(here, output_path)

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
    print(f"   {' + '.join(ops)}")


if __name__ == "__main__":
    main()
