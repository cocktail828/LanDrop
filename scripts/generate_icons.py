"""Regenerate the original paper-plane icons; run with Pillow inside a container."""
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'app/src/main/res'
BLUE = '#1976D2'
PLANE = [(17, 42), (82, 18), (66, 82), (49, 60), (34, 72), (35, 52)]
FOLD = [(35, 52), (82, 18), (49, 60), (34, 72)]
TRAILS = [[(16, 66), (27, 61)], [(16, 78), (26, 72)]]

def path(points):
    return 'M' + ' L'.join(f'{x},{y}' for x, y in points) + ' Z'

def vector(width, viewport, body):
    return f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{width}dp" android:height="{width}dp"
    android:viewportWidth="{viewport}" android:viewportHeight="{viewport}">
{body}
</vector>
'''

def plane_vector(fold=True):
    body = f'        <path android:fillColor="#FFFFFF" android:pathData="{path(PLANE)}" />\n'
    if fold:
        body += f'        <path android:fillColor="#AEEBFF" android:pathData="{path(FOLD)}" />\n'
    for trail in TRAILS:
        body += f'        <path android:fillColor="@android:color/transparent" android:strokeColor="#FFFFFF" android:strokeWidth="3" android:strokeLineCap="round" android:pathData="M{trail[0][0]},{trail[0][1]} L{trail[1][0]},{trail[1][1]}" />\n'
    return body

# Keep adaptive artwork within the safe area for circular and squircle masks.
(RES / 'drawable/ic_launcher_adaptive_foreground.xml').write_text(vector(108, 108,
    '    <group android:scaleX="0.70" android:scaleY="0.70" android:translateX="19" android:translateY="19">\n' + plane_vector() + '    </group>'))
(RES / 'drawable/ic_launcher_background.xml').write_text(vector(108, 108,
    f'    <path android:fillColor="{BLUE}" android:pathData="M0,0 H108 V108 H0 Z" />'))
(RES / 'drawable-v24/ic_notification.xml').write_text(vector(24, 100,
    '    <group android:scaleX="1.20" android:scaleY="1.20" android:translateX="-9" android:translateY="-9">\n' + plane_vector(False) + '    </group>'))
# Use the same mark for the Android TV launcher banner.
(RES / 'drawable/tv_banner.xml').write_text('''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="320dp" android:height="180dp" android:viewportWidth="320" android:viewportHeight="180">
''' + f'    <path android:fillColor="{BLUE}" android:pathData="M0,0 H320 V180 H0 Z" />\n' +
    '    <group android:translateX="110" android:translateY="40">\n' + plane_vector() + '    </group>\n</vector>\n')

# Density-specific PNGs remain necessary for older launcher and notification implementations.
def render(size, notification=False, round_icon=False):
    scale = size * 4 / 100
    image = Image.new('RGBA', (size * 4, size * 4))
    draw = ImageDraw.Draw(image)
    def points(shape):
        if notification:
            shape = [(x * 1.2 - 9, y * 1.2 - 9) for x, y in shape]
        return [(round(x * scale), round(y * scale)) for x, y in shape]
    if not notification:
        if round_icon:
            draw.ellipse([0, 0, size * 4 - 1, size * 4 - 1], fill=BLUE)
        else:
            draw.rounded_rectangle([0, 0, size * 4 - 1, size * 4 - 1], radius=round(size * 0.23 * 4), fill=BLUE)
    draw.polygon(points(PLANE), fill='white')
    if not notification:
        draw.polygon(points(FOLD), fill='#AEEBFF')
    for trail in TRAILS:
        draw.line(points(trail), fill='white', width=max(1, round(3 * scale)))
    return image.resize((size, size), Image.Resampling.LANCZOS)

for density, launcher_size, notification_size in [('mdpi',48,24),('hdpi',72,36),('xhdpi',96,48),('xxhdpi',144,72),('xxxhdpi',192,96)]:
    for rounded in [False, True]:
        render(launcher_size, round_icon=rounded).save(RES / f'mipmap-{density}/ic_launcher{"_round" if rounded else ""}.png')
    render(notification_size, notification=True).save(RES / f'drawable-{density}/ic_notification.png')
old_foreground = RES / 'drawable-xxxhdpi/ic_launcher_adaptive_foreground.png'
old_foreground.unlink(missing_ok=True)
render(512).save(ROOT / 'fastlane/metadata/android/en-US/images/icon.png')
# Review artifact: launcher icon plus the monochrome glyph on light and dark surfaces.
preview = Image.new('RGBA', (760, 320), '#F5F7FA')
preview.alpha_composite(render(256), (32, 32))
light_glyph = Image.new('RGBA', (128, 128), '#1B1F23')
light_glyph.putalpha(render(128, notification=True).getchannel('A'))
preview.alpha_composite(light_glyph, (350, 96))
ImageDraw.Draw(preview).rectangle((540, 0, 759, 319), fill='#111111')
preview.alpha_composite(render(128, notification=True), (586, 96))
preview_path = ROOT / 'build/validation/icon-preview.png'
preview_path.parent.mkdir(parents=True, exist_ok=True)
preview.convert('RGB').save(preview_path)
print('Generated launcher, notification and TV paper-plane icons.')
