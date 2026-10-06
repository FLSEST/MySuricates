#!/usr/bin/env python3
"""Searches shell fields of workflow definitions for unquoted output placeholders (TODO 5104c8d7).

Why this tool exists: since TODO 22e6bec8 the output of an AI block whose prompt contained
platform data is marked as untrusted; from then on all block outputs arrive in shell
fields as ONE quoted argument. A place that relies on word splitting
(`for x in {{ ... }}`, `cmd {{ ... }}` with expected flags, `$1`/`$2` without `set --`) then
breaks silently. Surveying by hand is tedious and ages immediately — hence a script instead of
a list in a Markdown file.

The classification depends on the syntactic role of the placeholder, not on its occurrence:

  gequotet          '{{ x }}' or "{{ x }}"          harmless
  heredoc-inhalt    in the body of a heredoc        harmless — and must NOT be escaped,
                                                    otherwise quotes end up in the content
  set--             set -- {{ x }}                  the recommended form (WORKFLOW-GUIDE)
  zuweisung         VAR={{ x }}                     correct only with shell_escape_variables,
                                                    without it breaks at the first space
  wortauftrennung   for/in, $1..$9 in the same     the actual finding
                    line
  sonstiges         everything else                 look at by hand

Usage:
    scripts/lib/shell_placeholder_audit.py <definitions.json> [--details]

<definitions.json> is either the response of GET /api/v1/workflows (list or
{"content": [...]}) or a single definition. The script fetches nothing itself — it
reads what it is given, and thus also runs against workflow-pool/*.json.

Exit: 0 = no place that relies on word splitting; 1 = at least one (list on stdout).
"""
import json
import re
import sys

OUTPUT_PLACEHOLDER = re.compile(
    r"\{\{\s*(previous_output|previous_report|step_\d+_output"
    r"|block\.[A-Za-z0-9_\-]+\.output)\s*\}\}")

# All quoting forms of the delimiter, and a redirect may follow
# (`python3 - <<'PY' > out`, `cat <<\__STEP0__ | grep …`). Both occur in real
# definitions and were each overlooked once during the first survey.
HEREDOC_START = re.compile(
    r"<<-?\s*(?:'([A-Za-z_][A-Za-z0-9_]*)'"
    r"|\"([A-Za-z_][A-Za-z0-9_]*)\""
    r"|\\([A-Za-z_][A-Za-z0-9_]*)"
    r"|([A-Za-z_][A-Za-z0-9_]*))")

WORD_SPLIT = re.compile(r"\bfor\b[^\n]*\bin\b|\$[1-9]")


def heredoc_body_lines(command):
    """Line numbers (0-based) that lie in the body of a heredoc."""
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
    """All shell blocks, also nested in foreach/while/conditional/switch/retry."""
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
    """List of (role, workflow-id, name, blockname, escaped, line)."""
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
    # assignment WITHOUT escape breaks at the first space — that is a finding too.
    risky += [r for r in by_role["zuweisung"] if not r[4]]
    if risky:
        print(f"\nBEFUND: {len(risky)} Stelle(n) brauchen eine Aenderung"
              " (set---Form oder shell_escape_variables).")
        return 1
    print("\nOK: keine Stelle baut auf Wortauftrennung, jede Zuweisung ist escaped.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
