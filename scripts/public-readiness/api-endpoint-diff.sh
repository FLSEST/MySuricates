#!/usr/bin/env bash
# Deterministic comparison: backend controller endpoints vs. docs/api-referenz.md (DE) + docs/en/api-referenz.md (EN).
# TODO 2cbc00a7.
#
# Usage: api-endpoint-diff.sh [controller_dir] [doc_de] [doc_en]
#   Without arguments: repo defaults (ai-automation-backend/.../controller, docs/api-referenz.md, docs/en/api-referenz.md).
#   With arguments (for the self-test): explicit fixture paths.
#
# Extracts from every *.java file in the controller directory the class @RequestMapping base
# plus all method mappings (@Get/Post/Put/Patch/DeleteMapping, @RequestMapping(method=...)),
# normalizes path variables to {id} and compares the result with all "METHODE /api/v1/..."
# occurrences in the two doc files (headings as well as running-text mentions), likewise normalized.
#
# Output:
#   FEHLT_IN_DOC <sprache> <METHODE> <pfad>   -- endpoint exists in the code but not in this doc
#   NUR_IN_DOC   <sprache> <METHODE> <pfad>   -- endpoint is in the doc but does not (any longer) exist in the code
# Exit 0: no gaps. Exit 1: at least one line of output. Exit 2: hard error (path missing).
set -uo pipefail

ROOT="$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel 2>/dev/null || pwd)"

CONTROLLER_DIR="${1:-$ROOT/ai-automation-backend/src/main/java/com/automation/controller}"
DOC_DE="${2:-$ROOT/docs/api-referenz.md}"
DOC_EN="${3:-$ROOT/docs/en/api-referenz.md}"

if [ ! -d "$CONTROLLER_DIR" ]; then
  echo "FEHLER: Controller-Verzeichnis nicht gefunden: $CONTROLLER_DIR" >&2
  exit 2
fi
for f in "$DOC_DE" "$DOC_EN"; do
  if [ ! -f "$f" ]; then
    echo "FEHLER: Doku-Datei nicht gefunden: $f" >&2
    exit 2
  fi
done

python3 - "$CONTROLLER_DIR" "$DOC_DE" "$DOC_EN" <<'PY'
import sys, re, glob, os

controller_dir, doc_de, doc_en = sys.argv[1:4]


def normalize_path(p):
    if not p:
        p = "/"
    if not p.startswith("/"):
        p = "/" + p
    p = re.sub(r"/+", "/", p)
    p = re.sub(r"\{[^}]*\}", "{id}", p)
    if len(p) > 1:
        p = p.rstrip("/")
    return p


ANN_METHOD = {"Get": "GET", "Post": "POST", "Put": "PUT", "Patch": "PATCH", "Delete": "DELETE"}
MAPPING_RE = re.compile(r'@(Get|Post|Put|Patch|Delete|Request)Mapping(?:\s*\((.*?)\))?')
CLASS_RE = re.compile(r'\b(?:class|interface)\s+\w+')


def extract_path_literals(args):
    """Nur das value/path-Attribut liefert Pfade — nicht produces/consumes/headers/params
    (z.B. @GetMapping(value = "/x", produces = "text/markdown") darf "text/markdown" nicht
    als zweiten Pfad missverstehen)."""
    if not args:
        return []
    m = re.search(r'\b(?:value|path)\s*=\s*(\{[^}]*\}|"[^"]*")', args)
    if m:
        blob = m.group(1)
    else:
        stripped = args.strip()
        m2 = re.match(r'(\{[^}]*\}|"[^"]*")', stripped) if stripped[:1] in ('"', '{') else None
        blob = m2.group(1) if m2 else None
    if not blob:
        return []
    return re.findall(r'"([^"]*)"', blob)


def extract_controllers(root):
    endpoints = set()
    for path in sorted(glob.glob(os.path.join(root, "**", "*.java"), recursive=True)):
        with open(path, encoding="utf-8") as fh:
            lines = fh.readlines()

        # Klassendeklaration suchen: alles davor gilt als "Klassenkopf" (Annotationen inkl.
        # Basis-@RequestMapping), alles danach als Methodenkoerper.
        class_idx = len(lines)
        for i, line in enumerate(lines):
            if CLASS_RE.search(line):
                class_idx = i
                break

        base = ""
        for i in range(class_idx):
            m = re.search(r'@RequestMapping\s*\(([^)]*)\)', lines[i])
            if m:
                quoted = extract_path_literals(m.group(1))
                if quoted:
                    base = quoted[0]

        for i in range(class_idx, len(lines)):
            for m in MAPPING_RE.finditer(lines[i]):
                ann, args = m.group(1), m.group(2) or ""
                if ann in ANN_METHOD:
                    methods = [ANN_METHOD[ann]]
                else:
                    # a bare @RequestMapping on the class head (base path only, no method) is
                    # already handled above as "base"; here only @RequestMapping(method=...) counts.
                    methods = re.findall(r'RequestMethod\.(\w+)', args)
                    if not methods:
                        continue
                quoted = extract_path_literals(args)
                paths = quoted if quoted else [""]
                for method in methods:
                    for p in paths:
                        full = (base.rstrip("/") + "/" + p.lstrip("/")) if p else base
                        endpoints.add((method.upper(), normalize_path(full)))
    return endpoints


DOC_RE = re.compile(r'\b(GET|POST|PUT|PATCH|DELETE)\s+(/api/v1/[A-Za-z0-9/_{}.\-]*)')


def extract_doc(path):
    endpoints = set()
    with open(path, encoding="utf-8") as fh:
        text = fh.read()
    for m in DOC_RE.finditer(text):
        method, p = m.group(1), m.group(2).rstrip(".")
        endpoints.add((method, normalize_path(p)))
    return endpoints


controllers = extract_controllers(controller_dir)
doc_sets = {"de": extract_doc(doc_de), "en": extract_doc(doc_en)}

exit_code = 0
for lang, doc_eps in sorted(doc_sets.items()):
    for method, path in sorted(controllers - doc_eps):
        print(f"FEHLT_IN_DOC {lang} {method} {path}")
        exit_code = 1
    for method, path in sorted(doc_eps - controllers):
        print(f"NUR_IN_DOC {lang} {method} {path}")
        exit_code = 1

sys.exit(exit_code)
PY
exit $?
