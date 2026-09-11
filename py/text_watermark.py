#!/usr/bin/env python3
"""
文字水印工具 —— 在视频画面上叠加文字水印（标题、时间戳、署名等）。

使用 Pillow 生成透明 PNG 文字图层，再用 ffmpeg overlay 叠加到视频上。
不依赖 ffmpeg drawtext 滤镜（部分 ffmpeg 编译未包含该滤镜）。

支持 9 宫格位置预设和自定义坐标，可调节字体大小/颜色/描边。

用法：
    # 右下角添加文字
    python3 text_watermark.py -i input.mp4 -t "@moon" --pos bottom-right

    # 左上角，大字
    python3 text_watermark.py -i input.mp4 -t "标题" --pos top-left --size 36

    # 自定义位置和样式
    python3 text_watermark.py -i input.mp4 -t "2024.01" -x 50 -y 50 --size 28 --color yellow

    # 带描边
    python3 text_watermark.py -i input.mp4 -t "CONFIDENTIAL" --pos center --size 48 --color red --border white

    # 半透明文字
    python3 text_watermark.py -i input.mp4 -t "DRAFT" --pos center --size 60 --opacity 0.5

参数：
    -i / --input       输入视频路径
    -t / --text         水印文字内容
    --pos               位置预设：top-left/top-right/bottom-left/bottom-right/center（默认 bottom-right）
    -x / --x            自定义 X 坐标（优先于 --pos）
    -y / --y            自定义 Y 坐标
    --size              字体大小（默认 24）
    --color             文字颜色（默认 white，支持 red/green/blue/yellow/cyan/white/black 或 #RRGGBB）
    --opacity           透明度 0.0~1.0（默认 1.0 不透明）
    --border            描边颜色（默认无描边，设为 black/white 等启用）
    --border-width      描边宽度（默认 2）
    --font              字体文件路径（默认自动查找系统字体）
    -m / --margin       边距像素（默认 20，用于位置预设）
    -o / --output       输出文件名（默认 output_text.mp4）

原理：
    1. 用 Pillow (PIL) 绘制文字到透明 PNG（文字+描边+透明度）
    2. 用 ffmpeg overlay 滤镜将 PNG 叠加到视频上
    3. 自动清理临时 PNG 文件
"""

import argparse
import os
import subprocess
import sys
import tempfile

try:
    from PIL import Image, ImageDraw, ImageFont
    _PIL_AVAILABLE = True
except ImportError:
    _PIL_AVAILABLE = False


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


def find_system_font():
    """查找系统可用字体文件路径"""
    candidates = [
        "/System/Library/Fonts/PingFang.ttc",
        "/System/Library/Fonts/Helvetica.ttc",
        "/System/Library/Fonts/STHeiti Light.ttc",
        "/Library/Fonts/Arial Unicode.ttf",
        "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    ]
    for path in candidates:
        if os.path.isfile(path):
            return path
    return None


# ─── 颜色名称转 RGB ──────────────────────────────
COLOR_MAP = {
    "white":  (255, 255, 255),
    "black":  (0, 0, 0),
    "red":    (255, 0, 0),
    "green":  (0, 255, 0),
    "blue":   (0, 0, 255),
    "yellow": (255, 255, 0),
    "cyan":   (0, 255, 255),
    "magenta": (255, 0, 255),
    "gray":   (128, 128, 128),
    "grey":   (128, 128, 128),
}


def parse_color(color_str):
    """解析颜色字符串，返回 (R, G, B)"""
    color_str = color_str.strip().lower()
    if color_str in COLOR_MAP:
        return COLOR_MAP[color_str]
    if color_str.startswith("#") and len(color_str) == 7:
        return (int(color_str[1:3], 16), int(color_str[3:5], 16), int(color_str[5:7], 16))
    # 尝试直接解析
    try:
        parts = color_str.split(",")
        if len(parts) == 3:
            return (int(parts[0]), int(parts[1]), int(parts[2]))
    except (ValueError, IndexError):
        pass
    print(f"⚠️  无法解析颜色 '{color_str}'，使用白色", file=sys.stderr)
    return (255, 255, 255)


def create_text_png(text, font_path, font_size, color_rgb, border_rgb, border_width, opacity, video_w, video_h):
    """
    用 Pillow 创建透明背景的文字 PNG 图片。
    返回 (png_path, text_w, text_h)
    """
    # 加载字体
    try:
        if font_path:
            font = ImageFont.truetype(font_path, font_size)
        else:
            font = ImageFont.load_default()
    except Exception:
        font = ImageFont.load_default()

    # 先创建临时图片测量文字尺寸
    tmp = Image.new("RGBA", (1, 1), (0, 0, 0, 0))
    tmp_draw = ImageDraw.Draw(tmp)
    bbox = tmp_draw.textbbox((0, 0), text, font=font)
    text_w = bbox[2] - bbox[0]
    text_h = bbox[3] - bbox[1]

    # 加上描边余量
    pad = border_width + 4 if border_rgb else 4
    img_w = text_w + pad * 2
    img_h = text_h + pad * 2

    # 创建实际图片
    img = Image.new("RGBA", (img_w, img_h), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # 计算透明度
    alpha = int(255 * opacity)
    text_color = (*color_rgb, alpha)

    # 绘制描边（如果指定了描边颜色）
    if border_rgb:
        border_color = (*border_rgb, alpha)
        bw = border_width
        for dx in range(-bw, bw + 1):
            for dy in range(-bw, bw + 1):
                if dx * dx + dy * dy <= bw * bw:
                    draw.text((pad - bbox[0] + dx, pad - bbox[1] + dy), text, font=font, fill=border_color)

    # 绘制文字
    draw.text((pad - bbox[0], pad - bbox[1]), text, font=font, fill=text_color)

    # 保存为 PNG
    png_path = tempfile.mktemp(suffix=".png")
    img.save(png_path, "PNG")

    return png_path, img_w, img_h


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description="文字水印工具：在视频上叠加文字")
    ap.add_argument("-i", "--input", required=True, help="输入视频路径")
    ap.add_argument("-t", "--text", required=True, help="水印文字内容")
    ap.add_argument("--pos", default="bottom-right",
                    choices=["top-left", "top-right", "bottom-left", "bottom-right", "center"],
                    help="水印位置预设（默认 bottom-right）")
    ap.add_argument("-x", type=int, default=None, help="自定义 X 坐标")
    ap.add_argument("-y", type=int, default=None, help="自定义 Y 坐标")
    ap.add_argument("--size", type=int, default=24, help="字体大小（默认 24）")
    ap.add_argument("--color", default="white", help="文字颜色（默认 white）")
    ap.add_argument("--opacity", type=float, default=1.0, help="透明度 0.0~1.0（默认 1.0）")
    ap.add_argument("--border", default=None, help="描边颜色（默认无）")
    ap.add_argument("--border-width", type=int, default=2, help="描边宽度（默认 2）")
    ap.add_argument("--font", default=None, help="字体文件路径（默认自动查找）")
    ap.add_argument("-m", "--margin", type=int, default=20, help="边距像素（默认 20）")
    ap.add_argument("-o", "--output", default=None, help="输出文件名（默认 output_text.mp4）")
    args = ap.parse_args()

    # ── 检查 Pillow ──
    if not _PIL_AVAILABLE:
        print("❌ 需要 Pillow 库来生成文字图层", file=sys.stderr)
        print("   安装：pip3 install Pillow", file=sys.stderr)
        sys.exit(1)

    input_path = os.path.abspath(args.input)
    if not os.path.isfile(input_path):
        print(f"❌ 文件不存在: {input_path}", file=sys.stderr)
        sys.exit(1)

    vw, vh = get_dimensions(input_path)
    audio_present = has_audio(input_path)

    # ── 查找字体 ──
    font_path = args.font or find_system_font()
    color_rgb = parse_color(args.color)
    border_rgb = parse_color(args.border) if args.border else None
    opacity = max(0.0, min(1.0, args.opacity))

    print(f"输入: {os.path.basename(input_path)}  {vw}x{vh}")
    print(f"文字: \"{args.text}\"")
    print(f"  字号: {args.size}  颜色: {args.color}  描边: {args.border or '无'}  透明度: {opacity}")
    print(f"  字体: {os.path.basename(font_path) if font_path else '默认'}")
    print(f"  音频: {'有' if audio_present else '无'}")

    # ── 生成文字 PNG ──
    png_path, text_w, text_h = create_text_png(
        args.text, font_path, args.size, color_rgb,
        border_rgb, args.border_width, opacity, vw, vh
    )

    # ── 计算 overlay 位置 ──
    margin = args.margin
    if args.x is not None and args.y is not None:
        ox, oy = args.x, args.y
    else:
        pos = args.pos
        if pos == "top-left":
            ox, oy = margin, margin
        elif pos == "top-right":
            ox, oy = vw - text_w - margin, margin
        elif pos == "bottom-left":
            ox, oy = margin, vh - text_h - margin
        elif pos == "bottom-right":
            ox, oy = vw - text_w - margin, vh - text_h - margin
        else:  # center
            ox, oy = (vw - text_w) // 2, (vh - text_h) // 2

    print(f"  文字尺寸: {text_w}x{text_h}  位置: ({ox},{oy})")

    # ── 构建 ffmpeg 命令 ──
    output_path = args.output or os.path.join(here, "output_text.mp4")
    if not os.path.isabs(output_path):
        output_path = os.path.join(here, output_path)

    # 用 overlay 叠加 PNG 文字层
    filter_complex = f"[0:v][1:v]overlay={ox}:{oy}[vout]"

    cmd = [
        "ffmpeg", "-y",
        "-i", input_path,
        "-i", png_path,
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

    try:
        subprocess.run(cmd, check=True)
        print(f"✅ 完成: {output_path}")
        print(f"   文字 \"{args.text}\" 已叠加")
    finally:
        # 清理临时 PNG
        if os.path.isfile(png_path):
            os.remove(png_path)


if __name__ == "__main__":
    main()
