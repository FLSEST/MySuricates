#!/usr/bin/env python3
"""Keep code comments in English (TODO 9468432d).

Code comments and test descriptions are written in English (CLAUDE.md -> "Language in code").
The existing code base is still being translated in slices; this tool keeps that honest:

  count  [--json] [PATH...]     German comment lines per file (heuristic, see is_german)
  ratchet [--update]            fail if a file has more German comment lines than its ceiling in
                                german-comments-baseline.txt (new files: ceiling 0); --update
                                lowers ceilings to the current counts and drops files that reached 0
  check-diff [BASE] [PATH...]   verify that the working tree differs from BASE (default HEAD) only in
                                comments, test descriptions (describe/it/test, @DisplayName) and Python
                                docstrings - the guard for translation slices

"German" is a heuristic: an umlaut/sharp s, or at least two distinct German function words. Comments
quoting German UI text therefore count too; such files keep their ceiling in the baseline.
"""
import argparse
import io
import json
import re
import subprocess
import sys
import tokenize
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASELINE = Path(__file__).resolve().parent / 'german-comments-baseline.txt'

C_LIKE = ('.java', '.ts', '.tsx', '.js', '.mjs', '.cjs')
HASH_LIKE = ('.sh', '.yml', '.yaml')
SUFFIXES = C_LIKE + HASH_LIKE + ('.py',)
# Existing Flyway migrations must never change (their comments are part of the checksum).
EXCLUDED = re.compile(r'(^|/)db/migration/|(^|/)node_modules/')

UMLAUT = re.compile(r'[äöüÄÖÜß]')
GERMAN_WORDS = frozenset('''
    der das und nicht ist sind wird werden wurde dass für bei auf ein eine einen einem einer kein keine
    keinen sonst statt damit weil wenn auch nur noch schon hier dann oder aber über zum zur vom beim
    muss darf kann soll sollte nach durch ohne gegen sich wir ich es sie den dem des aus wie was
    diese dieser dieses jede jeder jedes alle immer nie hat haben gibt läuft steht bleibt
'''.split())
WORD = re.compile(r"[A-Za-zÄÖÜäöüß]+")


def is_german(text):
    if UMLAUT.search(text):
        return True
    words = {w.lower() for w in WORD.findall(text)}
    return len(words & GERMAN_WORDS) >= 2


# --------------------------------------------------------------------------------------------
# Lexing: split a source into code (comments removed, whitespace normalised) and comment lines.
# --------------------------------------------------------------------------------------------

def _skip_string(src, i, quote):
    """Return the index after the string literal starting at src[i] (an opening quote)."""
    n = len(src)
    if quote == '"' and src.startswith('"""', i):  # Java text block
        j = src.find('"""', i + 3)
        while j != -1 and src[j - 1] == '\\':
            j = src.find('"""', j + 1)
        return n if j == -1 else j + 3
    j = i + 1
    while j < n:
        c = src[j]
        if c == '\\':
            j += 2
            continue
        if c == quote:
            return j + 1
        if c == '\n' and quote != '`':
            return j  # unterminated (or a JSX text apostrophe) - stop at the line end
        if quote == '`' and c == '$' and j + 1 < n and src[j + 1] == '{':
            j = _skip_template_expr(src, j + 2)
            continue
        j += 1
    return n


def _skip_template_expr(src, i):
    depth = 1
    n = len(src)
    while i < n and depth:
        c = src[i]
        if c in '"\'`':
            i = _skip_string(src, i, c)
            continue
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
        i += 1
    return i


_REGEX_PREV = set('(,=:[!&|?{};+-*%<>~^') | {''}


def lex_c_like(src, js=False):
    """Return (code, comments) for Java/TS/JS. comments = [(line, text)] per physical line."""
    code = []
    comments = []
    i, n, line = 0, len(src), 1
    last_sig = ''
    while i < n:
        c = src[i]
        if c == '/' and src.startswith('//', i):
            j = src.find('\n', i)
            j = n if j == -1 else j
            comments.append((line, src[i + 2:j]))
            code.append(' ')
            i = j
            continue
        if c == '/' and src.startswith('/*', i):
            j = src.find('*/', i + 2)
            j = n if j == -1 else j + 2
            for k, text in enumerate(src[i + 2:j - 2].split('\n')):
                comments.append((line + k, text))
            line += src.count('\n', i, j)
            code.append(' ')
            i = j
            continue
        if c in '"\'' or (js and c == '`'):
            j = _skip_string(src, i, c)
            code.append(src[i:j])
            line += src.count('\n', i, j)
            last_sig = c
            i = j
            continue
        if js and c == '/' and last_sig in _REGEX_PREV:
            # regex literal: skip to the closing slash on the same line
            j = i + 1
            in_class = False
            while j < n and src[j] != '\n':
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == '[':
                    in_class = True
                elif src[j] == ']':
                    in_class = False
                elif src[j] == '/' and not in_class:
                    break
                j += 1
            code.append(src[i:j + 1])
            last_sig = '/'
            i = j + 1
            continue
        if c == '\n':
            line += 1
        if c.isspace():
            code.append(' ')
        else:
            code.append(c)
            if c.isalnum() or c == '_' or c == '$':
                # keywords before a regex (return /x/) are rare enough to ignore
                last_sig = 'a'
            else:
                last_sig = c
        i += 1
    return re.sub(r'\s+', ' ', ''.join(code)).strip(), comments


HEREDOC = re.compile(r"<<-?\s*(['\"]?)([A-Za-z_][A-Za-z0-9_]*)\1")


def _strip_hash_comment(line):
    """Return (code, comment) of one shell/YAML line; '#' counts only outside quotes and after a blank."""
    quote = None
    i = 0
    while i < len(line):
        c = line[i]
        if quote:
            if c == '\\' and quote == '"':
                i += 2
                continue
            if c == quote:
                quote = None
        elif c in '"\'':
            quote = c
        elif c == '\\':
            i += 2
            continue
        elif c == '#' and (i == 0 or line[i - 1] in ' \t'):
            return line[:i], line[i + 1:]
        i += 1
    return line, None


def lex_hash(src, shell):
    code, comments = [], []
    heredoc_end = None
    for no, raw in enumerate(src.split('\n'), 1):
        if heredoc_end is not None:
            code.append(raw)  # heredoc bodies are data, never comments
            if raw.strip() == heredoc_end:
                heredoc_end = None
            continue
        if no == 1 and raw.startswith('#!'):
            code.append(raw)
            continue
        part, comment = _strip_hash_comment(raw)
        if comment is not None:
            comments.append((no, comment))
        if part.strip():
            code.append(' '.join(part.split()))
        if shell:
            m = HEREDOC.search(part)
            if m:
                heredoc_end = m.group(2)
    return '\n'.join(code), comments


def lex_python(src):
    code, comments = [], []
    prev = tokenize.NEWLINE
    try:
        tokens = list(tokenize.generate_tokens(io.StringIO(src).readline))
    except (tokenize.TokenError, SyntaxError):
        return lex_hash(src, shell=False)
    for idx, tok in enumerate(tokens):
        if tok.type == tokenize.COMMENT:
            if tok.start[0] == 1 and tok.string.startswith('#!'):
                code.append(tok.string)
            else:
                comments.append((tok.start[0], tok.string[1:]))
            continue
        if tok.type in (tokenize.NL, tokenize.ENCODING):
            continue
        if tok.type == tokenize.STRING and prev in (tokenize.NEWLINE, tokenize.INDENT, tokenize.DEDENT):
            nxt = tokens[idx + 1].type if idx + 1 < len(tokens) else tokenize.ENDMARKER
            if nxt in (tokenize.NEWLINE, tokenize.ENDMARKER):  # docstring / bare string statement
                for k, text in enumerate(tok.string.split('\n')):
                    comments.append((tok.start[0] + k, text))
                code.append('<DOCSTRING>')
                prev = tok.type
                continue
        code.append(tok.string)
        prev = tok.type
    return ' '.join(code), comments


TEST_DESC = re.compile(r"""\b(describe|it|test)((?:\.\w+)*)\(\s*(['"`])((?:\\.|(?!\3).)*)\3""")
DISPLAY_NAME = re.compile(r'@DisplayName\(\s*"((?:\\.|[^"\\])*)"')


def lex(path, src):
    """Return (code, comments) where comments also include test descriptions."""
    suffix = path.suffix
    if suffix in C_LIKE:
        js = suffix != '.java'
        code, comments = lex_c_like(src, js=js)
        desc = TEST_DESC if js else DISPLAY_NAME
        for no, raw in enumerate(src.split('\n'), 1):
            for m in desc.finditer(raw):
                comments.append((no, m.group(m.lastindex)))
        return code, comments
    if suffix == '.py':
        return lex_python(src)
    return lex_hash(src, shell=suffix == '.sh')


def german_lines(path, src):
    _, comments = lex(path, src)
    return sorted({no for no, text in comments if is_german(text)})


# --------------------------------------------------------------------------------------------
# count / ratchet
# --------------------------------------------------------------------------------------------

def tracked_files(paths=None):
    out = subprocess.run(['git', '-C', str(ROOT), 'ls-files', '-z', '--', *(paths or [])],
                         capture_output=True, text=True, check=True).stdout
    for name in sorted(filter(None, out.split('\0'))):
        p = Path(name)
        if p.suffix in SUFFIXES and not EXCLUDED.search(name) and (ROOT / p).is_file():
            yield name


def count_all(paths=None):
    counts = {}
    for name in tracked_files(paths):
        src = (ROOT / name).read_text(encoding='utf-8', errors='replace')
        n = len(german_lines(Path(name), src))
        if n:
            counts[name] = n
    return counts


def read_baseline():
    ceilings = {}
    if BASELINE.exists():
        for raw in BASELINE.read_text(encoding='utf-8').splitlines():
            raw = raw.strip()
            if raw and not raw.startswith('#'):
                ceiling, name = raw.split(None, 1)
                ceilings[name] = int(ceiling)
    return ceilings


BASELINE_HEADER = '''# German comment lines per file (ceiling) - scripts/tools/german_comments.py ratchet.
# Only ever lowered: run "german_comments.py ratchet --update" after translating comments.
# Never raise a ceiling - write the comment in English instead (TODO 9468432d).
'''


def write_baseline(ceilings):
    lines = ['%d %s' % (n, name) for name, n in sorted(ceilings.items()) if n > 0]
    BASELINE.write_text(BASELINE_HEADER + '\n'.join(lines) + ('\n' if lines else ''), encoding='utf-8')


def ratchet(update):
    counts = count_all()
    ceilings = read_baseline()
    problems, lowered = [], 0
    for name, n in sorted(counts.items()):
        ceiling = ceilings.get(name, 0)
        if n > ceiling:
            src = (ROOT / name).read_text(encoding='utf-8', errors='replace')
            where = ', '.join(str(no) for no in german_lines(Path(name), src)[:8])
            problems.append('%s: %d German comment lines (ceiling %d), lines %s' % (name, n, ceiling, where))
    for name, ceiling in ceilings.items():
        if counts.get(name, 0) < ceiling:
            lowered += 1
    if update:
        write_baseline({name: min(ceiling, counts.get(name, 0)) for name, ceiling in ceilings.items()})
        print('baseline: %d ceilings lowered, %d files left' % (lowered, sum(1 for v in counts.values() if v)))
    elif lowered:
        print('note: %d files are below their ceiling - run "%s ratchet --update" to tighten'
              % (lowered, Path(__file__).name))
    if problems:
        print('German code comments added (write them in English, CLAUDE.md -> Language in code):')
        print('\n'.join('  ' + p for p in problems))
        return 1
    return 0


# --------------------------------------------------------------------------------------------
# check-diff
# --------------------------------------------------------------------------------------------

def _git_show(base, name):
    r = subprocess.run(['git', '-C', str(ROOT), 'show', '%s:%s' % (base, name)], capture_output=True)
    return None if r.returncode else r.stdout.decode('utf-8', errors='replace')


def _strip_descriptions(path, code):
    if path.suffix in C_LIKE:
        pattern = TEST_DESC if path.suffix != '.java' else DISPLAY_NAME
        return pattern.sub(lambda m: m.group(0).replace(m.group(m.lastindex), '<DESC>'), code)
    return code


TS_TOKENS_JS = r'''
const ts = require(process.env.TS_MODULE);
const files = JSON.parse(require('fs').readFileSync(0, 'utf8'));
const DESC = /^(describe|it|test)(\.\w+)*$/;
const out = files.map(([name, text]) => {
  const kind = name.endsWith('.tsx') ? ts.ScriptKind.TSX : name.endsWith('.ts') ? ts.ScriptKind.TS : ts.ScriptKind.JS;
  const sf = ts.createSourceFile(name, text, ts.ScriptTarget.Latest, true, kind);
  const toks = [];
  const walk = (node) => {
    const kids = node.getChildren(sf);
    if (kids.length === 0) {
      const p = node.parent;
      if ((ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) && p && ts.isCallExpression(p)
          && p.arguments[0] === node && DESC.test(p.expression.getText(sf))) {
        toks.push('<DESC>');
      } else if (node.kind === ts.SyntaxKind.JsxText) {
        const t = node.getText(sf).replace(/\s+/g, ' ').trim();
        if (t) toks.push(t);
      } else if (node.kind !== ts.SyntaxKind.EndOfFileToken) {
        toks.push(node.getText(sf));
      }
      return;
    }
    kids.forEach(walk);
  };
  walk(sf);
  return toks.join('\u0001');
});
process.stdout.write(JSON.stringify(out));
'''


def _typescript_module():
    common = subprocess.run(['git', '-C', str(ROOT), 'rev-parse', '--path-format=absolute', '--git-common-dir'],
                            capture_output=True, text=True).stdout.strip()
    for base in (ROOT, Path(common).parent if common else None):
        if base and (base / 'ai-automation-frontend/node_modules/typescript/lib/typescript.js').is_file():
            return str(base / 'ai-automation-frontend/node_modules/typescript')
    return None


def _ts_token_streams(pairs):
    """Exact comment-free token streams via the TypeScript parser; None if node/typescript are missing."""
    module = _typescript_module()
    if not module or not pairs:
        return None
    import os
    r = subprocess.run(['node', '-e', TS_TOKENS_JS], input=json.dumps(pairs), capture_output=True, text=True,
                       env={**os.environ, 'TS_MODULE': module})
    if r.returncode:
        raise SystemExit('TypeScript token check failed: ' + r.stderr[-2000:])
    return json.loads(r.stdout)


def check_diff(base, paths=()):
    changed = subprocess.run(['git', '-C', str(ROOT), 'diff', '--name-only', base, '--', *paths],
                             capture_output=True, text=True, check=True).stdout.split()
    bad = []
    checked = 0
    ts_pairs = []
    for name in changed:
        path = Path(name)
        old = _git_show(base, name)
        if not (ROOT / name).is_file() or old is None:
            bad.append('%s: added or deleted' % name)
            continue
        if path.suffix not in SUFFIXES:
            bad.append('%s: not a checked source type - review by hand' % name)
            continue
        if EXCLUDED.search(name):
            bad.append('%s: must not change' % name)
            continue
        new = (ROOT / name).read_text(encoding='utf-8', errors='replace')
        checked += 1
        if path.suffix in C_LIKE and path.suffix != '.java':
            ts_pairs.append((name, old, new))
        old_code = _strip_descriptions(path, lex(path, old)[0])
        new_code = _strip_descriptions(path, lex(path, new)[0])
        if old_code != new_code:
            a, b = old_code, new_code
            k = next((i for i in range(min(len(a), len(b))) if a[i] != b[i]), min(len(a), len(b)))
            bad.append('%s: code changed near: %r -> %r' % (name, a[max(0, k - 60):k + 60], b[max(0, k - 60):k + 60]))
        old_lines, new_lines = old.count('\n'), new.count('\n')
        if new_lines > old_lines:
            bad.append('%s: grew from %d to %d lines' % (name, old_lines, new_lines))
    streams = _ts_token_streams([[n, t] for n, o, nw in ts_pairs for t in (o, nw)])
    if streams is None and ts_pairs:
        print('note: node/typescript not found - TS files compared with the approximate lexer only')
    elif streams:
        for k, (name, _, _) in enumerate(ts_pairs):
            if streams[2 * k] != streams[2 * k + 1]:
                a, b = streams[2 * k].split('\u0001'), streams[2 * k + 1].split('\u0001')
                i = next((i for i in range(min(len(a), len(b))) if a[i] != b[i]), min(len(a), len(b)))
                bad.append('%s: TypeScript tokens differ near: %r -> %r' % (name, a[i:i + 6], b[i:i + 6]))
    print('check-diff: %d files compared against %s' % (checked, base))
    for b in bad:
        print('  ' + b)
    return 1 if bad else 0


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest='cmd', required=True)
    c = sub.add_parser('count')
    c.add_argument('--json', action='store_true')
    c.add_argument('paths', nargs='*')
    r = sub.add_parser('ratchet')
    r.add_argument('--update', action='store_true')
    d = sub.add_parser('check-diff')
    d.add_argument('base', nargs='?', default='HEAD')
    d.add_argument('paths', nargs='*', help='limit the check to these paths')
    args = ap.parse_args(argv)
    if args.cmd == 'count':
        counts = count_all(args.paths)
        if args.json:
            print(json.dumps(counts, indent=1, sort_keys=True))
        else:
            for name, n in sorted(counts.items(), key=lambda kv: -kv[1]):
                print('%5d %s' % (n, name))
            print('%5d total in %d files' % (sum(counts.values()), len(counts)))
        return 0
    if args.cmd == 'ratchet':
        return ratchet(args.update)
    return check_diff(args.base, args.paths)


if __name__ == '__main__':
    sys.exit(main())
