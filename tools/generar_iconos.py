"""
Genera el juego completo de iconos de PlayMix TV a partir de logo_PM.png.

Por qué hacía falta: el ic_launcher_foreground.png que había medía 192 px en su
densidad más alta. Ese es el tamaño de un icono LEGACY. Un icono adaptativo se
dibuja sobre un lienzo de 108dp, o sea 432 px en xxxhdpi: Android estaba
estirando 192 px hasta 432 px, y de ahí venía el aspecto pixelado.
"""
from PIL import Image, ImageDraw, ImageFilter
import math, os

RES = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'res')
SRC = os.path.join(os.path.dirname(__file__), 'logo_PM.png')

# ── Paleta del proyecto ────────────────────────────────────────────────────
VIOLETA_ALTO = (46, 21, 80)     # #2E1550
VIOLETA_MED  = (23, 12, 43)     # #170C2B
VIOLETA_BAJO = (11, 7, 20)      # #0B0714
ROSA         = (255, 60, 172)   # #FF3CAC
NARANJA      = (255, 106, 0)    # #FF6A00

logo = Image.open(SRC).convert('RGBA')
logo = logo.crop(logo.split()[3].getbbox())

# ── Radio real de los píxeles SÓLIDOS (el halo tenue puede recortarse) ─────
a = logo.split()[3].load()
W, H = logo.size
radio_solido = 0
for y in range(0, H, 2):
    for x in range(0, W, 2):
        if a[x, y] > 128:
            r = math.hypot(x - (W-1)/2, y - (H-1)/2)
            radio_solido = max(radio_solido, r)
RATIO = radio_solido / H          # radio / altura del logo
print('radio sólido / altura = %.4f' % RATIO)

# El lienzo adaptativo es 108dp; la zona segura para CUALQUIER máscara es un
# círculo de radio 34dp. De ahí sale la altura máxima del logo.
ALTO_LOGO_DP = 34.0 / RATIO
print('altura del logo en el icono adaptativo: %.1f dp de 108dp' % ALTO_LOGO_DP)


def degradado(size, colores, diagonal=True):
    """Degradado lineal; en diagonal si se pide."""
    w, h = size
    n = len(colores)
    base = Image.new('RGB', (256, 1))
    p = base.load()
    for i in range(256):
        t = i / 255 * (n - 1)
        k = min(int(t), n - 2)
        f = t - k
        p[i, 0] = tuple(int(colores[k][c] + (colores[k+1][c] - colores[k][c]) * f) for c in range(3))
    grande = base.resize((max(w, h) * 2, max(w, h) * 2))
    if diagonal:
        grande = grande.rotate(45, expand=True, resample=Image.BICUBIC)
        cw, ch = grande.size
        grande = grande.crop(((cw - w)//2, (ch - h)//2, (cw - w)//2 + w, (ch - h)//2 + h))
    else:
        grande = grande.resize((w, h))
    return grande.convert('RGBA')


def resplandor(size, centro, radio, color, fuerza=0.55):
    """Halo suave del color de marca, para dar profundidad detrás del logo."""
    w, h = size
    capa = Image.new('L', (w, h), 0)
    d = ImageDraw.Draw(capa)
    cx, cy = centro
    d.ellipse([cx - radio, cy - radio, cx + radio, cy + radio], fill=int(255 * fuerza))
    capa = capa.filter(ImageFilter.GaussianBlur(radio * 0.55))
    tinta = Image.new('RGBA', (w, h), color + (0,))
    tinta.putalpha(capa)
    return tinta


def pegar_logo(lienzo, alto_px, centro=None, desplaz_y=0):
    w, h = lienzo.size
    esc = alto_px / logo.height
    lg = logo.resize((max(1, round(logo.width * esc)), max(1, round(alto_px))), Image.LANCZOS)
    cx, cy = centro or (w // 2, h // 2)
    lienzo.alpha_composite(lg, (cx - lg.width // 2, cy - lg.height // 2 + desplaz_y))
    return lienzo


# ═══════════════════════════════════════════════════════════════════════════
# 1. PRIMER PLANO DEL ICONO ADAPTATIVO  (lienzo de 108dp)
# ═══════════════════════════════════════════════════════════════════════════
DENS = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}

for dens, f in DENS.items():
    lado = round(108 * f)
    lienzo = Image.new('RGBA', (lado, lado), (0, 0, 0, 0))
    pegar_logo(lienzo, round(ALTO_LOGO_DP * f))
    ruta = f'{RES}/mipmap-{dens}/ic_launcher_foreground.png'
    lienzo.save(ruta)
    print('foreground %-8s %dx%d' % (dens, lado, lado))

# ═══════════════════════════════════════════════════════════════════════════
# 2. ICONOS LEGACY  (Android 7 y anteriores: sin máscara adaptativa)
# ═══════════════════════════════════════════════════════════════════════════
for dens, f in DENS.items():
    lado = round(48 * f)
    fondo = degradado((lado, lado), [VIOLETA_ALTO, VIOLETA_MED, VIOLETA_BAJO])
    fondo.alpha_composite(resplandor((lado, lado), (lado//2, lado//2), lado*0.34, ROSA, 0.30))
    pegar_logo(fondo, round(lado * 0.70))

    # Cuadrado con esquinas redondeadas
    mascara = Image.new('L', (lado, lado), 0)
    ImageDraw.Draw(mascara).rounded_rectangle([0, 0, lado-1, lado-1], radius=int(lado*0.22), fill=255)
    cuadrado = fondo.copy(); cuadrado.putalpha(mascara)
    cuadrado.save(f'{RES}/mipmap-{dens}/ic_launcher.png')

    # Círculo
    mascara = Image.new('L', (lado, lado), 0)
    ImageDraw.Draw(mascara).ellipse([0, 0, lado-1, lado-1], fill=255)
    circulo = fondo.copy(); circulo.putalpha(mascara)
    circulo.save(f'{RES}/mipmap-{dens}/ic_launcher_round.png')
    print('legacy     %-8s %dx%d' % (dens, lado, lado))

# ═══════════════════════════════════════════════════════════════════════════
# 3. BANNER DE ANDROID TV  (320x180 en xhdpi, según la guía de Google)
# ═══════════════════════════════════════════════════════════════════════════
for dens, (bw, bh) in {'xhdpi': (320, 180), 'xxhdpi': (480, 270)}.items():
    os.makedirs(f'{RES}/drawable-{dens}', exist_ok=True)
    b = degradado((bw, bh), [VIOLETA_ALTO, VIOLETA_MED, VIOLETA_BAJO])
    # Dos halos cruzados: naranja arriba a la izquierda, rosa abajo a la derecha
    b.alpha_composite(resplandor((bw, bh), (bw*0.30, bh*0.22), bh*0.55, NARANJA, 0.28))
    b.alpha_composite(resplandor((bw, bh), (bw*0.68, bh*0.82), bh*0.55, ROSA, 0.26))
    # Halo propio del logo, para despegarlo del fondo
    b.alpha_composite(resplandor((bw, bh), (bw*0.5, bh*0.5), bh*0.40, ROSA, 0.34))
    pegar_logo(b, round(bh * 0.80))
    b.convert('RGB').save(f'{RES}/drawable-{dens}/banner.png')
    print('banner     %-8s %dx%d' % (dens, bw, bh))

print('\nlisto')
