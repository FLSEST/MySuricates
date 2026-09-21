#!/usr/bin/env python3
"""Report page for scripts/installer-probe.sh — one self-contained HTML file.

Reads what the probe left in its output directory and writes <dir>/report.html:

  phases.tsv       name <TAB> command <TAB> exit code <TAB> seconds
  checks.tsv       label <TAB> ok|fail|skip <TAB> detail
  logs/<name>.log  terminal transcript of each phase (recorded under a pseudo terminal,
                   so it carries ANSI colours, carriage returns and erase-line sequences)
  screens/*.png    optional screenshots (login.png, dashboard.png), embedded as data URIs

The transcript is replayed through a small line-based terminal model instead of stripping
escape codes, so the page shows what a person at the terminal saw: npm's spinner collapses
to its last state, coloured ✓/✗ keep their colour. Only the standard library is used.
"""
from __future__ import annotations

import argparse
import base64
import html
import re
from pathlib import Path

# CSI (ESC [ params intermediates final), OSC (ESC ] … BEL|ST), charset and keypad selections.
_ESCAPE = re.compile(
    r"\x1b\[(?P<params>[0-?]*)(?P<inter>[ -/]*)(?P<final>[@-~])"
    r"|\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)"
    r"|\x1b[()][0-9A-Za-z]"
    r"|\x1b[78=>DEHMNOPc]"
)

_FG = {30: "k", 31: "r", 32: "g", 33: "y", 34: "b", 35: "m", 36: "c", 37: "w"}


def _apply_sgr(params: str, style: tuple) -> tuple:
    """Returns the new (fg, bold, dim) style after one SGR sequence."""
    fg, bold, dim = style
    codes = [int(p) if p.isdigit() else 0 for p in params.split(";")] if params else [0]
    i = 0
    while i < len(codes):
        c = codes[i]
        if c == 0:
            fg, bold, dim = None, False, False
        elif c == 1:
            bold = True
        elif c == 2:
            dim = True
        elif c == 22:
            bold = dim = False
        elif c == 39:
            fg = None
        elif c in _FG:
            fg = _FG[c]
        elif 90 <= c <= 97:
            fg = _FG[c - 60] + "2"
        elif c in (38, 48):
            # Extended colour: 38;5;n or 38;2;r;g;b — skip its arguments, keep the old colour.
            if i + 1 < len(codes) and codes[i + 1] == 5:
                i += 2
            elif i + 1 < len(codes) and codes[i + 1] == 2:
                i += 4
        i += 1
    return fg, bold, dim


def ansi_to_html(text: str) -> str:
    """Replays a terminal transcript line by line and returns escaped HTML with colour spans."""
    style: tuple = (None, False, False)
    out_lines: list[str] = []
    for raw in text.replace("\r\n", "\n").split("\n"):
        cells: list[tuple[str, tuple]] = []
        col = 0
        pos = 0
        while pos < len(raw):
            ch = raw[pos]
            if ch == "\x1b":
                m = _ESCAPE.match(raw, pos)
                if not m:
                    pos += 1
                    continue
                final = m.group("final")
                params = m.group("params") or ""
                if final == "m":
                    style = _apply_sgr(params, style)
                elif final == "K":
                    if params in ("", "0"):
                        del cells[col:]
                    elif params == "2":
                        cells.clear()
                elif final == "G":
                    col = max(int(params or "1") - 1, 0)
                elif final == "D":
                    col = max(col - int(params or "1"), 0)
                pos = m.end()
                continue
            if ch == "\r":
                col = 0
            elif ch == "\b":
                col = max(col - 1, 0)
            elif ch == "\t":
                target = (col // 8 + 1) * 8
                while col < target:
                    _put(cells, col, " ", style)
                    col += 1
            elif ch >= " " or ch == "\x7f":
                if ch != "\x7f":
                    _put(cells, col, ch, style)
                    col += 1
            pos += 1
        out_lines.append(_render_cells(cells))
    while out_lines and not out_lines[-1].strip():
        out_lines.pop()
    return "\n".join(out_lines)


def _put(cells: list, col: int, ch: str, style: tuple) -> None:
    while len(cells) < col:
        cells.append((" ", (None, False, False)))
    if col < len(cells):
        cells[col] = (ch, style)
    else:
        cells.append((ch, style))


def _render_cells(cells: list) -> str:
    parts: list[str] = []
    run: list[str] = []
    current = None
    for ch, st in cells + [("", "END")]:
        if st != current:
            if run:
                parts.append(_span("".join(run), current))
            run, current = [], st
        run.append(ch)
    return "".join(parts).rstrip()


def _span(text: str, style) -> str:
    esc = html.escape(text, quote=False)
    if not style or style == "END":
        return esc
    fg, bold, dim = style
    classes = [f"f-{fg}"] if fg else []
    if bold:
        classes.append("bold")
    if dim:
        classes.append("dim")
    return f'<span class="{" ".join(classes)}">{esc}</span>' if classes else esc


def _read_tsv(path: Path, columns: int) -> list[list[str]]:
    if not path.is_file():
        return []
    rows = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if not line.strip():
            continue
        cells = line.split("\t")
        rows.append((cells + [""] * columns)[:columns])
    return rows


_STATUS = {"ok": ("✓", "ok"), "fail": ("✗", "fail"), "skip": ("–", "skip")}

_CSS = """
:root { --bg:#f6f5f2; --surface:#ffffff; --text:#1f1d1a; --muted:#6b665e; --line:#e2ded6;
  --ok:#1f7a45; --fail:#b3261e; --skip:#8a8378; --accent:#b8661f; }
@media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) {
  --bg:#171614; --surface:#211f1c; --text:#ece8e1; --muted:#a39d93; --line:#34302b;
  --ok:#5cc28a; --fail:#f07a70; --skip:#8f887d; --accent:#e39a52; } }
* { box-sizing:border-box; }
body { margin:0; background:var(--bg); color:var(--text);
  font:15px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif; }
main { max-width:1080px; margin:0 auto; padding:32px 16px 64px; }
h1 { font-size:26px; margin:0 0 4px; }
h2 { font-size:18px; margin:32px 0 12px; }
.sub { color:var(--muted); margin:0 0 20px; }
.verdict { display:inline-block; padding:4px 12px; border-radius:999px; font-weight:600;
  font-size:14px; margin-left:8px; vertical-align:middle; color:#fff; }
.verdict.green { background:var(--ok); } .verdict.red { background:var(--fail); }
.meta { display:grid; grid-template-columns:max-content 1fr; gap:4px 16px; margin:0;
  background:var(--surface); border:1px solid var(--line); border-radius:10px; padding:14px 16px; }
.meta dt { color:var(--muted); } .meta dd { margin:0; font-family:ui-monospace,monospace; overflow-wrap:anywhere; }
ul.checks { list-style:none; padding:0; margin:0; background:var(--surface);
  border:1px solid var(--line); border-radius:10px; }
ul.checks li { display:grid; grid-template-columns:24px 1fr; gap:8px; padding:9px 14px;
  border-top:1px solid var(--line); } ul.checks li:first-child { border-top:0; }
.mark { font-weight:700; text-align:center; } .mark.ok { color:var(--ok); }
.mark.fail { color:var(--fail); } .mark.skip { color:var(--skip); }
.detail { display:block; color:var(--muted); font-size:13px; overflow-wrap:anywhere; }
table { width:100%; border-collapse:collapse; background:var(--surface);
  border:1px solid var(--line); border-radius:10px; overflow:hidden; }
th, td { text-align:left; padding:8px 12px; border-top:1px solid var(--line); font-size:14px; }
th { color:var(--muted); font-weight:500; border-top:0; }
td code { font-size:13px; overflow-wrap:anywhere; }
.num { text-align:right; font-variant-numeric:tabular-nums; white-space:nowrap; }
.shots { display:grid; grid-template-columns:repeat(auto-fit,minmax(280px,1fr)); gap:16px; }
figure { margin:0; background:var(--surface); border:1px solid var(--line); border-radius:10px; overflow:hidden; }
figure img { display:block; width:100%; height:auto; }
figcaption { padding:8px 12px; color:var(--muted); font-size:13px; }
details { margin:12px 0; border:1px solid var(--line); border-radius:10px; overflow:hidden; background:var(--surface); }
summary { cursor:pointer; padding:10px 14px; font-weight:600; }
summary .rc { font-weight:400; color:var(--muted); margin-left:8px; }
pre.term { margin:0; padding:14px 16px; background:#12110f; color:#dcd6cc; overflow-x:auto;
  font:13px/1.45 ui-monospace,"SFMono-Regular",Menlo,monospace; max-height:640px; }
.f-k{color:#6e6a64}.f-r{color:#f0776c}.f-g{color:#6fd08c}.f-y{color:#e8c25e}.f-b{color:#6aa7f0}
.f-m{color:#d38ae0}.f-c{color:#5fc9d6}.f-w{color:#e8e3da}.f-k2{color:#8d877f}.f-r2{color:#ff938a}
.f-g2{color:#8fe3a8}.f-y2{color:#f4d77f}.f-b2{color:#8fbcf6}.f-m2{color:#e4a8ef}.f-c2{color:#86dde6}
.f-w2{color:#ffffff}.bold{font-weight:700}.dim{opacity:.7}
.note { color:var(--muted); font-size:13px; }
"""


def render(directory: Path, meta: dict) -> str:
    phases = _read_tsv(directory / "phases.tsv", 4)
    checks = _read_tsv(directory / "checks.tsv", 3)
    green = bool(checks) and all(status != "fail" for _, status, _ in checks)
    esc = lambda s: html.escape(str(s))  # noqa: E731

    parts = [
        "<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\">",
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">",
        f"<title>Installer-Probe {esc(meta.get('short', ''))}</title>",
        f"<style>{_CSS}</style></head><body><main>",
        "<h1>Installer-Probe"
        f"<span class=\"verdict {'green' if green else 'red'}\">{'GRÜN' if green else 'ROT'}</span></h1>",
        "<p class=\"sub\">So erlebt ein neuer Nutzer <code>scripts/setup.sh</code> auf einer frischen "
        "Maschine: nichts installiert außer git, sudo und tzdata.</p>",
        "<dl class=\"meta\">",
    ]
    for label, key in (("Stand", "ref"), ("Commit", "sha"), ("Image", "image"),
                       ("Gestartet", "started"), ("Dauer gesamt", "duration")):
        if meta.get(key):
            parts.append(f"<dt>{label}</dt><dd>{esc(meta[key])}</dd>")
    parts.append("</dl>")

    parts.append("<h2>Prüfungen</h2><ul class=\"checks\">")
    for label, status, detail in checks:
        mark, cls = _STATUS.get(status, ("?", "skip"))
        det = f"<span class=\"detail\">{esc(detail)}</span>" if detail else ""
        parts.append(f"<li><span class=\"mark {cls}\">{mark}</span><span>{esc(label)}{det}</span></li>")
    parts.append("</ul>")

    shots = [(p, cap) for p, cap in ((directory / "screens" / "login.png", "Erster Aufruf: Login"),
                                      (directory / "screens" / "dashboard.png", "Nach dem Login: Dashboard"))
             if p.is_file()]
    if shots:
        parts.append("<h2>Im Browser</h2><div class=\"shots\">")
        for path, caption in shots:
            data = base64.b64encode(path.read_bytes()).decode("ascii")
            parts.append(f"<figure><img alt=\"{esc(caption)}\" src=\"data:image/png;base64,{data}\">"
                         f"<figcaption>{esc(caption)}</figcaption></figure>")
        parts.append("</div>")

    if phases:
        parts.append("<h2>Phasen</h2><table><thead><tr><th>Phase</th><th>Befehl</th>"
                     "<th class=\"num\">Exit</th><th class=\"num\">Dauer</th></tr></thead><tbody>")
        for name, command, rc, seconds in phases:
            parts.append(f"<tr><td>{esc(name)}</td><td><code>{esc(command)}</code></td>"
                         f"<td class=\"num\">{esc(rc)}</td><td class=\"num\">{esc(_duration(seconds))}</td></tr>")
        parts.append("</tbody></table>")
        parts.append("<h2>Terminal-Mitschnitt</h2><p class=\"note\">Aufgezeichnet unter einem "
                     "Pseudo-Terminal wie bei einem Menschen am Bildschirm, so wie der Installer ohne "
                     "Rückfragen (--yes) läuft. apt zeigt keinen animierten Fortschrittsbalken.</p>")
        for index, (name, command, rc, seconds) in enumerate(phases):
            log = directory / "logs" / f"{name}.log"
            body = ansi_to_html(log.read_text(encoding="utf-8", errors="replace")) if log.is_file() else ""
            is_open = " open"
            parts.append(f"<details{is_open}><summary>{esc(name)} <span class=\"rc\">$ {esc(command)} "
                         f"· Exit {esc(rc)} · {esc(_duration(seconds))}</span></summary>"
                         f"<pre class=\"term\">{body}</pre></details>")
    parts.append("</main></body></html>\n")
    return "".join(parts)


def _duration(seconds: str) -> str:
    try:
        total = int(seconds)
    except (TypeError, ValueError):
        return str(seconds)
    return f"{total // 60} min {total % 60:02d} s" if total >= 60 else f"{total} s"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dir", required=True, type=Path)
    for key in ("ref", "sha", "short", "image", "started", "duration"):
        parser.add_argument(f"--{key}", default="")
    args = parser.parse_args()
    meta = {k: getattr(args, k) for k in ("ref", "sha", "short", "image", "started", "duration")}
    (args.dir / "report.html").write_text(render(args.dir, meta), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
