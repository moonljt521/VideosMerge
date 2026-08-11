#!/usr/bin/env python3
"""
模糊背景填充工具 —— 将竖屏视频放到横屏画布（或反之），背景用模糊画面填充。

效果类似 Instagram 的视频排版：主体居中，背景是模糊放大后的同画面。

用法：
    # 竖屏(720x960) → 横屏(1920x1080) 模糊背景
    python3 blur_bg.py -i input.mp4 -w 1920 -h 1080

    # 竖屏 → 正方形 1080x1080
    python3 blur_bg.py -i input.mp4 -w 1080 -h 1080

    # 自定义模糊强度
    python3 blur_bg.py -i input.mp4 -w 1920 -h 1080 --blur 20

    # 横屏 → 竖屏
    python3 blur_bg.py -i input.mp4 -w 1080 -h 1920

参数：
    -i / --input    输入视频路径
    -w / --width    目标画布宽度（默认 1920）
    -h / --height   目标画布高度（默认 1080）
    --blur          模糊强度（默认 12，越大越模糊）
    --bg-bright     背景亮度（0.0~1.0，默认 0.5，暗化背景突出主体）
    -o / --output   输出文件名（默认 output_blur_bg.mp4）

原理：
    1. 将视频 split 为两路
    2. 背景路：放大到画布尺寸（cover 填充）→ boxblur → 亮度降低
    3. 前景路：等比缩放到画布内（contain 居中）
    4. overlay 叠加：模糊背景 + 居中前景
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
    ap = argparse.ArgumentParser(description="模糊背景填充工具：竖屏→横屏或横屏→竖屏")
    ap.add_argument("-i", "--input", required=True, help="输入视频路径")
    ap.add_argument("-w", "--width", type=int, default=1920, help="目标画布宽度（默认 1920）")
    ap.add_argument("--height", type=int, default=1080, help="目标画布高度（默认 1080）")
    ap.add_argument("--blur", type=int, default=12, help="模糊强度（默认 12）")
    ap.add_argument("--bg-bright", type=float, default=0.5,
                    help="背景亮度 0.0~1.0（默认 0.5，暗化背景突出主体）")
    ap.add_argument("-o", "--output", default=None, help="输出文件名（默认 output_blur_bg.mp4）")
    args = ap.parse_args()

    input_path = os.path.abspath(args.input)
    if not os.path.isfile(input_path):
        print(f"❌ 文件不存在: {input_path}", file=sys.stderr)
        sys.exit(1)

    vw, vh = get_dimensions(input_path)
    audio_present = has_audio(input_path)
    cw = args.width - (args.width % 2)
    ch = args.height - (args.height % 2)

    print(f"输入: {os.path.basename(input_path)}  {vw}x{vh}")
    print(f"目标: {cw}x{ch}  模糊: {args.blur}  背景亮度: {args.bg_bright}  音频: {'有' if audio_present else '无'}")

    output_path = args.output or os.path.join(here, "output_blur_bg.mp4")
    if not os.path.isabs(output_path):
        output_path = os.path.join(here, output_path)

    # ── 构建滤镜 ──
    # 背景路：cover 缩放到画布尺寸 → 模糊 → 亮度调整
    # 前景路：contain 缩放到画布内 → 居中
    # overlay 叠加

    blur_radius = args.blur
    # boxblur 的 luma_radius 建议 = 模糊值, sigma 类似
    bg_filter = (
        f"[0:v]split=2[bg][fg];"
        # 背景：放大填充画布 → 裁剪到画布尺寸 → 模糊 → 降亮度
        f"[bg]scale={cw}:{ch}:force_original_aspect_ratio=increase:flags=lanczos,"
        f"crop={cw}:{ch},"
        f"boxblur=luma_radius={blur_radius}:luma_power=1,"
        f"eq=brightness={args.bg_bright - 1.0}:contrast=0.9:saturation=0.8[bgblur];"
        # 前景：等比缩放到画布内（contain 居中）
        f"[fg]scale={cw}:{ch}:force_original_aspect_ratio=decrease:flags=lanczos,"
        f"pad={cw}:{ch}:(ow-iw)/2:(oh-ih)/2:black,setsar=1[fgcenter];"
        # 叠加
        f"[bgblur][fgcenter]overlay=0:0[vout]"
    )

    cmd = [
        "ffmpeg", "-y",
        "-i", input_path,
        "-filter_complex", bg_filter,
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
    print(f"   {vw}x{vh} → {cw}x{ch}（模糊背景填充）")


if __name__ == "__main__":
    main()
