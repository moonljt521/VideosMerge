#!/usr/bin/env python3
"""
滤镜调色工具 —— 调整视频亮度、对比度、饱和度，或应用预设风格滤镜。

用法：
    # 手动调整：亮度+0.1，对比度+0.2，饱和度+0.3
    python3 color_filter.py -i input.mp4 -b 0.1 -c 0.2 -s 0.3

    # 只提高饱和度
    python3 color_filter.py -i input.mp4 -s 0.5

    # 使用预设滤镜：复古
    python3 color_filter.py -i input.mp4 --preset vintage

    # 使用预设滤镜：暖色
    python3 color_filter.py -i input.mp4 --preset warm

    # 预设 + 手动微调
    python3 color_filter.py -i input.mp4 --preset cool -b 0.05

参数：
    -i / --input     输入视频路径
    -b / --brightness  亮度调整（-1.0~1.0，默认 0 不变）
    -c / --contrast    对比度调整（-1.0~1.0，默认 0 不变）
    -s / --saturation  饱和度调整（-1.0~1.0，默认 0 不变）
    --hue             色调旋转（-180~180 度，默认 0）
    --gamma           伽马校正（0.1~10.0，默认 1.0 不变）
    --preset          预设风格（vintage/warm/cool/vivid/bw/bright/dark）
    -o / --output     输出文件名（默认 output_color.mp4）

原理：
    eq 滤镜：brightness=B:contrast=C:saturation=S:gamma=G
    hue 滤镜：hue=h=HUE:s=SAT
    预设 = 预设的 eq + hue 参数组合
"""

import argparse
import os
import subprocess
import sys


# ─── 预设滤镜参数 ─────────────────────────────
PRESETS = {
    "vintage": {
        # 降低饱和度 + 提亮 + 暖色调
        "brightness": 0.08,
        "contrast": -0.15,
        "saturation": -0.3,
        "hue": 15,       # 色调偏暖
        "gamma": 1.1,
    },
    "warm": {
        # 暖色调：提亮 + 加饱和 + 色调偏暖
        "brightness": 0.05,
        "contrast": 0.05,
        "saturation": 0.2,
        "hue": 10,
        "gamma": 1.0,
    },
    "cool": {
        # 冷色调：降亮度 + 加对比 + 色调偏冷
        "brightness": -0.03,
        "contrast": 0.1,
        "saturation": -0.1,
        "hue": -15,
        "gamma": 1.0,
    },
    "vivid": {
        # 鲜艳：高饱和 + 高对比
        "brightness": 0.02,
        "contrast": 0.2,
        "saturation": 0.5,
        "hue": 0,
        "gamma": 1.0,
    },
    "bw": {
        # 黑白：饱和度降为 -1
        "brightness": 0.0,
        "contrast": 0.1,
        "saturation": -1.0,
        "hue": 0,
        "gamma": 1.0,
    },
    "bright": {
        # 明亮：大幅提亮
        "brightness": 0.15,
        "contrast": -0.05,
        "saturation": 0.1,
        "hue": 0,
        "gamma": 0.9,
    },
    "dark": {
        # 暗调：降亮度 + 加对比（电影感）
        "brightness": -0.1,
        "contrast": 0.25,
        "saturation": -0.15,
        "hue": 0,
        "gamma": 1.2,
    },
}


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
    ap = argparse.ArgumentParser(description="滤镜调色工具：调整亮度/对比度/饱和度 + 预设风格")
    ap.add_argument("-i", "--input", required=True, help="输入视频路径")
    ap.add_argument("-b", "--brightness", type=float, default=0.0,
                    help="亮度调整（-1.0~1.0，默认 0）")
    ap.add_argument("-c", "--contrast", type=float, default=0.0,
                    help="对比度调整（-1.0~1.0，默认 0）")
    ap.add_argument("-s", "--saturation", type=float, default=0.0,
                    help="饱和度调整（-1.0~1.0，默认 0）")
    ap.add_argument("--hue", type=float, default=0.0,
                    help="色调旋转（-180~180 度，默认 0）")
    ap.add_argument("--gamma", type=float, default=1.0,
                    help="伽马校正（0.1~10.0，默认 1.0）")
    ap.add_argument("--preset", default=None, choices=list(PRESETS.keys()),
                    help="预设风格（vintage/warm/cool/vivid/bw/bright/dark）")
    ap.add_argument("-o", "--output", default=None, help="输出文件名（默认 output_color.mp4）")
    args = ap.parse_args()

    input_path = os.path.abspath(args.input)
    if not os.path.isfile(input_path):
        print(f"❌ 文件不存在: {input_path}", file=sys.stderr)
        sys.exit(1)

    audio_present = has_audio(input_path)

    # ── 合并预设参数和手动参数 ──
    if args.preset:
        p = PRESETS[args.preset]
        brightness = p["brightness"] + args.brightness
        contrast = p["contrast"] + args.contrast
        saturation = p["saturation"] + args.saturation
        hue = p["hue"] + args.hue
        gamma = p["gamma"] * args.gamma
        print(f"预设: {args.preset} + 手动微调")
    else:
        brightness = args.brightness
        contrast = args.contrast
        saturation = args.saturation
        hue = args.hue
        gamma = args.gamma
        print(f"手动调色")

    print(f"输入: {os.path.basename(input_path)}")
    print(f"  亮度: {brightness:+.2f}  对比度: {contrast:+.2f}  饱和度: {saturation:+.2f}")
    print(f"  色调: {hue:+.0f}°  伽马: {gamma:.2f}  音频: {'有' if audio_present else '无'}")

    # ── 构建滤镜链 ──
    filters = []
    filters.append(
        f"eq=brightness={brightness:.3f}:contrast={1.0 + contrast:.3f}:"
        f"saturation={1.0 + saturation:.3f}:gamma={gamma:.3f}"
    )
    if abs(hue) > 0.1:
        filters.append(f"hue=h={hue:.1f}")

    vf = ",".join(filters)

    output_path = args.output or os.path.join(here, "output_color.mp4")
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
    if args.preset:
        print(f"   预设 {args.preset} 已应用")


if __name__ == "__main__":
    main()
