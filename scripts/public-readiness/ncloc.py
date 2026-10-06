#!/usr/bin/env python3
"""ncloc.py — lines without blank, comment and import lines (NCLOC) per source file.

The architecture review (TODO b8c06b94) no longer measures size in raw lines: about a
third of a typical backend file is blank lines, Javadoc and imports, and every
extension pushed files over a hard line limit. What is counted here is what carries logic.

Rules (Java, TS, TSX):
  - blank line: whitespace only                              -> does not count
  - comment line: no character outside of comments           -> does not count
    (//, /* ... */, Javadoc, in TSX also {/* ... */} alone on the line;
    code with a trailing comment counts)
  - import line: Java `import …;` (also static), TS/TSX a statement `import … from '…'`
    or `import '…'` at the start of the line, for multi-line import lists all their lines
                                                             -> does not count
  - everything else counts, also `package`, annotations and closing braces.

Strings, character literals, Java text blocks and TS template strings (with ${…}) are
skipped so that a "//" in a URL does not begin a comment. In TS/TSX a "/"
after an operator, an opening bracket, a comma or return/typeof/… counts as the start of a
regex literal (as in the JS tokenizer); its content — e.g. a backtick in /[#*_`>]/g — opens
neither string nor comment. Known limits: a regex literal across several lines does not exist in
JS; a JSX text that begins with "//" or "/*" is read as a comment, as is a
regex directly after ")" (e.g. `if (x) /re/.test(y)`), which counts as a division. Such places
shift the number by at most the affected lines.

Usage:  ncloc.py <base directory>   reads paths (relative to the base) line by line from stdin
Output: ncloc<TAB>lines<TAB>path per file, in input order. lines counts like
         SourceSizeRatchetTest (Files.readAllLines(..).size() == len(text.splitlines())).
"""
import os
import re
import sys

JAVA_IMPORT = re.compile(r"^\s*import\s+(static\s+)?[\w.]+(\.\*)?\s*;")
# A JSX comment {/* … */} alone on the line: the braces are syntax, the line a comment.
JSX_COMMENT_LINE = re.compile(r"^\s*\{\s*/\*.*\*/\s*\}\s*$")
TS_IMPORT_START = re.compile(r"""^\s*import(\s+type)?(\s|\{|\*|['"])""")


REGEX_BEFORE = set("(,=:[!&|?{};+-*%<>~^")
REGEX_AFTER_WORD = re.compile(r"(?:^|[^\w$])(return|typeof|case|do|else|in|of|void|yield|await|delete)$")


def regex_allowed(recent):
    """True if a '/' at this position begins a regex literal and is not a division.

    Decides like a JS tokenizer after the last significant character: after an operator,
    an opening bracket, a comma or a keyword like return comes an
    expression, i.e. a regex; after an identifier, a number, ')' or ']' a division.
    """
    tail = recent.rstrip()
    if not tail:
        return True
    return tail[-1] in REGEX_BEFORE or bool(REGEX_AFTER_WORD.search(tail))


def skip_regex(line, start):
    """Index after the regex literal starting at line[start] == '/'; None if it does not end in the line."""
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
    """Per line True if it has a non-whitespace character outside of comments."""
    lines = text.split("\n")
    mask = [False] * len(lines)
    state = "code"          # code | block | string
    quote = ""              # active string delimiter: ' " ` or """
    brace_stack = []        # per open ${ in template strings the brace depth
    depth = 0
    recent = ""             # last seen code (strings as "), for regex versus division
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
        # A simple string ends at the latest at the end of the line (Java/TS have no
        # multi-line '…'/"…"); only text blocks and template strings continue.
        if state == "string" and quote in ("'", '"'):
            state = "code"
        recent = (recent + " ")[-40:]
        mask[index] = has_code and not (template_strings and JSX_COMMENT_LINE.match(line))
    return lines, mask


def import_lines(lines, kind):
    """Indices of the lines that belong to an import statement."""
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
            # An import statement ends at the line with the module specifier ('…' / "…").
            while index < len(lines) and not re.search(r"""(from\s*|^\s*import\s*)['"][^'"]+['"]""", lines[index]):
                index += 1
                if index - start > 200:  # no specifier: not an import but something else
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
