#!/usr/bin/env python3
"""Kopf-Grafik des Installers: drei Erdmaennchen auf dem Huegel + Schriftzug "MySuricates".

Quelle ist die Pixelvorlage unten (Spec: docs/plans/2026-09-21-installer-gefuehrt-design.md,
Anhang A). Das Skript laeuft nur zur Entwicklungszeit und schreibt scripts/lib/art.generated.sh —
der Installer selbst braucht weder Python noch Schriften (Leitidee: keine Abhaengigkeiten).

    python3 scripts/tools/installer-art.py           # schreiben
    python3 scripts/tools/installer-art.py --check   # Exit 1, wenn die Datei veraltet ist (CI)

Zwei Pixel je Zeichen (▀ ▄ █). Drei Fassungen: 24-Bit-Farbe, 256 Farben (Terminal.app auf macOS
kann keine 24 Bit) und einfarbig (ohne TTY). Nur Standardbibliothek.
"""
import sys
from pathlib import Path

OUT = Path(__file__).resolve().parents[1] / "lib" / "art.generated.sh"

PAL = {
    "b": (196, 150, 96),   # Fell
    "k": (160, 116, 70),   # Schatten
    "l": (232, 204, 158),  # Bauch
    "D": (52, 36, 24),     # Augenflecken, Ohren, Nase
    "w": (245, 235, 215),  # Augenglanz
    "a": (170, 124, 76),   # Arme
    "h": (139, 105, 20),   # Huegel
    "H": (107, 79, 16),    # Huegelfuss
}

BIG = """\
....bbbbb.....
...bbbbbbb....
...DbbbbbD....
...bDDbDDb....
...bDwbwDb....
....bbbbb.....
.....bbb......
.....bDb......
.....bbb......
....kbbbk.....
...kbblbbk....
...kblllbk....
...aalllaa....
....aklka.....
....blllb.....
....blllb.....
...bblllbb....
...bblllbb....
...kbblbbk....
...bb...bb....
..bbb...bbkkkk""".splitlines()

SMALL = """\
...bbb....
..bbbbb...
..DbbbD...
..DwbwD...
...bbb....
...bDb....
...kbk....
..kblbk...
..aalaa...
...ala....
...blb....
..bblbb...
..bblbb...
..bb.bb...
.bbb.bbkkk""".splitlines()

# DejaVu Sans Bold 9 px, Buchstaben einzeln mit 1 px Abstand (ohne verschmelzen "r" und "i")
BANNER = [
    " ▄▄   ▄▄           ▄▄▄▄               ██",
    " ███ ███  ▄▄  ▄▄  ██  ▀  ▄▄ ▄▄  ▄▄▄▄  ▄▄   ▄▄▄  ▄▄▄▄   ██▄    ▄▄▄    ▄▄▄",
    " ██ █ ██   █▄▄█    ▀▀█▄  ██ ██  ██    ██  ██    ▄▄▄██  ██    ██▄██  ██▄▄",
    " ██   ██    ██    █▄▄█▀  ██▄██  ██    ██  ▀█▄▄  ██▄██  ██▄   ▀█▄▄▄  ▄▄█▀",
    "           ▄█",
]

# Die 256er-Palette hat fuer Sandbraun und Creme keinen guten Naechsten (algorithmisch: Lachs und
# Gruenlich) — diese Toene sind von Hand gewaehlt, der Rest wird berechnet.
PAL256 = {"b": 137, "k": 94, "a": 94, "l": 223}

W, H = 44, 24


def canvas():
    g = [["."] * W for _ in range(H)]
    for sprite, x0, y0 in ((SMALL, 3, 7), (BIG, 15, 0), (SMALL, 31, 7)):
        for y, row in enumerate(sprite):
            for x, c in enumerate(row):
                if c != ".":
                    g[y0 + y][x0 + x] = c
    for y, (a, b, c) in zip(range(21, 24), ((4, 40, "h"), (1, 43, "h"), (0, 44, "H"))):
        for x in range(a, b):
            if g[y][x] == ".":
                g[y][x] = c
    return g


def xterm256(rgb):
    """Naechste Farbe aus Wuerfel (16-231) oder Graustufen (232-255)."""
    levels = [0, 95, 135, 175, 215, 255]
    gray_i = max(0, min(23, round((sum(rgb) / 3 - 8) / 10)))
    gray_rgb = (8 + 10 * gray_i,) * 3
    # "redmean": wahrnehmungsnaeher als der reine RGB-Abstand (der machte das Fell lachsfarben)
    def dist(a, b):
        r = (a[0] + b[0]) / 2
        dr, dg, db = a[0] - b[0], a[1] - b[1], a[2] - b[2]
        return (2 + r / 256) * dr * dr + 4 * dg * dg + (2 + (255 - r) / 256) * db * db
    best = min(((dist(rgb, (levels[i], levels[j], levels[k])), 16 + 36 * i + 6 * j + k)
                for i in range(6) for j in range(6) for k in range(6)))
    return best[1] if best[0] <= dist(rgb, gray_rgb) else 232 + gray_i


def render(g, mode):
    rows = []
    for y in range(0, H, 2):
        line = ""
        for x in range(W):
            t, u = g[y][x], (g[y + 1][x] if y + 1 < H else ".")
            if mode == "plain":
                line += "█" if t != "." and u != "." else "▀" if t != "." else "▄" if u != "." else " "
                continue
            def fg(c):
                return "\\033[38;2;%d;%d;%dm" % PAL[c] if mode == "24" else "\\033[38;5;%dm" % PAL256.get(c, xterm256(PAL[c]))
            def bg(c):
                return "\\033[48;2;%d;%d;%dm" % PAL[c] if mode == "24" else "\\033[48;5;%dm" % PAL256.get(c, xterm256(PAL[c]))
            if t == "." and u == ".":
                line += "\\033[0m "
            elif u == ".":
                line += "\\033[0m" + fg(t) + "▀"
            elif t == ".":
                line += "\\033[0m" + fg(u) + "▄"
            else:
                line += fg(t) + bg(u) + "▀"
        rows.append(line + ("\\033[0m" if mode != "plain" else ""))
    return rows


def generated():
    g = canvas()
    out = [
        "#!/usr/bin/env bash",
        "# generiert von scripts/tools/installer-art.py — nicht von Hand aendern",
        "# Kopf-Grafik des Installers: drei Erdmaennchen (44 Spalten x 12 Zeilen) und Schriftzug (72 Spalten).",
        "# Reine Daten; bash 3.2: indizierte Arrays mit $'...'-Zeilen.",
        "",
        "ART_WIDTH=%d" % W,
        "ART_BANNER_WIDTH=%d" % max(len(r) for r in BANNER),
    ]
    for name, mode in (("ART_ROWS_24", "24"), ("ART_ROWS_256", "256"), ("ART_ROWS_PLAIN", "plain")):
        out.append("%s=(" % name)
        out += ["  $'%s'" % r for r in render(g, mode)]
        out.append(")")
    out.append("ART_BANNER=(")
    out += ["  $'%s'" % r for r in BANNER]
    out.append(")")
    return "\n".join(out) + "\n"


def main():
    text = generated()
    if "--check" in sys.argv:
        if not OUT.exists() or OUT.read_text(encoding="utf-8") != text:
            print("art.generated.sh ist veraltet — python3 scripts/tools/installer-art.py", file=sys.stderr)
            return 1
        print("Kopf-Grafik OK")
        return 0
    OUT.write_text(text, encoding="utf-8")
    print("geschrieben: %s" % OUT)
    return 0


if __name__ == "__main__":
    sys.exit(main())
