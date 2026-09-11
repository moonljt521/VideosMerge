#!/usr/bin/env python3
"""
视频压缩工具 —— 通过调整编码参数大幅减小视频文件体积。

用法：
    # 标准压缩（crf 28，比默认 20 体积小很多）
    python3 compress.py -i input.mp4

    # 极限压缩（crf 32 + 低分辨率 + 失真音频）
    python3 compress.py -i input.mp4 --crf 32 --scale 0.5 --audio-bitrate 96

    # 自定义压缩
    python3 compress.py -i input.mp4 --crf 26 --preset slow

    # 保留原分辨率，只压码率
    python3 compress.py -i input.mp4 --crf 30 --no-scale

参数：
    -i / --input        输入视频路径
    --crf              质量参数（默认 28，越大体积越小，范围 18~40）
    --scale            分辨率缩放比例（默认 0.75，如 0.5 = 缩小一半）
    --no-scale         不缩放分辨率，只调质量
    --preset           编码预设（默认 medium，可选 fast/slow）
    --audio-bitrate    音频比特率 kbps（默认 128）
    --no-audio         丢弃音频
    -o / --output      输出文件名（默认 output_compress.mp4）

原理：
    -crf 越大 → 质量越低 → 体积越小
    --scale 缩小分辨率 → 像素减少 → 体积减小
    --audio-bitrate 降低 → 音频体积减小
    输出 H.264 + AAC（最大兼容性）
"""

import argparse
import os
import subprocess
import sys


def get_info(path: str):
    """获取视频信息：分辨率、时长、文件大小"""
    r = subprocess.run(
        ["ffprobe", "-v", "error",
         "-show_entries", "format=duration:stream=width,height,codec_type",
         "-of", "json", path],
        capture_output=True, text=True, check=True,
    )
    import json
    info = json.loads(r.stdout)
    vw = vh = 0
    has_audio = False
    for s in info.get("streams", []):
        if s.get("codec_type") == "video" and vw == 0:
            vw, vh = int(s["width"]), int(s["height"])
        elif s.get("codec_type") == "audio":
            has_audio = True
    duration = float(info.get("format", {}).get("duration", 0))
    file_size = os.path.getsize(path)
    return vw, vh, duration, has_audio, file_size


def human_size(n: int) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024:
            return f"{n:.0f}{unit}"
        n /= 1024
    return f"{n:.1f}TB"


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description="视频压缩工具：减小文件体积")
    ap.add_argument("-i", "--input", required=True, help="输入视频路径")
    ap.add_argument("--crf", type=int, default=28, help="质量参数（默认 28，越大体积越小）")
    ap.add_argument("--scale", type=float, default=0.75, help="分辨率缩放比例（默认 0.75）")
    ap.add_argument("--no-scale", action="store_true", help="不缩放分辨率")
    ap.add_argument("--preset", default="medium", choices=["fast", "medium", "slow"],
                    help="编码预设（默认 medium）")
    ap.add_argument("--audio-bitrate", type=int, default=128, help="音频比特率 kbps（默认 128）")
    ap.add_argument("--no-audio", action="store_true", help="丢弃音频")
    ap.add_argument("-o", "--output", default=None, help="输出文件名（默认 output_compress.mp4）")
    args = ap.parse_args()

    input_path = os.path.abspath(args.input)
    if not os.path.isfile(input_path):
        print(f"❌ 文件不存在: {input_path}", file=sys.stderr)
        sys.exit(1)

    vw, vh, duration, audio_present, src_size = get_info(input_path)

    # 计算目标分辨率
    if args.no_scale:
        dst_w, dst_h = vw, vh
    else:
        dst_w = round(vw * args.scale)
        dst_h = round(vh * args.scale)
    dst_w -= dst_w % 2
    dst_h -= dst_h % 2

    print(f"输入: {os.path.basename(input_path)}")
    print(f"  原始: {vw}x{vh}  {duration:.1f}s  {human_size(src_size)}  音频: {'有' if audio_present else '无'}")
    print(f"  目标: {dst_w}x{dst_h}  crf={args.crf}  preset={args.preset}")

    output_path = args.output or os.path.join(here, "output_compress.mp4")
    if not os.path.isabs(output_path):
        output_path = os.path.join(here, output_path)

    # 构建滤镜
    if not args.no_scale and (dst_w != vw or dst_h != vh):
        vf = f"scale={dst_w}:{dst_h}:flags=lanczos,setsar=1"
    else:
        vf = None

    cmd = [
        "ffmpeg", "-y",
        "-i", input_path,
    ]
    if vf:
        cmd.extend(["-vf", vf])
    cmd.extend([
        "-c:v", "libx264",
        "-crf", str(args.crf),
        "-preset", args.preset,
        "-pix_fmt", "yuv420p",
    ])

    keep_audio = audio_present and not args.no_audio
    if keep_audio:
        cmd.extend(["-c:a", "aac", "-b:a", f"{args.audio_bitrate}k"])
    else:
        cmd.append("-an")
    cmd.append(output_path)

    print(f"\n运行 ffmpeg...")
    print(f"  {' '.join(cmd)}\n")
    subprocess.run(cmd, check=True)

    dst_size = os.path.getsize(output_path)
    ratio = (1 - dst_size / src_size) * 100 if src_size > 0 else 0
    print(f"✅ 完成: {output_path}")
    print(f"   {human_size(src_size)} → {human_size(dst_size)}  压缩了 {ratio:.1f}%")


if __name__ == "__main__":
    main()
