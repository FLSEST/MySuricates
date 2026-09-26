#!/usr/bin/env python3
"""ncloc.py — Zeilen ohne Leer-, Kommentar- und Importzeilen (NCLOC) je Quelldatei.

Das Architektur-Review (TODO b8c06b94) misst Groesse nicht mehr in rohen Zeilen: rund ein
Drittel einer typischen Backend-Datei sind Leerzeilen, Javadoc und Importe, und jede
Erweiterung schob Dateien ueber eine harte Zeilenkante. Gezaehlt wird hier, was Logik traegt.

Regeln (Java, TS, TSX):
  - Leerzeile: nur Leerraum                                  -> zaehlt nicht
  - Kommentarzeile: ausserhalb von Kommentaren kein Zeichen  -> zaehlt nicht
    (//, /* ... */, Javadoc, in TSX auch {/* ... */} allein auf der Zeile;
    Code mit Kommentar dahinter zaehlt)
  - Importzeile: Java `import …;` (auch static), TS/TSX eine Anweisung `import … from '…'`
    bzw. `import '…'` am Zeilenanfang, bei mehrzeiligen Importlisten alle ihre Zeilen
                                                             -> zaehlt nicht
  - alles andere zaehlt, auch `package`, Annotationen und schliessende Klammern.

Strings, Zeichenliterale, Java-Textbloecke und TS-Template-Strings (mit ${…}) werden
uebersprungen, damit ein "//" in einer URL keinen Kommentar beginnt. In TS/TSX gilt ein "/"
nach einem Operator, einer oeffnenden Klammer, einem Komma oder return/typeof/… als Beginn eines
Regex-Literals (wie im JS-Tokenizer); dessen Inhalt — etwa ein Backtick in /[#*_`>]/g — oeffnet
weder String noch Kommentar. Bekannte Grenzen: ein Regex-Literal ueber mehrere Zeilen gibt es in
JS nicht; ein JSX-Text, der mit "//" oder "/*" beginnt, wird als Kommentar gelesen, ebenso ein
Regex direkt nach ")" (etwa `if (x) /re/.test(y)`), der als Division gilt. Solche Stellen
verschieben die Zahl hoechstens um die betroffenen Zeilen.

Aufruf:  ncloc.py <basisverzeichnis>   liest Pfade (relativ zur Basis) zeilenweise von stdin
Ausgabe: ncloc<TAB>zeilen<TAB>pfad je Datei, in Eingabereihenfolge. zeilen zaehlt wie
         SourceSizeRatchetTest (Files.readAllLines(..).size() == len(text.splitlines())).
"""
import os
import re
import sys

JAVA_IMPORT = re.compile(r"^\s*import\s+(static\s+)?[\w.]+(\.\*)?\s*;")
# Ein JSX-Kommentar {/* … */} allein auf der Zeile: die Klammern sind Syntax, die Zeile ein Kommentar.
JSX_COMMENT_LINE = re.compile(r"^\s*\{\s*/\*.*\*/\s*\}\s*$")
TS_IMPORT_START = re.compile(r"""^\s*import(\s+type)?(\s|\{|\*|['"])""")


REGEX_BEFORE = set("(,=:[!&|?{};+-*%<>~^")
REGEX_AFTER_WORD = re.compile(r"(?:^|[^\w$])(return|typeof|case|do|else|in|of|void|yield|await|delete)$")


def regex_allowed(recent):
    """True, wenn an dieser Stelle ein '/' ein Regex-Literal beginnt und keine Division ist.

    Entscheidet wie ein JS-Tokenizer nach dem letzten bedeutsamen Zeichen: nach einem Operator,
    einer oeffnenden Klammer, einem Komma oder einem Schluesselwort wie return steht ein
    Ausdruck, also ein Regex; nach einem Bezeichner, einer Zahl, ')' oder ']' eine Division.
    """
    tail = recent.rstrip()
    if not tail:
        return True
    return tail[-1] in REGEX_BEFORE or bool(REGEX_AFTER_WORD.search(tail))


def skip_regex(line, start):
    """Index hinter dem Regex-Literal ab line[start] == '/'; None, wenn es in der Zeile nicht endet."""
    i, in_class = start + 1, False
    while i < len(line):
        ch = line[i]
        if ch == "\\":
            i += 2
            continue
        if ch == "[":
            in_class = True
        elif ch == "]":
            in_class = False
        elif ch == "/" and not in_class:
            i += 1
            while i < len(line) and (line[i].isalnum() or line[i] == "_"):
                i += 1  # Flags
            return i
        i += 1
    return None


def code_mask(text, template_strings):
    """Je Zeile True, wenn sie ausserhalb von Kommentaren ein Nicht-Leerzeichen hat."""
    lines = text.split("\n")
    mask = [False] * len(lines)
    state = "code"          # code | block | string
    quote = ""              # aktiver Stringbegrenzer: ' " ` oder """
    brace_stack = []        # je offenem ${ in Template-Strings die Klammertiefe
    depth = 0
    recent = ""             # zuletzt gesehener Code (Strings als "), fuer Regex gegen Division
    for index, line in enumerate(lines):
        i, n = 0, len(line)
        has_code = state == "string" and bool(line.strip())
        while i < n:
            if state == "block":
                end = line.find("*/", i)
                if end < 0:
                    break
                state, i = "code", end + 2
                continue
            if state == "string":
                if quote == '"""':
                    end = line.find('"""', i)
                    if end < 0:
                        break
                    state, i = "code", end + 3
                    continue
                ch = line[i]
                if ch == "\\":
                    i += 2
                    continue
                if quote == "`" and line.startswith("${", i):
                    brace_stack.append(depth)
                    depth += 1
                    state, i = "code", i + 2
                    recent = (recent + "{")[-40:]
                    continue
                if ch == quote:
                    state = "code"
                i += 1
                continue
            ch = line[i]
            if ch.isspace():
                recent = (recent + " ")[-40:]
                i += 1
                continue
            if line.startswith("//", i):
                break
            if line.startswith("/*", i):
                state, i = "block", i + 2
                continue
            has_code = True
            if ch == "/" and template_strings and regex_allowed(recent):
                end = skip_regex(line, i)
                if end is not None:
                    recent, i = (recent + "/")[-40:], end
                    continue
            if line.startswith('"""', i):
                state, quote, i = "string", '"""', i + 3
                recent = (recent + '"')[-40:]
                continue
            if ch in "'\"" or (ch == "`" and template_strings):
                state, quote, i = "string", ch, i + 1
                recent = (recent + '"')[-40:]
                continue
            if ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if brace_stack and depth == brace_stack[-1]:
                    brace_stack.pop()
                    state, quote = "string", "`"
            recent = (recent + ch)[-40:]
            i += 1
        # Ein einfacher String endet spaetestens am Zeilenende (Java/TS kennen keine
        # mehrzeiligen '…'/"…"); nur Textbloecke und Template-Strings laufen weiter.
        if state == "string" and quote in ("'", '"'):
            state = "code"
        recent = (recent + " ")[-40:]
        mask[index] = has_code and not (template_strings and JSX_COMMENT_LINE.match(line))
    return lines, mask


def import_lines(lines, kind):
    """Indizes der Zeilen, die zu einer Import-Anweisung gehoeren."""
    found = set()
    if kind == "java":
        for index, line in enumerate(lines):
            if JAVA_IMPORT.match(line):
                found.add(index)
        return found
    index = 0
    while index < len(lines):
        if TS_IMPORT_START.match(lines[index]) and not lines[index].lstrip().startswith("import("):
            start = index
            # Eine Import-Anweisung endet an der Zeile mit dem Modul-Spezifizierer ('…' / "…").
            while index < len(lines) and not re.search(r"""(from\s*|^\s*import\s*)['"][^'"]+['"]""", lines[index]):
                index += 1
                if index - start > 200:  # kein Spezifizierer: kein Import, sondern etwas anderes
                    index = start
                    break
            if index >= len(lines):
                index = start
            found.update(range(start, index + 1))
        index += 1
    return found


def ncloc(text, path):
    kind = "java" if path.endswith(".java") else "ts"
    if text.endswith("\n"):
        text = text[:-1]
    if not text:
        return 0
    lines, mask = code_mask(text, template_strings=(kind == "ts"))
    skip = import_lines(lines, kind)
    return sum(1 for index, has_code in enumerate(mask) if has_code and index not in skip)


def main():
    base = sys.argv[1] if len(sys.argv) > 1 else "."
    out = []
    for raw in sys.stdin:
        rel = raw.rstrip("\n")
        if not rel:
            continue
        with open(os.path.join(base, rel), "r", encoding="utf-8", errors="replace") as handle:
            text = handle.read()
        out.append("%d\t%d\t%s" % (ncloc(text, rel), len(text.splitlines()), rel))
    sys.stdout.write("\n".join(out) + ("\n" if out else ""))


if __name__ == "__main__":
    main()
