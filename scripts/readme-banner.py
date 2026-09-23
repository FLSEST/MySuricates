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

W, H = 1280, 530

SAND = "#d4a060"
FAWN = "#a87840"
CREAM = "#f3e6d0"
MUTED = "#c9ab84"
AMBER = "#e8a54c"
AI = "#b58ee8"  # --color-ai aus dem dunklen Standard-Theme
HUMAN = "#56c2b0"  # --color-human

# Wortlaut des Autors (2026-09-23); Titelzeile erklaert das Bild, die vier Zeilen sind seine Saetze.
TEXT = {
    "de": {
        "tagline": ["Baue deine eigenen Workflows mit deinen Erdmännchen",
                    "und lass sie dir helfen, jeden Schritt im Blick zu behalten."],
        "body": [
            "Deterministisch, wo es geht.",
            "Jeder Anbieter: Wechsle ihn und behalte deinen Ablauf.",
            "Behalte im Blick, was du getan hast und was du tust.",
            "Setze deine Erdmännchen ein, so wie du sie brauchst.",
        ],
        "things": [["Workflows", "Ketten", "Skills", "Vorlagen", "Zeitpläne"],
                   ["TODOs", "Integrationen", "Webhooks", "Human-in-the-Loop", "Assistent"],
                   ["BPMN", "Versionen", "Gates", "Memory", "Dashboard", "Audit"],
                   ["Hoch konfigurierbar", "Komplett individualisierbar", "Features abschaltbar", "…"]],
    },
    "en": {
        "tagline": ["Build your own workflows with your suricates",
                    "and let them help you watch every step."],
        "body": [
            "Deterministic if possible.",
            "Any provider: change it and keep your flow.",
            "Keep track of what you did and what you do.",
            "Put your suricates to work the way you need them.",
        ],
        "things": [["Workflows", "Chains", "Skills", "Templates", "Schedules"],
                   ["TODOs", "Integrations", "Webhooks", "Human-in-the-loop", "Assistant"],
                   ["BPMN", "Versions", "Gates", "Memory", "Dashboard", "Audit"],
                   ["Highly configurable", "Fully customizable", "Features can be switched off", "…"]],
    },
}

# Linien-Icons (16x16, Ursprung Mitte) fuer die Reihe unten, gleiche Reihenfolge wie "things".
ICONS = [[
    # Workflows: drei Schritte in Reihe
    '<rect x="-8" y="-3" width="5" height="6" rx="1"/><rect x="-2.5" y="-3" width="5" height="6" rx="1"/>'
    '<rect x="3" y="-3" width="5" height="6" rx="1"/>',
    # Ketten: zwei Glieder
    '<rect x="-9" y="-4" width="10" height="8" rx="4"/><rect x="-1" y="-4" width="10" height="8" rx="4"/>',
    # Skills: Stern
    '<path d="M0-8 2.3-2.6 8-2.2 3.6 1.6 5 7.4 0 4.4-5 7.4-3.6 1.6-8-2.2-2.3-2.6Z"/>',
    # Vorlagen: gestapelte Blaetter
    '<rect x="-7" y="-4" width="12" height="11" rx="1.5"/><path d="M-4-7h12v11"/>',
    # Zeitplaene: Uhr
    '<circle r="7.5"/><path d="M0-4V0.5L3 2.5"/>',
], [
    # TODOs: Kasten mit Haken
    '<rect x="-7" y="-7" width="14" height="14" rx="2"/><path d="M-3.5 0.5-1 3 4-3"/>',
    # Integrationen: Stecker
    '<path d="M-3-8V-4M3-8V-4"/><rect x="-6" y="-4" width="12" height="7" rx="2"/><path d="M0 3V8"/>',
    # Webhooks: Blitz
    '<path d="M1.5-8-6 1.5h5L-1.5 8 6-1.5H1Z"/>',
    # Human-in-the-Loop: Person im Kreispfeil
    '<circle cy="-2" r="2.6"/><path d="M-4.5 5c0-3 2-4.5 4.5-4.5S4.5 2 4.5 5"/>'
    '<path d="M6.5-5.5A8.5 8.5 0 1 0 8.5 1"/><path d="M8-8.5V-5H4.5"/>',
    # Assistent: Sprechblase
    '<path d="M-7-6h14a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H-2l-4 3.5V4h-1a1 1 0 0 1-1-1v-8a1 1 0 0 1 1-1Z"/>',
], [
    # BPMN: Startereignis, Aufgabe, Gateway
    '<circle cx="-8" cy="0" r="2.5"/><path d="M-5.5 0H-3.5"/><rect x="-3.5" y="-3" width="6" height="6" rx="1"/>'
    '<path d="M2.5 0H4.5"/><path d="M7.5-3 10.5 0 7.5 3 4.5 0Z"/>',
    # Versionen: Verzweigung mit drei Staenden
    '<circle cx="-5" cy="-5" r="2.4"/><circle cx="-5" cy="5" r="2.4"/><circle cx="5" cy="-1" r="2.4"/>'
    '<path d="M-5-2.6v5.2M-2.6-5C2-5 5-4.5 5-3.4"/>',
    # Gates: Ampel
    '<rect x="-4" y="-8" width="8" height="16" rx="2.5"/><circle cy="-4.5" r="1.4"/><circle r="1.4"/><circle cy="4.5" r="1.4"/>',
    # Memory: Speicherzylinder
    '<ellipse cy="-5" rx="6" ry="2.5"/><path d="M-6-5v10c0 1.4 2.7 2.5 6 2.5s6-1.1 6-2.5v-10"/>'
    '<path d="M-6 0c0 1.4 2.7 2.5 6 2.5s6-1.1 6-2.5"/>',
    # Dashboard: Balken
    '<path d="M-7 7V1M-2.3 7V-5M2.3 7V-1M7 7V-7"/>',
    # Audit: Lupe
    '<circle cx="-2" cy="-2" r="5.5"/><path d="M2 2l5 5"/>',
], [
    # Hoch konfigurierbar: Schieberegler
    '<path d="M-8-4h16M-8 4h16"/><circle cx="-2" cy="-4" r="2.4" fill="#1f160f"/><circle cx="3" cy="4" r="2.4" fill="#1f160f"/>',
    # Komplett individualisierbar: Puzzleteil
    '<path d="M-6-7h4a2 2 0 0 1 4 0h4v4a2 2 0 0 1 0 4v4h-4a2 2 0 0 1-4 0h-4v-4a2 2 0 0 1 0-4Z"/>',
    # Features abschaltbar: Kippschalter
    '<rect x="-9" y="-4.5" width="18" height="9" rx="4.5"/><circle cx="-4.5" r="2.6" fill="#1f160f"/>',
    # "...": kein Symbol, das Wort steht fuer sich
    '',
]]


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


def marker(kind, x, y, small=False):
    """Zeichen je Schrittart: Quadrat = fest, Kreis = KI, Raute = Mensch."""
    k = .85 if small else 1.0
    if kind == "ai":
        glow = "" if small else (f'<circle cx="{x:.1f}" cy="{y:.1f}" r="15" fill="{AI}" opacity=".35" '
                                 f'filter="url(#glow)"/>')
        return glow + f'<circle cx="{x:.1f}" cy="{y:.1f}" r="{9 * k:.1f}" fill="{AI}"/>'
    if kind == "human":
        r = 10.5 * k
        return (f'<path d="M{x:.1f} {y - r:.1f}L{x + r:.1f} {y:.1f}L{x:.1f} {y + r:.1f}L{x - r:.1f} {y:.1f}Z" '
                f'fill="{HUMAN}"/>')
    h = 8 * k
    return (f'<rect x="{x - h:.1f}" y="{y - h:.1f}" width="{2 * h:.1f}" height="{2 * h:.1f}" rx="2.5" '
            f'fill="#241910" stroke="{CREAM}" stroke-width="2.5"/>')


def ghost(kind, x, y):
    """Blasser Schritt fuer die Hintergrund-Workflows (nur Umrisse)."""
    if kind == "ai":
        return f'<circle cx="{x}" cy="{y}" r="5" stroke="{AI}"/>'
    if kind == "human":
        return f'<path d="M{x} {y - 6}L{x + 6} {y}L{x} {y + 6}L{x - 6} {y}Z" stroke="{HUMAN}"/>'
    return f'<rect x="{x - 5}" y="{y - 5}" width="10" height="10" rx="1.5"/>'


def background_flows():
    """Vier verschieden geformte Workflows, blass im Himmel: es gibt viele Wege."""
    g = []
    # 1) Gerade Kette
    x, y = 590, 66
    for i, k in enumerate(("fixed", "ai", "fixed", "human", "fixed")):
        g.append(ghost(k, x + i * 34, y))
    g.append(f'<path d="M{x + 6} {y}H{x + 4 * 34 - 6}"/>')
    # 2) Auffaechern (foreach) und wieder zusammenfuehren
    x, y = 1120, 58
    g.append(ghost("fixed", x, y))
    for dy in (-26, 0, 26):
        g.append(ghost("ai", x + 44, y + dy))
        g.append(f'<path d="M{x + 6} {y}C{x + 22} {y} {x + 22} {y + dy} {x + 38} {y + dy}"/>')
        g.append(f'<path d="M{x + 50} {y + dy}C{x + 66} {y + dy} {x + 66} {y} {x + 82} {y}"/>')
    g.append(ghost("fixed", x + 88, y))
    # 3) Verzweigung mit Freigabe
    x, y = 640, 150
    g.append(ghost("ai", x, y))
    g.append(ghost("human", x + 40, y))
    g.append(f'<path d="M{x + 6} {y}H{x + 33}"/>')
    g.append(f'<path d="M{x + 47} {y}C{x + 60} {y} {x + 60} {y - 22} {x + 74} {y - 22}"/>')
    g.append(f'<path d="M{x + 47} {y}C{x + 60} {y} {x + 60} {y + 22} {x + 74} {y + 22}"/>')
    g.append(ghost("fixed", x + 80, y - 22))
    g.append(ghost("fixed", x + 80, y + 22))
    # 4) Schleife mit Rueckpfeil
    x, y = 1150, 150
    for i, k in enumerate(("fixed", "ai", "fixed")):
        g.append(ghost(k, x + i * 36, y))
    g.append(f'<path d="M{x + 6} {y}H{x + 66}"/>')
    g.append(f'<path d="M{x + 72} {y + 6}C{x + 72} {y + 26} {x} {y + 26} {x} {y + 6}"/>')
    g.append(f'<path d="M{x - 4} {y + 12}L{x} {y + 6}L{x + 4} {y + 12}"/>')
    return (f'<g fill="none" stroke="{CREAM}" stroke-width="1.6" stroke-opacity=".22" '
            f'stroke-linecap="round" stroke-linejoin="round">{"".join(g)}</g>')


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

    # Szene rechts: Waechter-Erdmaennchen, umkreist von Bausteinen
    mx, scale = 1010, 1.12
    tx, ty = mx - 128 * scale, 118 - 30 * scale
    ocx, ocy, orx, ory = mx, 264, 214, 56
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
        x, y = rnd.uniform(700, 1265), rnd.uniform(14, 200)
        stars.append(f'<circle cx="{x:.0f}" cy="{y:.0f}" r="{rnd.uniform(.6, 1.5):.1f}" '
                     f'opacity="{rnd.uniform(.25, .75):.2f}"/>')
    add(f'<g fill="{CREAM}">{"".join(stars)}</g>')
    add(background_flows())

    # Hintere Haelfte des Rings (hinter dem Erdmaennchen)
    add(f'<use href="#loop" fill="none" stroke="{AMBER}" stroke-opacity=".32" stroke-width="2" '
        'stroke-dasharray="2 7" stroke-linecap="round"/>')

    def runner():
        return (f'<g class="runner"><circle r="11" fill="{AMBER}" opacity=".55" filter="url(#glow)"/>'
                f'<circle r="4.5" fill="#fff3dc"/>'
                '<animateMotion dur="9s" repeatCount="indefinite" rotate="auto">'
                '<mpath href="#loop"/></animateMotion></g>')

    add(runner())

    # Bausteine auf dem Ring, ohne Beschriftung: die Formen stehen fuer fest / KI / Mensch
    kinds = ["fixed", "ai", "human", "fixed", "ai", "fixed"]
    angles = [210, 330, 10, 60, 120, 170]  # hinten links, hinten rechts, rechts, vorn rechts, vorn links, links

    def node(i):
        x, y = on_orbit(angles[i])
        return marker(kinds[i], x, y)

    add(node(0))
    add(node(1))
    add(node(5))

    add(f'<g transform="translate({tx:.1f} {ty:.1f}) scale({scale})" '
        f'stroke-linecap="round" stroke-linejoin="round">{MEERKAT}</g>')

    # Huegel, verdeckt den Rumpf; dahinter eine flache Duene fuer Tiefe
    add(f'<path d="M520 {H}C640 402 760 386 900 384C1040 382 1160 370 {W} 352V{H}Z" fill="#2a1a0f" opacity=".8"/>')
    add(f'<path d="M700 {H}C790 390 880 334 1010 330C1130 326 1200 364 {W} 390V{H}Z" fill="url(#mound)"/>')

    # Vordere Haelfte des Rings (vor dem Erdmaennchen)
    add(f'<g clip-path="url(#front)"><use href="#loop" fill="none" stroke="{AMBER}" stroke-opacity=".75" '
        f'stroke-width="2.5"/>{runner()}</g>')
    for i in (2, 3, 4):
        add(node(i))
    # Pfeilspitze zeigt die Laufrichtung (im Uhrzeigersinn)
    for deg in (90,):
        x, y = on_orbit(deg)
        a = math.radians(deg)
        rot = math.degrees(math.atan2(ory * math.cos(a), -orx * math.sin(a)))
        add(f'<path transform="translate({x:.1f} {y:.1f}) rotate({rot:.1f})" d="M-5-6 2 0-5 6" fill="none" '
            f'stroke="{AMBER}" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/>')

    # Text links
    x0 = 72
    widths = []
    title, _ = text(demi, "MySuricates", 72, x0, 112, CREAM, tracking=-1.0)
    add(title)
    add(f'<rect x="{x0 + 2}" y="134" width="64" height="5" rx="2.5" fill="{AMBER}"/>')
    for i, line in enumerate(t["tagline"]):
        p, w = text(demi, line, 24, x0, 184 + i * 30, "#f0c890", tracking=-.2)
        add(p)
        widths.append(w)
    for i, line in enumerate(t["body"]):
        y = 262 + i * 29
        add(f'<rect x="{x0}" y="{y - 15}" width="3" height="18" rx="1.5" fill="{AMBER}" opacity=".8"/>')
        p, w = text(book, line, 19.5, x0 + 16, y, MUTED, tracking=.1)
        add(p)
        widths.append(w + 16)
    if x0 + max(widths) > 740:  # dahinter beginnen Ring und Szene
        raise SystemExit(f"{lang}: Textzeile zu breit ({max(widths):.0f} px)")

    # Reihen unten: was es alles gibt, je mit Symbol; Umbruch nach Breite, "…" steht am Ende
    items = [(icon, label) for icons, labels in zip(ICONS, t["things"]) for icon, label in zip(icons, labels)]
    row, lx = 0, x0
    for icon, label in items:
        if not icon:  # "…" steht ohne Symbol fuer alles Weitere
            p, w = text(demi, label, 22, lx, 398 + row * 30, AMBER)
            add(p)
            continue
        p, w = text(book, label, 16, lx + 24, 0, CREAM, tracking=.3)
        if lx + 24 + w > 740 and lx > x0:  # dahinter beginnt die Szene
            row, lx = row + 1, x0
        ly = 398 + row * 30
        add(f'<g transform="translate({lx + 8:.1f} {ly - 6:.1f})" fill="none" stroke="{AMBER}" '
            f'stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">{icon}</g>')
        p, _ = text(book, label, 16, lx + 24, ly, CREAM, tracking=.3)
        add(p)
        lx += 24 + w + 26
    if row > 3:
        raise SystemExit(f"{lang}: mehr als vier Reihen unten")

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
