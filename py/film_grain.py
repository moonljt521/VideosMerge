#!/usr/bin/env python3
"""
胶片颗粒工具 —— 为视频添加胶片颗粒/噪点效果，营造复古胶片质感。

用法：
    # 默认中等强度颗粒
    python3 film_grain.py -i input.mp4

    # 强颗粒
    python3 film_grain.py -i input.mp4 --strength 40

    # 轻微颗粒
    python3 film_grain.py -i input.mp4 --strength 15

    # 颗粒 + 复古调色
    python3 film_grain.py -i input.mp4 --strength 30 --vintage

参数：
    -i / --input      输入视频路径
    --strength        颗粒强度 0~100（默认 25）
    --vintage         叠加复古调色（降低饱和度 + 暖色调）
    --monochrome      黑白颗粒（默认彩色）
    -o / --output     输出文件名（默认 output_grain.mp4）

原理：
    noise 滤镜：noise=alls=STRENGTH:allf=t
    复古调色：eq=saturation=0.7 + hue=h=10
    黑白：eq=saturation=0
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
    ap = argparse.ArgumentParser(description="胶片颗粒工具：添加噪点复古质感")
    ap.add_argument("-i", "--input", required=True, help="输入视频路径")
    ap.add_argument("--strength", type=int, default=25,
                    help="颗粒强度 0~100（默认 25）")
    ap.add_argument("--vintage", action="store_true", help="叠加复古调色")
    ap.add_argument("--monochrome", action="store_true", help="黑白颗粒")
    ap.add_argument("-o", "--output", default=None, help="输出文件名（默认 output_grain.mp4）")
    args = ap.parse_args()

    input_path = os.path.abspath(args.input)
    if not os.path.isfile(input_path):
        print(f"❌ 文件不存在: {input_path}", file=sys.stderr)
        sys.exit(1)

    audio_present = has_audio(input_path)
    strength = max(0, min(100, args.strength))

    filters = []

    # 颗粒噪点
    filters.append(f"noise=alls={strength}:allf=t")

    # 复古调色
    if args.vintage:
        filters.append("eq=saturation=0.7:brightness=0.03:contrast=-0.05")
        filters.append("hue=h=10")

    # 黑白
    if args.monochrome:
        filters.append("eq=saturation=0")

    vf = ",".join(filters)

    print(f"输入: {os.path.basename(input_path)}")
    print(f"  颗粒强度: {strength}  复古: {'是' if args.vintage else '否'}  黑白: {'是' if args.monochrome else '否'}")

    output_path = args.output or os.path.join(here, "output_grain.mp4")
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
    print(f"   颗粒强度 {strength}" + (" + 复古" if args.vintage else "") + (" + 黑白" if args.monochrome else ""))


if __name__ == "__main__":
    main()
