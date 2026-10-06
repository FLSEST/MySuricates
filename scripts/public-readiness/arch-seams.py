#!/usr/bin/env python3
"""arch-seams.py — seams per backend candidate for the verdict in the architecture review (TODO e0e9495f).

Since 4.7.0 the review has the AI read the files from ## review_candidates and judge whether
a cut is worthwhile. This script supplies the facts it would otherwise have to guess: which
method uses which collaborators. The bytecode (javap -c -l -p) is measured, not the
source — field accesses appear there as getfield, source lines in the LineNumberTable.

  Collaborator   injected field as in ## files of arch-metrics.sh: "private final <type> <name>;"
                 without initializer, not static, type not in NON_COLLAB_RE. The exclusion list
                 is read from arch-metrics.sh so that both counts do not drift apart.
  Method         merged per name (overloads), lambdas (lambda$<method>$N) count towards the
                 enclosing method, methods of inner classes are called <Inner>.<method>.
                 Constructors are missing: they touch every injected field.
  Group          methods connected via shared collaborators. Several groups are
                 a cut candidate along the collaborators; one group means a cut would have to be
                 justified differently. Methods without a collaborator appear only as a number.
  nur_konstruktor  injected, but never read outside the constructor.

Call:    arch-seams.py <src/main/java> <classes-dir> <subject>...
         subject = path from com/automation/ including .java, as in ## review_candidates.
Output:  ## seams — stably sorted. What cannot be measured appears as a missing line with reason,
         never as an empty table.
Exit:    0 always (fact sheet for a downstream evaluator); 2 on a wrong call.
"""
import glob
import os
import re
import shutil
import subprocess
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
FIELD_DECL = re.compile(r"^  (?P<mods>(?:(?:private|protected|public|static|final|transient|volatile) )*)"
                        r"(?P<type>[^()]+) (?P<name>[\w$]+);$")
METHOD_DECL = re.compile(r"^  (?:[^()]* )?(?P<name>[\w$.<>]+)\(.*\)(?: throws [\w.$, ]+)?;$")
FIELD_ACCESS = re.compile(r"\b(?:getfield|putfield)\s+#\d+\s+// Field (?:(?P<owner>[\w/$]+)\.)?(?P<name>[\w$]+):")
LINE_ENTRY = re.compile(r"^\s+line (\d+): \d+$")
LAMBDA = re.compile(r"^lambda\$(.+)\$\d+$")


def non_collab_pattern():
    """NON_COLLAB_RE from arch-metrics.sh — one exclusion list for both counts."""
    path = os.path.join(SCRIPT_DIR, "arch-metrics.sh")
    try:
        text = open(path, encoding="utf-8").read()
    except OSError:
        return None
    match = re.search(r"^\s*NON_COLLAB_RE='([^']+)'\s*$", text, re.M)
    return re.compile(match.group(1)) if match else None


def simple_type(declared):
    """java.util.List<com.automation.X> -> List; com.automation.Outer$Inner -> Inner."""
    base = re.sub(r"[<\[].*$", "", declared.strip())
    return re.split(r"[.$]", base)[-1]


def javap(paths):
    result = subprocess.run(["javap", "-c", "-l", "-p"] + paths, capture_output=True, text=True)
    return result.stdout if result.returncode == 0 else None


def parse(text, outer_internal):
    """{class-file-block: ...} -> (fields of the outer class, {method: (accesses, lines)})."""
    fields = {}       # name -> (mods, type) from the outer class only
    methods = {}      # (class, raw name) -> [set(outer fields), [lines]]
    klass, current, in_lines = None, None, False
    outer_dotted = outer_internal.replace("/", ".")
    for line in text.splitlines():
        header = re.match(r"^(?:[\w ]+ )?(?:class|interface|enum|record) ([\w.$]+)", line)
        if header and not line.startswith(" "):
            klass, current, in_lines = header.group(1), None, False
            continue
        if line.startswith("  ") and not line.startswith("   "):
            in_lines = False
            method = METHOD_DECL.match(line)
            if method:
                current = (klass, method.group("name"))
                methods.setdefault(current, [set(), []])
                continue
            field = FIELD_DECL.match(line)
            if field and klass == outer_dotted:
                fields[field.group("name")] = (field.group("mods"), field.group("type"))
            current = None
            continue
        if current is None:
            continue
        if line.strip() == "LineNumberTable:":
            in_lines = True
            continue
        if in_lines:
            entry = LINE_ENTRY.match(line)
            if entry:
                methods[current][1].append(int(entry.group(1)))
                continue
            in_lines = False
        access = FIELD_ACCESS.search(line)
        if access:
            owner = access.group("owner")
            own = owner is None and klass == outer_dotted
            if own or owner == outer_internal:
                methods[current][0].add(access.group("name"))
    return fields, methods


def injected_without_initializer(source, name):
    """True if the source declares the field as 'private final <type> <name>;' without initializer."""
    return re.search(r"^\s*private\s+final\s+[^;=]*?\b" + re.escape(name) + r"\s*;", source, re.M) is not None


def label(klass, raw, outer_dotted):
    """(Method name for the output, is_lambda): lambdas to the enclosing method, inner classes
    with prefix; None for constructors and initializers (javac names their lambdas lambda$new$N or
    lambda$static$N, javap the constructor with the fully qualified class name)."""
    name, lambda_ = raw, False
    while True:
        match = LAMBDA.match(name)
        if not match:
            break
        name, lambda_ = match.group(1), True
    if name in ("new", "static") or "." in name:
        return None, lambda_
    if klass != outer_dotted:
        name = klass[len(outer_dotted) + 1:] + "." + name
    return name, lambda_


def ranges(lines):
    """[3, 4, 5, 9, 10] -> '3-10' as a span from first to last code line."""
    return "%d-%d" % (min(lines), max(lines)) if lines else "-"


def measure(src, classes, subject, non_collab):
    """Lines of the output for one file, or [('missing', reason)]."""
    if not subject.endswith(".java"):
        return [("missing", "kein Backend-Quelltext")]
    stem = subject[:-len(".java")]
    class_file = os.path.join(classes, stem + ".class")
    source_file = os.path.join(src, subject)
    if not os.path.isfile(class_file):
        return [("missing", "class_missing")]
    if not os.path.isfile(source_file):
        return [("missing", "source_missing")]
    if os.path.getmtime(source_file) > os.path.getmtime(class_file):
        # Source newer than the build: lines and accesses would belong to a different state.
        return [("missing", "stale_classes")]
    inner = sorted(glob.glob(os.path.join(classes, glob.escape(stem) + "$*.class")))
    text = javap([class_file] + inner)
    if text is None:
        return [("missing", "javap_failed")]
    outer_internal = stem
    outer_dotted = stem.replace("/", ".")
    fields, methods = parse(text, outer_internal)
    source = open(source_file, encoding="utf-8", errors="replace").read()
    collaborators = sorted(
        name for name, (mods, declared) in fields.items()
        if "static" not in mods.split() and "final" in mods.split() and "private" in mods.split()
        and not non_collab.match(simple_type(declared)) and injected_without_initializer(source, name))

    merged = {}  # label -> [set(collaborators), [lines]]
    used_anywhere = set()
    for (klass, raw), (accessed, lines) in methods.items():
        name, lambda_ = label(klass, raw, outer_dotted)
        if name is None:
            if lambda_:
                # A lambda from the constructor runs later — its field is more than just wired up.
                used_anywhere |= accessed
            continue
        entry = merged.setdefault(name, [set(), []])
        entry[0] |= accessed & set(collaborators)
        entry[1] += lines
        used_anywhere |= accessed
    with_collab = {n: e for n, e in merged.items() if e[0]}

    # Groups: connected components over shared collaborators (union-find).
    parent = {n: n for n in with_collab}

    def find(n):
        while parent[n] != n:
            parent[n] = parent[parent[n]]
            n = parent[n]
        return n
    owner_of = {}
    for name in sorted(with_collab):
        for collab in sorted(with_collab[name][0]):
            if collab in owner_of:
                parent[find(name)] = find(owner_of[collab])
            else:
                owner_of[collab] = name
    groups = {}
    for name in with_collab:
        groups.setdefault(find(name), []).append(name)

    def first_line(name):
        return min(merged[name][1]) if merged[name][1] else 10 ** 9
    ordered = sorted(groups.values(), key=lambda g: (-len(g), min(first_line(n) for n in g)))

    rows = [("class", "kollaborateure=%d" % len(collaborators), "methoden=%d" % len(merged),
             "mit_kollaborateur=%d" % len(with_collab), "gruppen=%d" % len(ordered))]
    for name in sorted(with_collab, key=lambda n: (first_line(n), n)):
        rows.append(("method", name, ranges(merged[name][1]), ",".join(sorted(merged[name][0]))))
    for number, members in enumerate(ordered, 1):
        members = sorted(members, key=lambda n: (first_line(n), n))
        used = sorted(set().union(*(with_collab[n][0] for n in members)))
        rows.append(("group", str(number), ",".join(members), ",".join(used)))
    for collab in collaborators:
        if collab not in used_anywhere:
            rows.append(("nur_konstruktor", collab))
    return rows


def main(argv):
    if len(argv) < 3:
        sys.stderr.write("Aufruf: arch-seams.py <src/main/java> <classes-dir> <subject>...\n")
        return 2
    src, classes, subjects = argv[1], argv[2], argv[3:]
    out = ["## seams",
           "# Je Backend-Kandidat aus ## review_candidates: welche Methode welche Kollaborateure (injizierte Beans",
           "# wie in ## files) benutzt, gemessen im Bytecode (javap -c -l -p). Lambdas zaehlen zur umschliessenden",
           "# Methode, Ueberladungen sind zusammengefasst, Konstruktoren fehlen; zeilen = erste bis letzte Codezeile.",
           "# group = Methoden, die ueber gemeinsame Kollaborateure zusammenhaengen; mehrere Gruppen sind ein",
           "# Schnitt-Kandidat. nur_konstruktor = injiziert, ausserhalb des Konstruktors nie gelesen.",
           "# class\\tdatei\\tkollaborateure=\\tmethoden=\\tmit_kollaborateur=\\tgruppen=",
           "# method\\tdatei\\tmethode\\tzeilen\\tkollaborateure   group\\tdatei\\tnr\\tmethoden\\tkollaborateure",
           "# nur_konstruktor\\tdatei\\tkollaborateur   missing\\tdatei\\tgrund"]
    non_collab = non_collab_pattern()
    if non_collab is None:
        state = "exclusions_missing"
    elif shutil.which("javap") is None:
        state = "javap_missing"
    elif not os.path.isdir(classes):
        state = "no_classes"
    else:
        state = "ok"
    out.append("state\t" + state)
    backend = sorted(s for s in subjects if s.endswith(".java"))
    for subject in backend:
        rows = measure(src, classes, subject, non_collab) if state == "ok" else [("missing", state)]
        out += ["\t".join((row[0], subject) + tuple(row[1:])) for row in rows]
    if not backend:
        out.append("# keine Backend-Kandidaten")
    sys.stdout.write("\n".join(out) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
