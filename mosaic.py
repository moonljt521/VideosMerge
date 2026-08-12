#!/usr/bin/env python3
"""
局部马赛克工具 —— 对视频画面的指定区域打马赛克/模糊处理。

支持手动指定区域和自动人脸区域（需 OpenCV）。

用法：
    # 对左上角 200x200 区域打码
    python3 mosaic.py -i input.mp4 -x 0 -y 0 -w 200 --height 200

    # 对右下角区域模糊
    python3 mosaic.py -i input.mp4 -x 500 -y 700 -w 200 --height 200 --blur

    # 自动检测人脸打码
    python3 mosaic.py -i input.mp4 --auto-face

参数：
    -i / --input    输入视频路径
    -x / --x        马赛克区域起始 X 坐标
    -y / --y        马赛克区域起始 Y 坐标
    -w / --width    马赛克区域宽度
    --height        马赛克区域高度
    --blur          使用模糊代替马赛克（更自然）
    --auto-face     自动检测人脸区域打码（需 OpenCV）
    -o / --output   输出文件名（默认 output_mosaic.mp4）

原理：
    1. split 视频为背景流 + 处理流
    2. 处理流裁剪出指定区域 → 模糊/马赛克 → 缩放回原尺寸
    3. overlay 叠加回背景的指定位置
    --auto-face 用 OpenCV Haar 检测人脸，多帧采样取包围盒
"""

import argparse
import os
import subprocess
import sys

try:
    import cv2
    import numpy as np
    _CV2_AVAILABLE = True
except ImportError:
    _CV2_AVAILABLE = False

VIDEO_EXTS = (".mp4", ".mov", ".mkv", ".avi", ".m4v", ".webm", ".flv", ".ts")


def get_dimensions(path: str):
    r = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0",
         "-show_entries", "stream=width,height",
         "-of", "csv=p=0:s=x", path],
        capture_output=True, text=True, check=True,
    )
    w, h = r.stdout.strip().split("x")
    return int(w), int(h)


def get_duration(path: str) -> float:
    r = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "default=noprint_wrappers=1:nokey=1", path],
        capture_output=True, text=True, check=True,
    )
    return float(r.stdout.strip())


def has_audio(path: str) -> bool:
    r = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "a",
         "-show_entries", "stream=codec_type",
         "-of", "default=noprint_wrappers=1:nokey=1", path],
        capture_output=True, text=True,
    )
    return "audio" in r.stdout.strip()


def detect_face_bbox(path: str, duration: float):
    """检测人脸，返回 (fx_min, fy_min, fx_max, fy_max) 或 None"""
    if not _CV2_AVAILABLE:
        return None
    cascade_path = os.path.join(cv2.data.haarcascades, "haarcascade_frontalface_default.xml")
    if not os.path.isfile(cascade_path):
        return None
    classifier = cv2.CascadeClassifier(cascade_path)
    if classifier.empty():
        return None

    all_faces = []
    for frac in (0.1, 0.25, 0.5, 0.75):
        ts = duration * frac
        if ts < 0.3:
            continue
        r = subprocess.run(
            ["ffmpeg", "-hide_banner", "-ss", f"{ts:.2f}", "-i", path,
             "-frames:v", "1", "-f", "image2pipe", "-vcodec", "png", "-"],
            capture_output=True, timeout=15,
        )
        if not r.stdout:
            continue
        arr = np.frombuffer(r.stdout, np.uint8)
        frame = cv2.imdecode(arr, cv2.IMREAD_COLOR)
        if frame is None:
            continue
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        faces = classifier.detectMultiScale(gray, scaleFactor=1.1, minNeighbors=3, minSize=(50, 50))
        for (x, y, w, h) in faces:
            all_faces.append((x, y, x + w, y + h))

    if not all_faces:
        return None
    fx_min = min(f[0] for f in all_faces)
    fy_min = min(f[1] for f in all_faces)
    fx_max = max(f[2] for f in all_faces)
    fy_max = max(f[3] for f in all_faces)
    return fx_min, fy_min, fx_max, fy_max


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description="局部马赛克工具：对指定区域打码/模糊")
    ap.add_argument("-i", "--input", required=True, help="输入视频路径")
    ap.add_argument("-x", type=int, default=None, help="马赛克区域起始 X")
    ap.add_argument("-y", type=int, default=None, help="马赛克区域起始 Y")
    ap.add_argument("-w", "--width", type=int, default=None, help="马赛克区域宽度")
    ap.add_argument("--height", type=int, default=None, help="马赛克区域高度")
    ap.add_argument("--blur", action="store_true", help="使用模糊代替马赛克")
    ap.add_argument("--auto-face", action="store_true", help="自动检测人脸区域打码")
    ap.add_argument("-o", "--output", default=None, help="输出文件名（默认 output_mosaic.mp4）")
    args = ap.parse_args()

    input_path = os.path.abspath(args.input)
    if not os.path.isfile(input_path):
        print(f"❌ 文件不存在: {input_path}", file=sys.stderr)
        sys.exit(1)

    vw, vh = get_dimensions(input_path)
    duration = get_duration(input_path)
    audio_present = has_audio(input_path)

    # ── 确定打码区域 ──
    if args.auto_face:
        print("检测人脸中...")
        bbox = detect_face_bbox(input_path, duration)
        if bbox:
            mx, my, mx2, my2 = bbox
            # 扩大一点区域，确保覆盖完整面部
            pad_w = int((mx2 - mx) * 0.2)
            pad_h = int((my2 - my) * 0.3)
            mx = max(0, mx - pad_w)
            my = max(0, my - pad_h)
            mx2 = min(vw, mx2 + pad_w)
            my2 = min(vh, my2 + pad_h)
            mw = mx2 - mx
            mh = my2 - my
            print(f"  人脸区域: ({mx},{my}) {mw}x{mh}")
        else:
            print("  ⚠️  未检测到人脸，使用中心 1/3 区域")
            mw = vw // 3
            mh = vh // 3
            mx = (vw - mw) // 2
            my = (vh - mh) // 2
    elif args.x is not None and args.y is not None and args.width and args.height:
        mx, my, mw, mh = args.x, args.y, args.width, args.height
    else:
        print("❌ 请指定 --auto-face 或 -x/-y/-w/--height", file=sys.stderr)
        sys.exit(1)

    # 边界检查 + 偶数
    mx = max(0, min(mx, vw - mw))
    my = max(0, min(my, vh - mh))
    mw = min(mw, vw - mx)
    mh = min(mh, vh - my)
    mw -= mw % 2
    mh -= mh % 2

    print(f"输入: {os.path.basename(input_path)}  {vw}x{vh}  {duration:.2f}s")
    print(f"  打码区域: ({mx},{my}) {mw}x{mh}  模式: {'模糊' if args.blur else '马赛克'}")

    output_path = args.output or os.path.join(here, "output_mosaic.mp4")
    if not os.path.isabs(output_path):
        output_path = os.path.join(here, output_path)

    # ── 构建滤镜 ──
    # 裁剪指定区域 → 处理（马赛克/模糊）→ overlay 回去
    if args.blur:
        # 模糊
        region_filter = f"boxblur=luma_radius=20:luma_power=2"
    else:
        # 马赛克：缩小再放大
        block = max(4, min(mw, mh) // 10)
        region_filter = f"scale={max(2,mw//block)}:{max(2,mh//block)},scale={mw}:{mh}:flags=neighbor"

    filter_complex = (
        f"[0:v]split=2[bg][fg];"
        f"[fg]crop={mw}:{mh}:{mx}:{my},{region_filter}[mosaic];"
        f"[bg][mosaic]overlay={mx}:{my}[vout]"
    )

    cmd = [
        "ffmpeg", "-y",
        "-i", input_path,
        "-filter_complex", filter_complex,
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
    print(f"   区域 ({mx},{my}) {mw}x{mh} 已{'模糊' if args.blur else '马赛克'}处理")


if __name__ == "__main__":
    main()
