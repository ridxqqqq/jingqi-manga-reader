# -*- coding: utf-8 -*-
# 生成 APK 启动图标：深色→橙红渐变底 + 白色“惊”字 + 漫画速度线，输出各密度
from PIL import Image, ImageDraw, ImageFont
import os, random

S = 512
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'apk', 'res')

img = Image.new('RGB', (S, S))
px = img.load()
c1 = (18, 20, 32)
c2 = (255, 96, 60)
for y in range(S):
    t = (y / (S - 1)) ** 2
    r = int(c1[0] + (c2[0] - c1[0]) * t)
    g = int(c1[1] + (c2[1] - c1[1]) * t)
    b = int(c1[2] + (c2[2] - c1[2]) * t)
    for x in range(S):
        px[x, y] = (r, g, b)

d = ImageDraw.Draw(img, 'RGBA')
random.seed(7)
for i in range(26):
    y0 = random.randint(-40, S)
    ln = random.randint(60, 200)
    x0 = random.randint(-60, S - 40)
    w = random.choice([2, 3, 4])
    d.line([(x0, y0), (x0 + ln, y0 - ln // 3)], fill=(255, 255, 255, 36), width=w)

d.ellipse([S-150, 60, S-130, 80], fill=(255, 255, 255, 200))
d.ellipse([S-190, 110, S-178, 122], fill=(255, 255, 255, 140))

mask = Image.new('L', (S, S), 0)
ImageDraw.Draw(mask).rounded_rectangle([0, 0, S, S], radius=112, fill=255)
base = Image.new('RGBA', (S, S), (0, 0, 0, 0))
base.paste(img, (0, 0), mask)

d2 = ImageDraw.Draw(base)
f_big = ImageFont.truetype(r'C:\Windows\Fonts\simhei.ttf', 252)
f_small = ImageFont.truetype(r'C:\Windows\Fonts\msyh.ttc', 64)

cx, cy = S // 2, S // 2 - 46
d2.text((cx + 8, cy + 10), '惊', font=f_big, fill=(0, 0, 0, 110), anchor='mm')
d2.text((cx, cy), '惊', font=f_big, fill=(255, 255, 255, 255), anchor='mm')

d2.text((cx + 4, S - 92 + 4), '惊奇先生', font=f_small, fill=(0, 0, 0, 90), anchor='mm')
d2.text((cx, S - 92), '惊奇先生', font=f_small, fill=(255, 255, 255, 235), anchor='mm')

densities = {'mipmap-mdpi': 48, 'mipmap-hdpi': 72, 'mipmap-xhdpi': 96,
             'mipmap-xxhdpi': 144, 'mipmap-xxxhdpi': 192}
for folder, size in densities.items():
    p = os.path.join(OUT, folder)
    os.makedirs(p, exist_ok=True)
    base.resize((size, size), Image.LANCZOS).save(os.path.join(p, 'ic_launcher.png'))
    print(folder, size, 'ok')
print('icons done')
