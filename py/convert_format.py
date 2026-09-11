#!/usr/bin/env python3
"""
格式转换工具 —— 转换视频编码格式或容器格式。

支持常见编码转换（HEVC→H.264）和容器转换（MP4→MOV）。

用法：
    # HEVC → H.264（最常见：iPhone 录的视频在旧设备上播不了）
    python3 convert_format.py -i input.mp4 -c h264

    # 转为 MOV 容器
    python3 convert_format.py -i input.mp4 -f mov

    # 同时转编码和容器
    python3 convert_format.py -i input.mp4 -c h264 -f mkv

    # 指定编码器预设和质量
    python3 convert_format.py -i input.mp4 -c h264 --crf 18 --preset slow

参数：
    -i / --input    输入视频路径
    -c / --codec    目标视频编码（h264/hevc/h265/mpeg4/VP9，默认 h264）
    -f / --format   目标容器格式（mp4/mov/mkv/webm/avi，默认按编码自动）
    --crf           质量参数（默认 20，越小质量越高）
    --preset        编码预设（fast/medium/slow，默认 medium）
    --no-audio      丢弃音频
    -o / --output   输出文件名（默认 output_convert.<ext>）

原理：
    H.264: -c:v libx264 -crf 20 -preset medium
    HEVC:  -c:v libx265 -crf 28 -preset medium
    音频统一转 AAC（最大兼容性）
"""

import argparse
import os
import subprocess
import sys

CODEC_MAP = {
    "h264": ("libx264", "mp4"),
    "hevc": ("libx265", "mp4"),
    "h265": ("libx265", "mp4"),
    "mpeg4": ("mpeg4", "mp4"),
    "vp9": ("libvpx-vp9", "webm"),
}


def get_info(path: str):
    """获取视频编码、分辨率、时长、音频信息"""
    r = subprocess.run(
        ["ffprobe", "-v", "error",
         "-show_entries", "format=duration:stream=codec_name,codec_type,width,height",
         "-of", "json", path],
        capture_output=True, text=True, check=True,
    )
    import json
    info = json.loads(r.stdout)
    v_codec = None
    has_audio = False
    for s in info.get("streams", []):
        if s.get("codec_type") == "video" and not v_codec:
            v_codec = s.get("codec_name")
        elif s.get("codec_type") == "audio":
            has_audio = True
    duration = float(info.get("format", {}).get("duration", 0))
    return v_codec, has_audio, duration


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description="格式转换工具：转换视频编码和容器格式")
    ap.add_argument("-i", "--input", required=True, help="输入视频路径")
    ap.add_argument("-c", "--codec", default="h264", choices=list(CODEC_MAP.keys()),
                    help="目标视频编码（默认 h264）")
    ap.add_argument("-f", "--format", default=None, choices=["mp4", "mov", "mkv", "webm", "avi"],
                    help="目标容器格式（默认按编码自动）")
    ap.add_argument("--crf", type=int, default=20, help="质量参数（默认 20，越小越好）")
    ap.add_argument("--preset", default="medium", choices=["fast", "medium", "slow"],
                    help="编码预设（默认 medium）")
    ap.add_argument("--no-audio", action="store_true", help="丢弃音频")
    ap.add_argument("-o", "--output", default=None, help="输出文件名")
    args = ap.parse_args()

    input_path = os.path.abspath(args.input)
    if not os.path.isfile(input_path):
        print(f"❌ 文件不存在: {input_path}", file=sys.stderr)
        sys.exit(1)

    src_codec, audio_present, duration = get_info(input_path)
    encoder, default_ext = CODEC_MAP[args.codec]
    container = args.format or default_ext

    # HEVC 默认 crf 调高（libx265 的 crf 尺度不同）
    crf = args.crf
    if args.codec in ("hevc", "h265") and crf == 20:
        crf = 28

    print(f"输入: {os.path.basename(input_path)}")
    print(f"  原编码: {src_codec}  时长: {duration:.2f}s  音频: {'有' if audio_present else '无'}")
    print(f"目标: 编码={args.codec}({encoder})  容器={container}  crf={crf}  preset={args.preset}")

    # 输出路径
    if args.output:
        output_path = args.output
        if not os.path.isabs(output_path):
            output_path = os.path.join(here, output_path)
    else:
        output_path = os.path.join(here, f"output_convert.{container}")
    if not os.path.isabs(output_path):
        output_path = os.path.join(here, output_path)

    # 构建 ffmpeg 命令
    cmd = [
        "ffmpeg", "-y",
        "-i", input_path,
        "-c:v", encoder,
        "-crf", str(crf),
        "-preset", args.preset,
    ]
    if args.codec == "h264":
        cmd.append("-pix_fmt")
        cmd.append("yuv420p")

    keep_audio = audio_present and not args.no_audio
    if keep_audio:
        cmd.extend(["-c:a", "aac", "-b:a", "192k"])
    else:
        cmd.append("-an")

    cmd.append(output_path)

    print(f"\n运行 ffmpeg...")
    print(f"  {' '.join(cmd)}\n")
    subprocess.run(cmd, check=True)
    print(f"✅ 完成: {output_path}")
    print(f"   {src_codec} → {args.codec} ({container})")


if __name__ == "__main__":
    main()
