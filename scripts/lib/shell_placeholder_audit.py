#!/usr/bin/env python3
"""Sucht in Shell-Feldern von Workflow-Definitionen unquoted Output-Platzhalter (TODO 5104c8d7).

Warum es dieses Werkzeug gibt: Seit TODO 22e6bec8 wird die Ausgabe eines KI-Blocks, dessen
Prompt Plattform-Daten enthielt, als untrusted gefaerbt; ab da kommen alle Blockausgaben in
Shell-Feldern als EIN gequotetes Argument an. Eine Stelle, die auf Wortauftrennung baut
(`for x in {{ ... }}`, `cmd {{ ... }}` mit erwarteten Flags, `$1`/`$2` ohne `set --`), bricht
dann still. Die Erhebung von Hand ist muehsam und altert sofort — deshalb ein Skript statt
einer Liste in einer Markdown-Datei.

Die Einordnung haengt an der syntaktischen Rolle des Platzhalters, nicht an seinem Vorkommen:

  gequotet          '{{ x }}' oder "{{ x }}"        unkritisch
  heredoc-inhalt    im Rumpf eines Heredocs         unkritisch — und darf NICHT escaped werden,
                                                    sonst landen Anfuehrungszeichen im Inhalt
  set--             set -- {{ x }}                  die empfohlene Form (WORKFLOW-GUIDE)
  zuweisung         VAR={{ x }}                     nur mit shell_escape_variables korrekt,
                                                    ohne bricht sie am ersten Leerzeichen
  wortauftrennung   for/in, $1..$9 in derselben     der eigentliche Befund
                    Zeile
  sonstiges         alles andere                    von Hand ansehen

Aufruf:
    scripts/lib/shell_placeholder_audit.py <definitionen.json> [--details]

<definitionen.json> ist entweder die Antwort von GET /api/v1/workflows (Liste oder
{"content": [...]}) oder eine einzelne Definition. Das Skript ruft selbst nichts ab — es
liest, was ihm gegeben wird, und laeuft damit auch gegen workflow-pool/*.json.

Exit: 0 = keine Stelle, die auf Wortauftrennung baut; 1 = mindestens eine (Liste auf stdout).
"""
import json
import re
import sys

OUTPUT_PLACEHOLDER = re.compile(
    r"\{\{\s*(previous_output|previous_report|step_\d+_output"
    r"|block\.[A-Za-z0-9_\-]+\.output)\s*\}\}")

# Alle Quoting-Formen des Trennzeichens, und es darf eine Umleitung folgen
# (`python3 - <<'PY' > out`, `cat <<\__STEP0__ | grep …`). Beides kommt in echten
# Definitionen vor und wurde bei der ersten Erhebung je einmal uebersehen.
HEREDOC_START = re.compile(
    r"<<-?\s*(?:'([A-Za-z_][A-Za-z0-9_]*)'"
    r"|\"([A-Za-z_][A-Za-z0-9_]*)\""
    r"|\\([A-Za-z_][A-Za-z0-9_]*)"
    r"|([A-Za-z_][A-Za-z0-9_]*))")

WORD_SPLIT = re.compile(r"\bfor\b[^\n]*\bin\b|\$[1-9]")


def heredoc_body_lines(command):
    """Zeilennummern (0-basiert), die im Rumpf eines Heredocs liegen."""
    inside, delimiter, body = False, None, set()
    for number, line in enumerate(command.splitlines()):
        if inside:
            if line.strip() == delimiter:
                inside, delimiter = False, None
            else:
                body.add(number)
            continue
        match = HEREDOC_START.search(line)
        if match:
            inside = True
            delimiter = next(group for group in match.groups() if group)
    return body


def shell_blocks(node, found=None):
    """Alle Shell-Bloecke, auch in foreach/while/conditional/switch/retry verschachtelt."""
    found = [] if found is None else found
    if isinstance(node, dict):
        if node.get("type") == "shell" and isinstance(node.get("config"), dict):
            found.append(node)
        for value in node.values():
            shell_blocks(value, found)
    elif isinstance(node, list):
        for value in node:
            shell_blocks(value, found)
    return found


def classify(line, placeholder, in_heredoc, quoted):
    if quoted:
        return "gequotet"
    if in_heredoc:
        return "heredoc-inhalt"
    stripped = line.strip()
    if stripped.startswith("set --"):
        return "set--"
    if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*=" + re.escape(placeholder), stripped):
        return "zuweisung"
    if WORD_SPLIT.search(stripped):
        return "wortauftrennung"
    return "sonstiges"


def definitions(payload):
    if isinstance(payload, list):
        return payload
    if isinstance(payload, dict):
        if isinstance(payload.get("content"), list):
            return payload["content"]
        return [payload]
    return []


def audit(payload):
    """Liste von (rolle, workflow-id, name, blockname, escaped, zeile)."""
    results = []
    for definition in definitions(payload):
        name = definition.get("name")
        name = name if isinstance(name, str) else json.dumps(name, ensure_ascii=False)
        for block in shell_blocks(definition.get("blocks") or []):
            command = block["config"].get("command") or ""
            escaped = bool(block["config"].get("shell_escape_variables"))
            body = heredoc_body_lines(command)
            for number, line in enumerate(command.splitlines()):
                for match in OUTPUT_PLACEHOLDER.finditer(line):
                    before = line[max(0, match.start() - 1):match.start()]
                    after = line[match.end():match.end() + 1]
                    quoted = before in ("'", '"') and after in ("'", '"')
                    role = classify(line, match.group(0), number in body, quoted)
                    results.append((role, definition.get("id", "?")[:8], name,
                                    block.get("name") or "(ohne Namen)", escaped,
                                    line.strip()[:90]))
    return results


def main():
    arguments = [a for a in sys.argv[1:] if not a.startswith("--")]
    details = "--details" in sys.argv
    if len(arguments) != 1:
        print(__doc__.strip().splitlines()[0], file=sys.stderr)
        print("Aufruf: scripts/lib/shell_placeholder_audit.py <definitionen.json> [--details]",
              file=sys.stderr)
        return 2

    with open(arguments[0], encoding="utf-8") as handle:
        results = audit(json.load(handle))

    order = ["wortauftrennung", "sonstiges", "zuweisung", "set--", "heredoc-inhalt", "gequotet"]
    by_role = {role: [r for r in results if r[0] == role] for role in order}

    print(f"{len(results)} Output-Platzhalter in Shell-Feldern")
    for role in order:
        rows = by_role[role]
        if not rows:
            continue
        without_escape = sum(1 for r in rows if not r[4])
        print(f"  {len(rows):4}  {role:17} (davon {without_escape} ohne shell_escape_variables)")
        if details or role in ("wortauftrennung", "sonstiges", "zuweisung"):
            for _, wid, name, block, escaped, line in sorted(rows, key=lambda r: (r[2], r[3])):
                flag = "esc" if escaped else "OHNE-ESC"
                print(f"        {wid}  {name[:32]:34} {block[:20]:22} {flag:9} {line}")

    risky = by_role["wortauftrennung"]
    # zuweisung OHNE Escape bricht am ersten Leerzeichen — auch das ist ein Befund.
    risky += [r for r in by_role["zuweisung"] if not r[4]]
    if risky:
        print(f"\nBEFUND: {len(risky)} Stelle(n) brauchen eine Aenderung"
              " (set---Form oder shell_escape_variables).")
        return 1
    print("\nOK: keine Stelle baut auf Wortauftrennung, jede Zuweisung ist escaped.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
