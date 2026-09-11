#!/usr/bin/env python3
"""
视频防抖工具 —— 检测并修正视频画面抖动，产生稳定画面。

两步处理：先分析抖动数据，再应用变换修正。

用法：
    # 默认强度防抖
    python3 stabilize.py -i input.mp4

    # 调整平滑度（越大越稳定但画面裁剪更多）
    python3 stabilize.py -i input.mp4 --smooth 15

    # 轻度防抖（少裁剪）
    python3 stabilize.py -i input.mp4 --smooth 5

    # 较大裁剪换取更强防抖
    python3 stabilize.py -i input.mp4 --smooth 20 --crop 0.8

参数：
    -i / --input    输入视频路径
    --smooth        平滑度（默认 10，越大越稳定但裁剪越多）
    --crop          裁剪比例 0.5~1.0（默认 0.9，1.0=不裁剪）
    --zoom          缩放补偿（默认 0，正值放大填补黑边）
    -o / --output   输出文件名（默认 output_stabilize.mp4）

原理：
    第一步：vidstabdetect=shakiness=5:accuracy=10 → 生成 transforms.trf
    第二步：vidstabtransform=input=transforms.trf:smoothing=S:zoom=Z:crop=C
    需要两次 ffmpeg 调用
"""

import argparse
import os
import subprocess
import sys
import tempfile


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
    ap = argparse.ArgumentParser(description="视频防抖工具：检测并修正画面抖动")
    ap.add_argument("-i", "--input", required=True, help="输入视频路径")
    ap.add_argument("--smooth", type=int, default=10, help="平滑度（默认 10，越大越稳定）")
    ap.add_argument("--crop", type=float, default=0.9, help="裁剪比例 0.5~1.0（默认 0.9）")
    ap.add_argument("--zoom", type=float, default=0.0, help="缩放补偿（默认 0）")
    ap.add_argument("-o", "--output", default=None, help="输出文件名（默认 output_stabilize.mp4）")
    args = ap.parse_args()

    input_path = os.path.abspath(args.input)
    if not os.path.isfile(input_path):
        print(f"❌ 文件不存在: {input_path}", file=sys.stderr)
        sys.exit(1)

    audio_present = has_audio(input_path)
    smooth = max(1, args.smooth)
    crop = max(0.5, min(1.0, args.crop))
    zoom = args.zoom

    # 检测 vidstab 是否可用，否则回退到 deshake
    r = subprocess.run(["ffmpeg", "-filters"], capture_output=True, text=True)
    has_vidstab = "vidstab" in r.stdout

    output_path = args.output or os.path.join(here, "output_stabilize.mp4")
    if not os.path.isabs(output_path):
        output_path = os.path.join(here, output_path)

    print(f"输入: {os.path.basename(input_path)}")
    print(f"  平滑度: {smooth}  裁剪: {crop}  缩放: {zoom}")
    print(f"  防抖滤镜: {'vidstabdetect+vidstabtransform' if has_vidstab else 'deshake (vidstab 不可用)'}")

    if has_vidstab:
        # ── vidstab 两步法 ──
        trf_path = tempfile.mktemp(suffix=".trf")

        # 第一步：检测抖动
        print("\n步骤 1/2: 检测抖动...")
        detect_cmd = [
            "ffmpeg", "-y",
            "-i", input_path,
            "-vf", f"vidstabdetect=shakiness=5:accuracy=10:result={trf_path}",
            "-an", "-f", "null", "-",
        ]
        print(f"  {' '.join(detect_cmd)}")
        detect_result = subprocess.run(detect_cmd, capture_output=True, text=True)
        if detect_result.returncode != 0:
            print(f"❌ 检测失败: {detect_result.stderr[-300:]}", file=sys.stderr)
            if os.path.isfile(trf_path):
                os.remove(trf_path)
            sys.exit(1)

        # 第二步：应用防抖
        print("步骤 2/2: 应用防抖...")
        vf = (
            f"vidstabtransform=input={trf_path}:smoothing={smooth}:"
            f"crop={crop}:zoom={zoom},unsharp=5:5:0.8:3:3:0.4"
        )
        try:
            subprocess.run([
                "ffmpeg", "-y", "-i", input_path,
                "-vf", vf,
                "-c:v", "libx264", "-pix_fmt", "yuv420p",
                "-preset", "medium", "-crf", "20",
            ] + (["-c:a", "aac", "-b:a", "192k"] if audio_present else ["-an"]) + [output_path], check=True)
        finally:
            if os.path.isfile(trf_path):
                os.remove(trf_path)
    else:
        # ── deshake 一步法（效果不如 vidstab 但兼容性好）──
        print("\n使用 deshake 滤镜防抖...")
        vf = f"deshake=rx=64:ry=64:edge=clamp,unsharp=5:5:0.8:3:3:0.4"
        subprocess.run([
            "ffmpeg", "-y", "-i", input_path,
            "-vf", vf,
            "-c:v", "libx264", "-pix_fmt", "yuv420p",
            "-preset", "medium", "-crf", "20",
        ] + (["-c:a", "aac", "-b:a", "192k"] if audio_present else ["-an"]) + [output_path], check=True)

    print(f"\n✅ 完成: {output_path}")
    print(f"   防抖完成（smooth={smooth} crop={crop} zoom={zoom}）")


if __name__ == "__main__":
    main()
