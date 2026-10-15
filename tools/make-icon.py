# -*- coding: utf-8 -*-
"""数字键盘图标生成器 —— 从零画出 ic_launcher 的五档 mipmap。

设计思路：把真实 UI 缩小成图标。
数字键盘的界面本身就是「顶部一条琥珀显示条 + 4x3 键阵」，
这正是图标需要的三个要素（有屏幕区、有强调色、九宫格不满铺）。

布局尺寸全部来自 KeyboardActivity.java 的源码常量，不是从截图取样的：
    KEY_W_DP=80  KEY_H_DP=58  KEY_GAP=5(四边 margin，故相邻间距 10dp)
    display 宽 = 80*3 + 5*4 = 260dp，22sp 琥珀字
    root padding 14/12

定稿参数（方案 2 / dark）：
    深底 #0D1017 + 白灰键 #D8DDE5 + 琥珀显示条 #FFC24D
    键盘占画布 94%、底板圆角 12.5%、键块 6px 微圆角、1 号键不做蓝色高亮

用法：
    python make-icon.py                 # 直接写进 ../res/mipmap-*/
    python make-icon.py --out ./preview # 只出预览，不动 res/
    python make-icon.py --preview       # 额外在临时目录存一张 512 大图

依赖：pillow, numpy
"""
import argparse
import os
import sys
import tempfile

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:
    sys.exit('缺少 pillow：pip install pillow')
try:
    import numpy as np
except ImportError:
    sys.exit('缺少 numpy：pip install numpy')

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

SS = 4                      # 超采样倍数
K = 512                     # 画布边长

# ---- 源码真值（dp） ----
PAD_X, PAD_Y = 14, 12
KEY_W, KEY_H, MARGIN = 80, 58, 5
GAP = MARGIN * 2                        # 相邻键间距 10dp
DISP_H = 35.5                           # 22sp 文字 + 5+5 padding
DISP_W = KEY_W * 3 + MARGIN * 4         # 260dp（= 键阵外缘总宽：3 键宽 + 2 个内部间隙）
CONTENT_W = DISP_W                      # 260dp；显示条从 grid_left（x0+MARGIN*s）起画，与键阵左右对齐
CONTENT_H = DISP_H + (KEY_H + GAP) * 4  # 35.5 + 272 = 307.5dp

# ---- 源码真值（颜色，半透明值已「实心化」） ----
C_PANEL = (13, 16, 23)          # 0xE60D1017 -> #0D1017
C_KEY   = (43, 46, 52)          # 0x1FFFFFFF 叠在 #0D1017 上 -> #2B2E34
C_FOCUS = (47, 95, 168)         # 0xFF2F5FA8
C_AMBER = (255, 194, 77)        # 0xFFFFC24D
# 源码里文字是纯白 0xFFFFFFFF，但图标上纯白太扎眼，降到约 -14% 明度的冷灰
C_TEXT  = (216, 221, 229)       # #D8DDE5

ROWS = [["1", "2", "3"], ["4", "5", "6"], ["7", "8", "9"], ["退", "0", "换台"]]

# 字体：找得到就用，找不到退回 PIL 内置位图字体（效果差但不会崩）
FONT_NUM_CANDIDATES = [
    r"C:\Windows\Fonts\arialbd.ttf",
    "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
]
FONT_CN_CANDIDATES = [
    r"C:\Windows\Fonts\msyhbd.ttc",
    "/System/Library/Fonts/PingFang.ttc",
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc",
]


def pick_font(cands):
    for p in cands:
        if os.path.exists(p):
            return p
    return None


FONT_NUM = pick_font(FONT_NUM_CANDIDATES)
FONT_CN = pick_font(FONT_CN_CANDIDATES)

_font_cache = {}


def font(path, size):
    key = (path, size)
    if key not in _font_cache:
        if path is None:
            _font_cache[key] = ImageFont.load_default()
        else:
            _font_cache[key] = ImageFont.truetype(path, size)
    return _font_cache[key]


def fit_font(path, target_h, text, hi=800):
    """二分找最大字号，使字面高度 <= target_h"""
    if path is None:
        return font(None, 10)
    lo = 4
    while lo < hi:
        mid = (lo + hi + 1) // 2
        bb = font(path, mid).getbbox(text)
        if bb[3] - bb[1] <= target_h:
            lo = mid
        else:
            hi = mid - 1
    return font(path, lo)


def content_box(fill_ratio):
    """内容区在 512 画布里的位置与缩放"""
    h = K * fill_ratio
    w = h * CONTENT_W / CONTENT_H
    x0 = (K - w) / 2
    y0 = (K - h) / 2
    return x0, y0, w, h, h / CONTENT_H      # 最后一个 = px per dp


def draw_keyboard(d, x0, y0, s, *, key_color, text_color,
                  focus_is_blue=True, radius=0.0):
    """在 SS 倍画布上画键盘主体（显示条 + 4 行键）。

    x0,y0,s 都按 512 空间传入；函数内部统一 x SS 换算到画布空间，
    避免 textbbox（画布空间）与布局量（512 空间）相减导致错位。
    radius: 键块圆角，单位是 512 空间的 px（0 = 直角）
    """
    def R(a, b, c, e):
        return [a * SS, b * SS, c * SS, e * SS]

    rr = round(radius * SS)

    # ---- 显示条 ----
    # ⚠️ 显示条左缘必须和键阵左缘（grid_left = x0 + MARGIN*s）对齐。
    #    显示条宽 = 3 个键宽 + 2 个内部间隙 = 键阵外缘总宽，两者本来同宽；
    #    但旧版从 x0 起画 —— 那是把最外侧的 margin 也算进内容区了，
    #    结果整条左移 5dp：左缘探出键阵 5dp、右缘缩进 5dp，
    #    就是用户看到的「上面的输入框和下面的按键没对齐」。
    dw, dh = DISP_W * s, DISP_H * s
    dx = x0 + MARGIN * s
    d.rounded_rectangle(R(dx, y0, dx + dw, y0 + dh), radius=rr, fill=key_color)
    # 中间那根琥珀色「—」（量自截图 12.8dp x 1.25dp，略加粗保证小尺寸可见）
    bar_w, bar_h = 13.5 * s, 2.4 * s
    bx = dx + (dw - bar_w) / 2
    by = y0 + (dh - bar_h) / 2
    d.rounded_rectangle(R(bx, by, bx + bar_w, by + bar_h),
                        radius=round(bar_h / 2 * SS), fill=C_AMBER)

    # ---- 4 行键 ----
    grid_left = x0 + MARGIN * s
    row_top = y0 + DISP_H * s + MARGIN * s
    step = (KEY_H + GAP) * s
    kw, kh = KEY_W * s, KEY_H * s
    for r, labels in enumerate(ROWS):
        ky = row_top + r * step
        for c, label in enumerate(labels):
            kx = grid_left + c * (KEY_W + GAP) * s
            is_focus = (r == 0 and c == 0)
            bg = C_FOCUS if (is_focus and focus_is_blue) else key_color
            fg = C_TEXT if (is_focus and focus_is_blue) else text_color
            d.rounded_rectangle(R(kx, ky, kx + kw, ky + kh), radius=rr, fill=bg)

            # 文字：按「居中」定位，最稳
            # 真实截图里数字高 = 键高 x0.306；这里放到 0.36，换 160px 下的可辨度
            ratio = 0.36 if len(label) == 1 else 0.30
            target = kh * ratio * SS
            fpath = FONT_NUM if label.isascii() else FONT_CN
            f = fit_font(fpath, target, label)
            bb = d.textbbox((0, 0), label, font=f)
            tw, th = bb[2] - bb[0], bb[3] - bb[1]
            cx = (kx + kw / 2) * SS
            cy = (ky + kh / 2) * SS
            d.text((cx - tw / 2 - bb[0], cy - th / 2 - bb[1]),
                   label, font=f, fill=fg)


def rounded_square(im, d, color, radius_ratio=0.216):
    r = int(K * radius_ratio)
    d.rounded_rectangle([0, 0, K * SS - 1, K * SS - 1],
                        radius=r * SS, fill=color)


def build(style='dark'):
    """style: 'clear' | 'dark' | 'light' | 'blue'"""
    im = Image.new('RGBA', (K * SS, K * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)

    # 完整面板尺寸（含 root padding，不含 footer）：298 x 331.5 dp
    BODY_W = PAD_X * 2 + DISP_W
    BODY_H = PAD_Y * 2 + DISP_H + (KEY_H + GAP) * 4

    if style == 'clear':
        x0, y0, w, h, s = content_box(0.92)
        draw_keyboard(d, x0, y0, s, key_color=C_KEY, text_color=C_TEXT)

    elif style == 'dark':
        # 定稿方案 2：
        #   · 键盘整体更大（0.88 -> 0.94）
        #   · 底板四个角改小圆角（0.216 -> 0.125）
        #   · 键块加 6px 微圆角
        #   · 1 号键不再高亮蓝，跟其他键一样
        rounded_square(im, d, C_PANEL + (255,), radius_ratio=0.125)
        x0, y0, w, h, s = content_box(0.94)
        draw_keyboard(d, x0, y0, s, key_color=C_KEY, text_color=C_TEXT,
                      focus_is_blue=False, radius=6.0)

    elif style == 'light':
        # 计算器图标的主流做法（iOS / LineageOS）：浅底 + 深色机身
        rounded_square(im, d, (233, 237, 242))
        ph = K * 0.84
        pw = ph * BODY_W / BODY_H
        px0, py0 = (K - pw) / 2, (K - ph) / 2
        d.rounded_rectangle([px0 * SS, py0 * SS, (px0 + pw) * SS, (py0 + ph) * SS],
                            radius=int(pw * 0.10 * SS), fill=C_PANEL + (255,))
        s2 = ph / BODY_H
        draw_keyboard(d, px0 + PAD_X * s2, py0 + PAD_Y * s2, s2,
                      key_color=C_KEY, text_color=C_TEXT)

    elif style == 'blue':
        # 底色用 #2F5FA8 的暗一档，否则 1 号高亮键（也是 #2F5FA8）会隐形
        rounded_square(im, d, (28, 61, 115))
        x0, y0, w, h, s = content_box(0.84)
        draw_keyboard(d, x0, y0, s,
                      key_color=(255, 255, 255), text_color=C_PANEL)

    return im.resize((K, K), Image.LANCZOS)


def corner_check(img):
    al = np.asarray(img)[:, :, 3]
    c = (int(al[0, 0]), int(al[0, -1]), int(al[-1, 0]), int(al[-1, -1]))
    ok = 'OK' if max(c) == 0 else '!! 四角不透明，上电视会糊成黑方块'
    print(f'  四角 alpha={c}  {ok}')
    return max(c) == 0


# ★ 尺寸用 108dp 规格（跟 tcl-keymapper 一致），不是 Android 标准的 48dp：
#   电视 density=1.5，108dp x 1.5 = 162 ≈ 160px，正好是桌面图标槽位的实测尺寸。
#   用标准 48dp 的话图标只有 72px，上电视会明显比改键器小一圈。
SIZES = [('mdpi', 108), ('hdpi', 160), ('xhdpi', 216),
         ('xxhdpi', 324), ('xxxhdpi', 432)]


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--out', default=os.path.join(ROOT, 'res'),
                    help='输出目录，默认写进项目的 res/mipmap-*')
    ap.add_argument('--style', default='dark',
                    choices=['dark', 'clear', 'light', 'blue'])
    ap.add_argument('--preview', action='store_true',
                    help='额外存一张 512 大图到临时目录')
    args = ap.parse_args()

    print(f'样式 {args.style}  ·  字体 {FONT_NUM or "内置"} / {FONT_CN or "内置"}')
    im = build(args.style)
    if not corner_check(im):
        sys.exit('图标四角必须透明，否则电视桌面会显示成黑方块')

    if args.preview:
        p = os.path.join(tempfile.gettempdir(), f'ic_launcher_{args.style}_512.png')
        im.save(p)
        print(f'  预览 -> {p}')

    for bucket, side in SIZES:
        d = os.path.join(args.out, f'mipmap-{bucket}')
        os.makedirs(d, exist_ok=True)
        out = im.resize((side, side), Image.LANCZOS)
        p = os.path.join(d, 'ic_launcher.png')
        out.save(p)
        al = np.asarray(out)[:, :, 3]
        corners = (int(al[0, 0]), int(al[0, -1]), int(al[-1, 0]), int(al[-1, -1]))
        flag = 'OK' if max(corners) == 0 else '!! 四角不透明'
        print(f'  {bucket:8s} {side:3d}px  {os.path.getsize(p):6d}B  '
              f'corners={corners}  {flag}')

    print('\n完成。重新打包：./build.sh')


if __name__ == '__main__':
    main()
