#!/usr/bin/env python3
"""Baut den README-Banner (docs/assets/readme-banner-{de,en}.svg).

Text wird in Pfade umgewandelt: GitHub bettet SVGs per <img> ein, dort gelten
weder Webfonts noch die Schriften des Repos, sondern die des Betrachters.

Aufruf: python3 scripts/readme-banner.py docs/assets [schriftverzeichnis]
Schrift: URW Gothic (urw-base35, unter Linux meist unter /usr/share/fonts/opentype/urw-base35).
"""
import math
import random
import sys
from pathlib import Path

from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

W, H = 1280, 420

SAND = "#d4a060"
FAWN = "#a87840"
CREAM = "#f3e6d0"
MUTED = "#c9ab84"
AMBER = "#e8a54c"
AI = "#b58ee8"  # --color-ai aus dem dunklen Standard-Theme

TEXT = {
    "de": {
        "tagline": "Feste Schritte und KI in einem Ablauf.",
        "body": [
            "Ein lokaler Regelkreis findet Befunde, legt TODOs an,",
            "stößt Fixes an und prüft nach – mit Claude, Codex oder Pi.",
        ],
        "legend_fixed": "fester Schritt",
        "legend_ai": "KI-Schritt",
        "nodes": ["Befund", "TODO", "Fix", "Prüfung"],
    },
    "en": {
        "tagline": "Fixed steps and AI in one flow.",
        "body": [
            "A local control loop finds issues, files TODOs,",
            "triggers fixes and checks again – with Claude, Codex or Pi.",
        ],
        "legend_fixed": "fixed step",
        "legend_ai": "AI step",
        "nodes": ["Finding", "TODO", "Fix", "Check"],
    },
}


class Face:
    def __init__(self, path):
        self.font = TTFont(path)
        self.upm = self.font["head"].unitsPerEm
        self.cmap = self.font.getBestCmap()
        self.glyphs = self.font.getGlyphSet()
        self.hmtx = self.font["hmtx"]
        self.kern = self._pair_kerning()

    def _pair_kerning(self):
        pairs = {}
        if "GPOS" not in self.font:
            return pairs
        for lookup in self.font["GPOS"].table.LookupList.Lookup:
            if lookup.LookupType != 2:
                continue
            for sub in lookup.SubTable:
                if sub.Format != 1:
                    continue
                for first, pset in zip(sub.Coverage.glyphs, sub.PairSet):
                    for rec in pset.PairValueRecord:
                        xa = getattr(rec.Value1, "XAdvance", 0) if rec.Value1 else 0
                        if xa:
                            pairs[(first, rec.SecondGlyph)] = xa
        return pairs

    def run(self, text, size, tracking=0.0):
        """Liefert (Pfaddaten, Breite) fuer eine Textzeile ab Ursprung 0/0 (Grundlinie)."""
        scale = size / self.upm
        pen = SVGPathPen(self.glyphs, ntos=lambda v: f"{v:.1f}".rstrip("0").rstrip("."))
        x = 0.0
        prev = None
        for ch in text:
            name = self.cmap.get(ord(ch))
            if name is None:
                raise SystemExit(f"Glyphe fehlt: {ch!r}")
            if prev is not None:
                x += self.kern.get((prev, name), 0) * scale
            self.glyphs[name].draw(TransformPen(pen, (scale, 0, 0, -scale, x, 0)))
            x += self.hmtx[name][0] * scale + tracking
            prev = name
        return pen.getCommands(), x - (tracking if text else 0)


def text(face, s, size, x, y, fill, anchor="start", tracking=0.0, extra=""):
    d, width = face.run(s, size, tracking)
    if anchor == "middle":
        x -= width / 2
    elif anchor == "end":
        x -= width
    return f'<path transform="translate({x:.1f} {y:.1f})" fill="{fill}"{extra} d="{d}"/>', width


MEERKAT = """
    <ellipse cx="63" cy="62" rx="24" ry="32" fill="#a87840"/>
    <ellipse cx="193" cy="62" rx="24" ry="32" fill="#a87840"/>
    <path d="M75 219C65 170 70 125 92 105c16-14 56-14 72 0 22 20 27 65 17 114Z" fill="#d4a060"/>
    <ellipse cx="128" cy="178" rx="25" ry="46" fill="#ecd4a8"/>
    <ellipse cx="128" cy="105" rx="72" ry="63" fill="#d4a060"/>
    <path d="M77 91c10-29 28-36 51-36s41 7 51 36c-10 28-28 40-51 40S87 119 77 91Z" fill="#8b6030"/>
    <g fill="none" stroke="#2a1a0d" stroke-width="8">
      <circle cx="105" cy="93" r="21"/><circle cx="151" cy="93" r="21"/><path d="M126 91h4"/>
    </g>
    <g fill="#2a1a0d"><ellipse cx="105" cy="92" rx="5" ry="6.5"/><ellipse cx="151" cy="92" rx="5" ry="6.5"/><ellipse cx="128" cy="118" rx="9" ry="6"/></g>
    <g fill="#fff"><circle cx="103" cy="89" r="2"/><circle cx="149" cy="89" r="2"/></g>
    <g fill="none" stroke="#a87840" stroke-width="13"><path d="m91 158 20 27"/><path d="m165 158-20 27"/></g>
    <g fill="#d4a060"><ellipse cx="114" cy="185" rx="11" ry="7"/><ellipse cx="142" cy="185" rx="11" ry="7"/></g>"""


def build(lang, book, demi):
    t = TEXT[lang]
    out = []
    add = out.append

    # Szene rechts: Waechter-Erdmaennchen, umkreist vom Regelkreis
    mx, scale = 1010, 1.12
    tx, ty = mx - 128 * scale, 84 - 30 * scale
    ocx, ocy, orx, ory = mx, 230, 214, 56
    loop_d = (f"M{ocx - orx} {ocy}"
              f"A{orx} {ory} 0 1 1 {ocx + orx} {ocy}"
              f"A{orx} {ory} 0 1 1 {ocx - orx} {ocy}Z")

    def on_orbit(deg):
        a = math.radians(deg)
        return ocx + orx * math.cos(a), ocy + ory * math.sin(a)

    add(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}" role="img">')
    add("<defs>")
    add('<linearGradient id="sky" x1="0" y1="0" x2="0" y2="1">'
        '<stop offset="0" stop-color="#110d0a"/><stop offset=".62" stop-color="#1f160f"/>'
        '<stop offset="1" stop-color="#3a2415"/></linearGradient>')
    add(f'<radialGradient id="dusk" cx="{mx}" cy="{H + 40}" r="520" gradientUnits="userSpaceOnUse">'
        f'<stop offset="0" stop-color="{AMBER}" stop-opacity=".42"/>'
        f'<stop offset=".55" stop-color="{AMBER}" stop-opacity=".08"/>'
        f'<stop offset="1" stop-color="{AMBER}" stop-opacity="0"/></radialGradient>')
    add('<linearGradient id="mound" x1="0" y1="0" x2="0" y2="1">'
        '<stop offset="0" stop-color="#7a5430"/><stop offset=".5" stop-color="#4a3019"/><stop offset="1" stop-color="#24170d"/></linearGradient>')
    add('<filter id="glow" x="-3" y="-3" width="7" height="7"><feGaussianBlur stdDeviation="4"/></filter>')
    add(f'<clipPath id="card"><rect width="{W}" height="{H}" rx="28"/></clipPath>')
    add(f'<clipPath id="front"><rect x="0" y="{ocy}" width="{W}" height="{H}"/></clipPath>')
    add(f'<path id="loop" d="{loop_d}"/>')
    add("</defs>")
    add("<style>@media (prefers-reduced-motion: reduce){.runner{display:none}}</style>")

    add('<g clip-path="url(#card)">')
    add(f'<rect width="{W}" height="{H}" fill="url(#sky)"/>')
    add(f'<rect width="{W}" height="{H}" fill="url(#dusk)"/>')

    rnd = random.Random(7)
    stars = []
    for _ in range(46):
        x, y = rnd.uniform(700, 1265), rnd.uniform(14, 190)
        stars.append(f'<circle cx="{x:.0f}" cy="{y:.0f}" r="{rnd.uniform(.6, 1.5):.1f}" '
                     f'opacity="{rnd.uniform(.25, .75):.2f}"/>')
    add(f'<g fill="{CREAM}">{"".join(stars)}</g>')

    # Hintere Haelfte des Regelkreises (hinter dem Erdmaennchen)
    add(f'<use href="#loop" fill="none" stroke="{AMBER}" stroke-opacity=".32" stroke-width="2" '
        'stroke-dasharray="2 7" stroke-linecap="round"/>')

    def runner():
        return (f'<g class="runner"><circle r="11" fill="{AMBER}" opacity=".55" filter="url(#glow)"/>'
                f'<circle r="4.5" fill="#fff3dc"/>'
                '<animateMotion dur="9s" repeatCount="indefinite" rotate="auto">'
                '<mpath href="#loop"/></animateMotion></g>')

    add(runner())

    kinds = ["ai", "fixed", "ai", "fixed"]  # Befund, TODO, Fix, Pruefung
    angles = [205, 335, 25, 155]  # im Uhrzeigersinn: hinten links, hinten rechts, vorn rechts, vorn links

    def node(i):
        x, y = on_orbit(angles[i])
        parts = []
        if kinds[i] == "ai":
            parts.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="15" fill="{AI}" opacity=".35" filter="url(#glow)"/>')
            parts.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="9" fill="{AI}"/>')
        else:
            parts.append(f'<rect x="{x - 8:.1f}" y="{y - 8:.1f}" width="16" height="16" rx="2.5" '
                         f'fill="#241910" stroke="{CREAM}" stroke-width="2.5"/>')
        back = y < ocy
        ly = y - 20 if back else y + 34
        label, _ = text(book, t["nodes"][i], 18, x, ly, CREAM, anchor="middle", tracking=.3)
        parts.append(label)
        return "".join(parts)

    add(node(0))
    add(node(1))

    add(f'<g transform="translate({tx:.1f} {ty:.1f}) scale({scale})" '
        f'stroke-linecap="round" stroke-linejoin="round">{MEERKAT}</g>')

    # Huegel, verdeckt den Rumpf; dahinter eine flache Duene fuer Tiefe
    add(f'<path d="M520 {H}C640 368 760 352 900 350C1040 348 1160 336 {W} 318V{H}Z" fill="#2a1a0f" opacity=".8"/>')
    add(f'<path d="M700 {H}C790 356 880 300 1010 296C1130 292 1200 330 {W} 356V{H}Z" fill="url(#mound)"/>')

    # Vordere Haelfte des Regelkreises (vor dem Erdmaennchen)
    add(f'<g clip-path="url(#front)"><use href="#loop" fill="none" stroke="{AMBER}" stroke-opacity=".75" '
        f'stroke-width="2.5"/>{runner()}</g>')
    add(node(2))
    add(node(3))
    # Pfeilspitzen zeigen die Laufrichtung (im Uhrzeigersinn)
    for deg in (0, 90, 180):
        x, y = on_orbit(deg)
        a = math.radians(deg)
        rot = math.degrees(math.atan2(ory * math.cos(a), -orx * math.sin(a)))
        add(f'<path transform="translate({x:.1f} {y:.1f}) rotate({rot:.1f})" d="M-5-6 2 0-5 6" fill="none" '
            f'stroke="{AMBER}" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/>')

    # Text links
    x0 = 72
    title, _ = text(demi, "MySuricates", 78, x0, 146, CREAM, tracking=-1.0)
    add(title)
    add(f'<rect x="{x0 + 2}" y="170" width="64" height="5" rx="2.5" fill="{AMBER}"/>')
    tag, _ = text(demi, t["tagline"], 31, x0, 226, "#f0c890", tracking=-.2)
    add(tag)
    for i, line in enumerate(t["body"]):
        p, _ = text(book, line, 21, x0, 270 + i * 31, MUTED, tracking=.1)
        add(p)

    ly = 364
    add(f'<rect x="{x0}" y="{ly - 13}" width="14" height="14" rx="2.5" fill="#241910" '
        f'stroke="{CREAM}" stroke-width="2.2"/>')
    lf, wf = text(book, t["legend_fixed"], 18, x0 + 26, ly, CREAM, tracking=.3)
    add(lf)
    cx = x0 + 26 + wf + 40
    add(f'<circle cx="{cx + 7}" cy="{ly - 6}" r="7.5" fill="{AI}"/>')
    la, _ = text(book, t["legend_ai"], 18, cx + 26, ly, CREAM, tracking=.3)
    add(la)

    add("</g>")
    add(f'<rect x=".75" y=".75" width="{W - 1.5}" height="{H - 1.5}" rx="27.5" fill="none" '
        f'stroke="{AMBER}" stroke-opacity=".28" stroke-width="1.5"/>')
    add("</svg>")
    return "\n".join(out) + "\n"


def main():
    out_dir = Path(sys.argv[1])
    font_dir = Path(sys.argv[2] if len(sys.argv) > 2 else "/usr/share/fonts/opentype/urw-base35")
    book = Face(font_dir / "URWGothic-Book.otf")
    demi = Face(font_dir / "URWGothic-Demi.otf")
    out_dir.mkdir(parents=True, exist_ok=True)
    for lang in TEXT:
        target = out_dir / f"readme-banner-{lang}.svg"
        target.write_text(build(lang, book, demi), encoding="utf-8")
        print(f"{target} ({target.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    main()
