"""arch_tools.py — reads what the analysis tools of the architecture review report (TODO 67128699).

The review runs PMD from the image automation-agent-analysis in the shell block's tool mode:

  cpd   PMD's copy-paste detector, raw output in CSV (`pmd cpd --format csv`): one row per duplication,
        "lines,tokens,occurrences,line,file,line,file,...". CPD has no SARIF renderer, hence the raw mode.
        One run per language (java, typescript); CPD cannot read .tsx at all, so React components stay
        unmeasured — the caller says so instead of reporting them duplicate-free.
  pmd   `pmd check` with scripts/public-readiness/pmd-architecture.xml, SARIF; the platform turns it into a
        fact sheet with "TREFFER <prefix>: <path>:<line> <SEVERITY> <rule> – <text>" lines (at most 200).

Paths come as /workspace/<repo path> (CPD) or as repo paths (fact sheet). Both map to the subjects of the
architecture review: com/automation/... for the backend, frontend/src/... for the frontend; anything else
(tests, scripts) has no subject and is dropped.
"""
import re

BACKEND_SRC = "ai-automation-backend/src/main/java/"
FRONTEND_SRC = "ai-automation-frontend/src/"

CPD_HEADER = "lines,tokens,occurrences"
CPD_ROW = re.compile(r"^(\d+),(\d+),(\d+),(.+)$")
HIT = re.compile(r"^TREFFER [\w.-]+: (\S+):(\d+) (\S+) (\S+) – (.*)$")
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


def parse_pmd(text, prefix="pmd"):
    """(state, {subject: [(line, rule, message)]}) from the SARIF fact sheet of the pmd block.

    state: ok | truncated (more than 200 hits, the rest is not listed) | not_measured (no FAKT line,
    or the platform reported NICHT GEMESSEN)."""
    text = text or ""
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
