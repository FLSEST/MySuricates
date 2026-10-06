"""arch_tools.py — reads what the analysis tools of the architecture review report (TODO 67128699).

The review runs PMD from the image automation-agent-analysis in the shell block's tool mode:

  cpd   PMD's copy-paste detector, raw output in CSV (`pmd cpd --format csv`): one row per duplication,
        "lines,tokens,occurrences,line,file,line,file,...". CPD has no SARIF renderer, hence the raw mode.
        One run per language (java, typescript); CPD cannot read .tsx at all, so React components stay
        unmeasured — the caller says so instead of reporting them duplicate-free.
  pmd   `pmd check` with scripts/public-readiness/pmd-architecture.xml, SARIF; the platform turns it into a
        fact sheet with "TREFFER <prefix>: <path>:<line> <SEVERITY> <rule> – <text>" lines (at most 200).

  jscpd duplicates in TypeScript including .tsx (TODO c608cdda), SARIF, so the fact sheet carries one
        "TREFFER jscpd: <path>:<line> … – Duplicated code block (<n> tokens), duplicated at [<src path>:<line>](0)"
        per clone; the other place is relative to the scanned source root (ai-automation-frontend/src).
  lizard complexity per function in TypeScript/TSX, raw output of `lizard -w`: one clang-style warning
        "<path>:<line>: warning: <function> has <n> NLOC, <n> CCN, ..." per function over the threshold.

Paths come as /workspace/<repo path> (CPD, lizard) or as repo paths (fact sheet). Both map to the subjects of the
architecture review: com/automation/... for the backend, frontend/src/... for the frontend; anything else
(tests, scripts) has no subject and is dropped.
"""
import re

BACKEND_SRC = "ai-automation-backend/src/main/java/"
FRONTEND_SRC = "ai-automation-frontend/src/"

CPD_HEADER = "lines,tokens,occurrences"
CPD_ROW = re.compile(r"^(\d+),(\d+),(\d+),(.+)$")
HIT = re.compile(r"^TREFFER [\w.-]+: (\S+):(\d+) (\S+) (\S+) – (.*)$")
JSCPD_MESSAGE = re.compile(r"\((\d+) tokens\), duplicated at \[([^\]:]+):(\d+)\]")
LIZARD_WARNING = re.compile(r"^(\S+?):(\d+): warning: (.+)$")
FRONTEND_TEST = re.compile(r"(^|/)(__tests__|__mocks__|test)/|\.(test|spec)\.[jt]sx?$")


def subject_of(path):
    """Repo or container path → review subject, or None for files outside the measured sources."""
    path = path.strip()
    if path.startswith("file://"):
        path = path[len("file://"):]
    if path.startswith("/workspace/"):
        path = path[len("/workspace/"):]
    if path.startswith(BACKEND_SRC):
        return path[len(BACKEND_SRC):]
    if path.startswith(FRONTEND_SRC):
        rest = path[len(FRONTEND_SRC):]
        return None if FRONTEND_TEST.search(rest) else "frontend/src/" + rest
    return None


def parse_cpd(text):
    """(measured, [(lines, tokens, [(subject, line), ...])]) — measured only if the CSV header is there."""
    measured, duplicates = False, []
    for raw in (text or "").splitlines():
        line = raw.strip()
        if line == CPD_HEADER:
            measured = True
            continue
        match = CPD_ROW.match(line)
        if not match:
            continue
        fields = match.group(4).split(",")
        places = []
        for i in range(0, len(fields) - 1, 2):
            subject = subject_of(fields[i + 1])
            if subject and fields[i].isdigit():
                places.append((subject, int(fields[i])))
        if len(places) >= 2:
            duplicates.append((int(match.group(1)), int(match.group(2)), places))
    return measured, duplicates


def parse_pmd(text, prefix=None):
    """(state, {subject: [(line, rule, message)]}) from the SARIF fact sheet of the pmd block.

    state: ok | truncated (more than 200 hits, the rest is not listed) | not_measured (no FAKT line,
    or the platform reported NICHT GEMESSEN). Without a prefix it is read from the sheet's header
    ("## FAKTENBLATT <prefix>"): the block was renamed pmd -> pmd_design in Review 4.10.0, because the
    platform's evidence check ignores names shorter than 4 characters."""
    text = text or ""
    if prefix is None:
        header = re.search(r"^## FAKTENBLATT (\S+)", text, re.M)
        prefix = header.group(1) if header else "pmd"
    if ("NICHT GEMESSEN: %s_findings" % prefix) in text or ("FAKT %s_findings:" % prefix) not in text:
        return "not_measured", {}
    state = "truncated" if re.search(r"^TREFFER %s: \(\+\d+ " % re.escape(prefix), text, re.M) else "ok"
    hits = {}
    for raw in text.splitlines():
        match = HIT.match(raw.strip())
        if not match:
            continue
        subject = subject_of(match.group(1))
        if subject:
            hits.setdefault(subject, []).append((int(match.group(2)), match.group(4), match.group(5).strip()))
    for subject in hits:
        hits[subject].sort()
    return state, hits


def parse_jscpd(text, prefix="jscpd"):
    """(measured, [(lines, tokens, [(subject, line), ...])]) from the SARIF fact sheet of the jscpd block.

    lines is 0: the fact sheet keeps only the start line of a clone, jscpd's message names its tokens."""
    text = text or ""
    if ("NICHT GEMESSEN: %s_findings" % prefix) in text or ("FAKT %s_findings:" % prefix) not in text:
        return False, []
    duplicates = []
    for raw in text.splitlines():
        hit = HIT.match(raw.strip())
        message = JSCPD_MESSAGE.search(raw)
        if not hit or not message:
            continue
        first = subject_of(hit.group(1))
        second = subject_of(FRONTEND_SRC + message.group(2))
        if first and second:
            duplicates.append((0, int(message.group(1)), [(first, int(hit.group(2))), (second, int(message.group(3)))]))
    return True, duplicates


def parse_lizard(text):
    """(measured, {subject: [(line, "CCN", text)]}) from `lizard -w`. Measured only if every non-empty line is
    a warning: an error message (image missing, crash) is a gap, not a clean result; no lines at all means
    no function over the threshold."""
    if text is None:
        return False, {}
    hits = {}
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        warning = LIZARD_WARNING.match(line)
        if not warning:
            return False, {}
        subject = subject_of(warning.group(1))
        if subject:
            hits.setdefault(subject, []).append((int(warning.group(2)), "CCN", warning.group(3).strip()))
    for subject in hits:
        hits[subject].sort()
    return True, hits
