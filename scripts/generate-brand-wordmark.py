#!/usr/bin/env python3
"""生成 cetus 品牌字标（启动图），可复现。

## 为什么需要这个脚本

品牌从 DeepLinks 改为 cetus 时，`strings.xml` / locale 表的字符串替换
**碰不到启动图** —— 启动图里的品牌名是烧进位图 / 矢量里的文字。
结果就是「字符串全改完、门禁全绿，用户冷启动仍看到 DeepLinks」。
把字标生成脚本化，既让本次改动可复现，也避免以后重蹈覆辙。

## 用法

    python3 scripts/generate-brand-wordmark.py            # 生成全部 4 个产物
    python3 scripts/generate-brand-wordmark.py --check    # 校验产物与脚本期望一致（CI 可跑）

## 产物（路径 / 格式 / 尺寸与改动前的既有资产一一对应，均未改变）

    apps/android/app/src/main/res/drawable-nodpi/splash_wordmark.png        1024×1024 PNG
    apps/android/app/src/main/res/drawable-night-nodpi/splash_wordmark.png  1024×1024 PNG
    apps/ios/App/Resources/Assets.xcassets/LaunchBrand.imageset/launch-brand-light.pdf
    apps/ios/App/Resources/Assets.xcassets/LaunchBrand.imageset/launch-brand-dark.pdf

## 设计取值来源：对**改动前原始资产**的像素测量（不是猜的）

见 docs/cetus/evidence/brand-asset-gap-splash-wordmark.md。摘要：

| 项 | 浅色 | 夜间 |
|---|---|---|
| Android 背景 | `#FFFFFF` | `#313234` |
| Android 字色 | `#2563D8` | `#EBEFF7` |

字面几何（两平台一致）：

- 画布 1024×1024，**不透明**
- cap height 102 px（原始资产 D 字形 447..548）
- 墨迹宽 681 px、垂直**光学居中**（原上边距 446 / 下边距 447）
- 字重 = Helvetica Neue **Medium**：对 5 个候选做定量比对，
  Medium 的 stem/cap=0.136 vs 原 0.137（Δ0.002）、W/cap=5.281 vs 5.183（Δ0.098）为最优；
  Bold 明显偏重（stem/cap 0.178）。**两平台其实是同一字重**。

## 依赖

Python 3 + Pillow + fontTools + macOS 系统字体 Helvetica Neue（`/System/Library/Fonts/HelveticaNeue.ttc`，Medium = index 10）。
CI 不需要跑生成；本脚本用于「需要重新生成时」的复现与校验。
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

try:
    from PIL import Image, ImageDraw, ImageFont
    from fontTools.pens.basePen import decomposeQuadraticSegment
    from fontTools.pens.recordingPen import RecordingPen
    from fontTools.ttLib import TTFont
except ImportError as exc:  # pragma: no cover
    print(f"缺少依赖：{exc}. 需要 Pillow 与 fontTools。", file=sys.stderr)
    raise SystemExit(2)

REPO_ROOT = Path(__file__).resolve().parent.parent

# --- 命名合同 §1.1：用户可见品牌名一律小写 cetus ---------------------------
WORDMARK_TEXT = "cetus"

HELVETICA_NEUE = Path("/System/Library/Fonts/HelveticaNeue.ttc")
HELVETICA_NEUE_MEDIUM_INDEX = 10  # 实测比对选出的字面

CANVAS = 1024
CAP_HEIGHT_PX = 102  # 原始资产 D 字形高度
TARGET_INK_WIDTH_PX = 681  # 原始资产墨迹宽度（用于校验，不强制拉伸）

PALETTE = {
    "android_light": {"background": (255, 255, 255), "ink": (0x25, 0x63, 0xD8)},
    "android_night": {"background": (0x31, 0x32, 0x34), "ink": (0xEB, 0xEF, 0xF7)},
}

OUTPUTS = {
    "android_light": REPO_ROOT
    / "apps/android/app/src/main/res/drawable-nodpi/splash_wordmark.png",
    "android_night": REPO_ROOT
    / "apps/android/app/src/main/res/drawable-night-nodpi/splash_wordmark.png",
    "ios_light": REPO_ROOT
    / "apps/ios/App/Resources/Assets.xcassets/LaunchBrand.imageset/launch-brand-light.pdf",
    "ios_dark": REPO_ROOT
    / "apps/ios/App/Resources/Assets.xcassets/LaunchBrand.imageset/launch-brand-dark.pdf",
}

# iOS 矢量 PDF 的页面尺寸：沿用原资产比例（119.05 × 36.20 pt），保持
# UILaunchScreen 里的视觉大小不变。
IOS_PAGE_WIDTH_PT = 119.0493
IOS_PAGE_HEIGHT_PT = 36.20354


def load_font() -> TTFont:
    if not HELVETICA_NEUE.is_file():
        raise SystemExit(f"缺少系统字体 {HELVETICA_NEUE}")
    return TTFont(str(HELVETICA_NEUE), fontNumber=HELVETICA_NEUE_MEDIUM_INDEX)


def text_width_units(font: TTFont, text: str) -> float:
    """按 advance width 计算文本宽度（字体单位）。"""
    cmap = font.getBestCmap()
    hmtx = font["hmtx"]
    return sum(hmtx[cmap[ord(ch)]][0] for ch in text)


def render_png(font: TTFont, text: str, background: tuple, ink: tuple, out: Path) -> None:
    """渲染 1024×1024 不透明 PNG：纯色背景 + 居中字标。"""
    upem = font["head"].unitsPerEm
    cap_units = font["OS/2"].sCapHeight or int(upem * 0.714)
    # 字号：让 cap height 落在 CAP_HEIGHT_PX 上
    size = CAP_HEIGHT_PX * upem / cap_units

    pil_font = ImageFont.truetype(
        str(HELVETICA_NEUE), int(round(size)), index=HELVETICA_NEUE_MEDIUM_INDEX
    )

    image = Image.new("RGB", (CANVAS, CANVAS), background)
    draw = ImageDraw.Draw(image)

    # 测量实际墨迹包围盒，做**光学居中**（原资产是居中而非基线对齐）
    bbox = draw.textbbox((0, 0), text, font=pil_font)
    ink_w = bbox[2] - bbox[0]
    ink_h = bbox[3] - bbox[1]
    x = (CANVAS - ink_w) / 2 - bbox[0]
    y = (CANVAS - ink_h) / 2 - bbox[1]
    draw.text((x, y), text, font=pil_font, fill=ink)

    out.parent.mkdir(parents=True, exist_ok=True)
    image.save(out, "PNG", optimize=True)
    print(f"  {out.relative_to(REPO_ROOT)}  {image.size[0]}×{image.size[1]}  ink {ink_w}×{ink_h}")


def _fmt(value: float) -> str:
    text = f"{value:.5f}".rstrip("0").rstrip(".")
    return text if text else "0"


def render_pdf(font: TTFont, text: str, out: Path) -> None:
    """输出**纯矢量** PDF（文字转轮廓）。

    原资产就是这种形态：内容流里没有 BT/Tj/TJ 文本算子，只有 m/l/c/h/f 路径算子，
    且 `Contents.json` 声明 preserves-vector-representation。
    因此这里同样把字形转成路径，不嵌字体。
    """
    upem = font["head"].unitsPerEm
    cap_units = font["OS/2"].sCapHeight or int(upem * 0.714)
    glyph_set = font.getGlyphSet()
    cmap = font.getBestCmap()
    hmtx = font["hmtx"]

    # PDF 里的字号：让 cap height 对应到页面高度的合理比例。
    # 原资产 cap 约 22.13pt / 页高 36.20pt ≈ 0.611。
    cap_pt = IOS_PAGE_HEIGHT_PT * 0.6113
    scale = cap_pt / cap_units  # 字体单位 -> pt

    total_units = text_width_units(font, text)
    text_w_pt = total_units * scale
    x_cursor = (IOS_PAGE_WIDTH_PT - text_w_pt) / 2
    # 垂直居中：cap 中心对齐页高中心；基线 = 中心 - cap/2
    baseline = (IOS_PAGE_HEIGHT_PT - cap_pt) / 2

    segments: list[str] = []

    def emit_contour(points: list[tuple[float, float]], start: tuple[float, float]) -> None:
        """把已扁平化的轮廓点写成 PDF 路径。"""
        if not points:
            return
        segments.append(f"{_fmt(start[0])} {_fmt(start[1])} m")
        for px, py in points:
            segments.append(f"{_fmt(px)} {_fmt(py)} l")
        segments.append("h")

    for ch in text:
        glyph_name = cmap[ord(ch)]
        pen = RecordingPen()
        glyph_set[glyph_name].draw(pen)

        # 把字形轮廓（含二次贝塞尔）扁平化为折线，避免在 PDF 里处理 qCurveTo。
        current: list[tuple[float, float]] = []
        start_pt: tuple[float, float] | None = None
        last: tuple[float, float] | None = None

        def to_pt(pt) -> tuple[float, float]:
            gx, gy = pt
            return (x_cursor + gx * scale, baseline + gy * scale)

        for op, args in pen.value:
            if op == "moveTo":
                if current and start_pt is not None:
                    emit_contour(current, start_pt)
                current = []
                start_pt = to_pt(args[0])
                last = args[0]
            elif op == "lineTo":
                current.append(to_pt(args[0]))
                last = args[0]
            elif op == "qCurveTo":
                # TrueType 的 qCurveTo 可带多个点（隐式 on-curve 点）。
                # 手写展开容易出错（u 字形就会画歪），直接用 fontTools 的
                # decomposeQuadraticSegment 拆成原子二次贝塞尔段。
                pts = list(args)
                if pts[-1] is None:  # 全曲线闭合的特殊写法
                    pts = pts[:-1]
                    if last is not None:
                        pts.append(last)
                if len(pts) < 2:
                    continue
                if last is None:
                    last = pts[-1]
                cur = last
                for ctrl, end in decomposeQuadraticSegment(pts):
                    for step in range(1, 9):
                        t = step / 8
                        p0 = to_pt(cur)
                        p1 = to_pt(ctrl)
                        p2 = to_pt(end)
                        mt = 1 - t
                        current.append(
                            (
                                mt * mt * p0[0] + 2 * mt * t * p1[0] + t * t * p2[0],
                                mt * mt * p0[1] + 2 * mt * t * p1[1] + t * t * p2[1],
                            )
                        )
                    cur = end
                last = pts[-1]
            elif op == "curveTo":
                for c in args:
                    current.append(to_pt(c))
                last = args[-1]
            elif op == "closePath":
                if current and start_pt is not None:
                    emit_contour(current, start_pt)
                current = []
                start_pt = None
                last = None
        if current and start_pt is not None:
            emit_contour(current, start_pt)

        x_cursor += hmtx[glyph_name][0] * scale

    content = "q\n0 0 0 sc\n" + "\n".join(segments) + "\nf\nQ\n"
    content_bytes = content.encode("latin1")

    objects: list[bytes] = []
    objects.append(b"<< /Type /Catalog /Pages 2 0 R >>")
    objects.append(b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
    objects.append(
        (
            f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 {_fmt(IOS_PAGE_WIDTH_PT)} "
            f"{_fmt(IOS_PAGE_HEIGHT_PT)}] /Resources << >> /Contents 4 0 R >>"
        ).encode("latin1")
    )
    objects.append(
        b"<< /Length " + str(len(content_bytes)).encode() + b" >>\nstream\n"
        + content_bytes
        + b"endstream"
    )

    pdf = bytearray(b"%PDF-1.4\n")
    offsets: list[int] = []
    for index, body in enumerate(objects, start=1):
        offsets.append(len(pdf))
        pdf += f"{index} 0 obj\n".encode() + body + b"\nendobj\n"

    xref_pos = len(pdf)
    pdf += f"xref\n0 {len(objects) + 1}\n".encode()
    pdf += b"0000000000 65535 f \n"
    for offset in offsets:
        pdf += f"{offset:010d} 00000 n \n".encode()
    pdf += (
        f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref_pos}\n%%EOF\n"
    ).encode()

    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(bytes(pdf))
    print(f"  {out.relative_to(REPO_ROOT)}  vector PDF  {len(pdf)} bytes")


def main() -> int:
    parser = argparse.ArgumentParser(description="生成 cetus 品牌字标")
    parser.add_argument("--check", action="store_true", help="仅校验依赖与产物存在")
    args = parser.parse_args()

    font = load_font()
    print(f"字体：{font['name'].getDebugName(4)}  文本：{WORDMARK_TEXT!r}")

    if args.check:
        missing = [str(p.relative_to(REPO_ROOT)) for p in OUTPUTS.values() if not p.is_file()]
        if missing:
            print("缺少产物：" + ", ".join(missing), file=sys.stderr)
            return 1
        print("全部产物存在。")
        return 0

    print("Android：")
    render_png(font, WORDMARK_TEXT, PALETTE["android_light"]["background"],
               PALETTE["android_light"]["ink"], OUTPUTS["android_light"])
    render_png(font, WORDMARK_TEXT, PALETTE["android_night"]["background"],
               PALETTE["android_night"]["ink"], OUTPUTS["android_night"])

    print("iOS：")
    render_pdf(font, WORDMARK_TEXT, OUTPUTS["ios_light"])
    render_pdf(font, WORDMARK_TEXT, OUTPUTS["ios_dark"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
