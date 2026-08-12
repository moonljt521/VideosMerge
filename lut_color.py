#!/usr/bin/env python3
"""
LUT 调色工具 —— 加载 .cube LUT 文件对视频进行专业调色。

LUT（Look-Up Table）是专业调色师使用的颜色映射表，
可一键应用电影级调色风格。

用法：
    # 应用 LUT
    python3 lut_color.py -i input.mp4 --lut film.cube

    # 调整 LUT 强度（0~1，默认 1.0 完全应用）
    python3 lut_color.py -i input.mp4 --lut film.cube --intensity 0.5

参数：
    -i / --input      输入视频路径
    --lut             LUT 文件路径（.cube 格式）
    --intensity       LUT 应用强度 0.0~1.0（默认 1.0 完全应用）
    -o / --output     输出文件名（默认 output_lut.mp4）

原理：
    lut3d 滤镜：lut3d=file='path/to/lut.cube'
    强度混合：lut3d + colorchannelmixer 或用 lut3d 的 amount 参数
    .cube 是行业标准 LUT 格式，可在网上找到大量免费 LUT
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
    ap = argparse.ArgumentParser(description="LUT 调色工具：加载 .cube LUT 文件调色")
    ap.add_argument("-i", "--input", required=True, help="输入视频路径")
    ap.add_argument("--lut", required=True, help="LUT 文件路径（.cube 格式）")
    ap.add_argument("--intensity", type=float, default=1.0,
                    help="LUT 应用强度 0.0~1.0（默认 1.0 完全应用）")
    ap.add_argument("-o", "--output", default=None, help="输出文件名（默认 output_lut.mp4）")
    args = ap.parse_args()

    input_path = os.path.abspath(args.input)
    lut_path = os.path.abspath(args.lut)

    if not os.path.isfile(input_path):
        print(f"❌ 视频文件不存在: {input_path}", file=sys.stderr)
        sys.exit(1)
    if not os.path.isfile(lut_path):
        print(f"❌ LUT 文件不存在: {lut_path}", file=sys.stderr)
        sys.exit(1)

    audio_present = has_audio(input_path)
    intensity = max(0.0, min(1.0, args.intensity))

    print(f"输入: {os.path.basename(input_path)}")
    print(f"  LUT: {os.path.basename(lut_path)}")
    print(f"  强度: {intensity}")

    output_path = args.output or os.path.join(here, "output_lut.mp4")
    if not os.path.isabs(output_path):
        output_path = os.path.join(here, output_path)

    # 构建 LUT 滤镜
    # lut3d 直接应用（强度 1.0）
    # 部分版本 lut3d 不支持 amount 参数，用 split+blend 方式
    if intensity >= 0.99:
        vf = f"lut3d=file='{lut_path}'"
    else:
        # 混合原视频和 LUT 处理后的视频
        vf = (
            f"split=2[orig][luted];"
            f"[luted]lut3d=file='{lut_path}'[luted2];"
            f"[orig][luted2]blend=all_mode=overlay:all_opacity={intensity:.2f}"
        )

    # 判断是否需要 filter_complex
    if intensity < 0.99:
        cmd = [
            "ffmpeg", "-y",
            "-i", input_path,
            "-filter_complex", vf,
            "-map", "[vout]" if "[vout]" in vf else "0",
            "-c:v", "libx264",
            "-pix_fmt", "yuv420p",
            "-preset", "medium",
            "-crf", "20",
        ]
    else:
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
    print(f"   LUT {os.path.basename(lut_path)} 已应用（强度 {intensity}）")


if __name__ == "__main__":
    main()
