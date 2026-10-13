#!/usr/bin/env python3
"""Commit messages for the history rewrite (TODO 0bf5eed4): TODO trailer, translation batches, checks.

The rewrite moves the TODO bracket at the end of a subject into a trailer (deterministic, step b)
and translates German messages into English (one AI call per batch, step c). Everything except the
translation itself happens here, so it is testable and costs no tokens:

  todo-index  --out FILE                     fetch all TODOs (id, title) from the own instance; run by
                                             hand, never from a workflow block (no API access there)
  extract     --repo R --state S --run ID    collect the messages still missing from the map, apply
              [--sample N] [--batch-size B]  the trailer step, put English-only ones straight into the
              [--redo-german]                map and write the German ones as translation batches;
                                             --redo-german also takes map entries that still read German
  check       --state S --run ID --batch NN  verify one batch answer (stdin or --answer FILE) against
              [--answer FILE] [--stored]     its source; accepted messages go into the map, the rest
                                             into rejects.jsonl with the reason. The raw answer is
                                             kept, so --stored re-checks it after a rule change
  report      --state S --run ID             summary plus a side-by-side review page (review.html)
  callback    --map FILE [--replacements F]  body for `git filter-repo --commit-callback`: message from the
                                             map, then the replacements (filter-repo expression syntax)
  map-missing --repo R --map FILE            number of commits without a map entry
  german-left --repo R                       number of messages that still read German
  patterns-left --repo R --replacements F    number of replacement rules that still match a message

State lives outside the repository (`--state`, e.g. ~/.mysuricates/history-rewrite): `map.jsonl`
(archive hash -> final message) is the cache, so the final run translates only what is new.
Standard library only.
"""
import argparse
import html
import json
import os
import re
import subprocess
import sys
import urllib.request
from pathlib import Path

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'tools'))
from german_comments import GERMAN_WORDS, UMLAUT, WORD  # noqa: E402

SHORT_ID = r'[0-9a-f]{8}'
# One TODO reference inside the final bracket: "TODO 1234abcd", "1234abcd" or "1234abcd Title".
REF_START = re.compile(r'(?:^|,\s+)(?:TODO\s+)?(' + SHORT_ID + r')(?=\s|,|$)')
TRAILER_LINE = re.compile(r'^[A-Za-z][A-Za-z-]*: \S')
UUID = re.compile(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
EMAIL = re.compile(r'[\w.+-]+@[\w-]+\.[\w.-]+')
MAX_SUBJECT = 72


# ---------------------------------------------------------------------------------------------
# Message structure: subject, body, trailers
# ---------------------------------------------------------------------------------------------

def split_message(msg):
    """(subject, body, trailers): trailers are the last paragraph if every line is `Key: value`."""
    lines = msg.rstrip('\n').split('\n')
    subject = lines[0]
    rest = lines[1:]
    while rest and not rest[0].strip():
        rest = rest[1:]
    paragraphs = '\n'.join(rest).split('\n\n')
    trailers = []
    if paragraphs and paragraphs[-1].strip():
        last = [line for line in paragraphs[-1].split('\n') if line.strip()]
        if last and all(TRAILER_LINE.match(line) for line in last):
            trailers = last
            paragraphs = paragraphs[:-1]
    body = '\n\n'.join(p for p in paragraphs).strip('\n')
    return subject, body, trailers


def join_message(subject, body, trailers):
    parts = [subject]
    if body:
        parts.append(body)
    if trailers:
        parts.append('\n'.join(trailers))
    return '\n\n'.join(parts) + '\n'


def final_bracket(subject):
    """(start, inner) of a balanced bracket that ends the subject, or None."""
    s = subject.rstrip()
    if not s.endswith(')'):
        return None
    depth = 0
    for i in range(len(s) - 1, -1, -1):
        if s[i] == ')':
            depth += 1
        elif s[i] == '(':
            depth -= 1
            if depth == 0:
                return i, s[i + 1:-1]
    return None


def parse_refs(inner):
    """[(short_id, title or '')] if the bracket is a TODO reference list, else None."""
    starts = list(REF_START.finditer(inner))
    if not starts or starts[0].start() != 0:
        return None
    refs = []
    for n, m in enumerate(starts):
        end = starts[n + 1].start() if n + 1 < len(starts) else len(inner)
        title = inner[m.end():end].strip()
        refs.append((m.group(1), title))
    return refs


DROPPED_TRAILERS = ('Claude-Session:',)


def drop_private_trailers(msg):
    """Trailers that link to the author's private sessions do not go public (decided 2026-10-08)."""
    subject, body, trailers = split_message(msg)
    kept = [t for t in trailers if not t.startswith(DROPPED_TRAILERS)]
    return msg if kept == trailers else join_message(subject, body, kept)


def move_todo_bracket(msg, index):
    """Step b: the TODO bracket at the end of the subject becomes `Todo:` trailer lines.

    The full UUID and current title come from the index; a TODO that no longer exists keeps its
    short id and the title written in the bracket. Messages that already carry a `Todo:` trailer
    (new convention since 2026-10-06) are left alone.
    """
    subject, body, trailers = split_message(msg)
    if any(t.startswith('Todo: ') for t in trailers):
        return msg
    bracket = final_bracket(subject)
    refs = parse_refs(bracket[1]) if bracket else None
    if not refs:
        return msg
    new_subject = subject[:bracket[0]].rstrip()
    todo_lines = []
    for short, title in refs:
        full, known_title = index.get(short, (short, ''))
        title = known_title or title
        todo_lines.append(f'Todo: {full} ({title})' if title else f'Todo: {full}')
    # Todo before Co-Authored-By, like new commits write it.
    co = [t for t in trailers if t.startswith('Co-Authored-By:')]
    other = [t for t in trailers if not t.startswith('Co-Authored-By:')]
    return join_message(new_subject, body, other + todo_lines + co)


# ---------------------------------------------------------------------------------------------
# Language and preserved tokens
# ---------------------------------------------------------------------------------------------

QUOTED = re.compile(r'`[^`]*`|„[^“]*“|"[^"\n]*"')
# The comment ratchet judges single lines and wants two German function words. For the rewrite a
# German message that slips through stays German in the public history, while an English one sent
# to translation only costs tokens (it comes back unchanged) - so one unambiguous German word, a
# German word ending or a transliterated umlaut is enough here.
AMBIGUOUS = frozenset({'was', 'es', 'hat', 'die', 'den', 'an', 'sie', 'alle', 'also'})
STRONG_WORDS = (GERMAN_WORDS | frozenset('''
    mit im zu von bis jetzt mehr wieder neu neue neuen fehlt macht geht beide eigene eigenen
    sowie bzw bereits sondern kommt liest schreibt zeigt nimmt zerlegt gestrafft ersetzen
    ersetzt beschreiben aktualisiert hinzugefuegt entfernt behoben angepasst
'''.split())) - AMBIGUOUS
GERMAN_ENDING = re.compile(r'\b[a-zäöüß]{3,}(?:ungen|ung|keit|heit|lich|liche[nrs]?|iert|ieren|isch|ische[nrs]?)\b', re.I)
TRANSLITERATED = re.compile(r'\b(?:fuer|ueber|koennen|muessen|muesste|aender\w*|pruef\w*|laeuft|haelt|groesse\w*|'
                            r'waere|waeren|naechste\w*|moeglich\w*|zurueck\w*|ueberall|loesch\w*|schliess\w*)\b', re.I)


FILE_OR_PATH = re.compile(r'\S*[/.]\S*')
# Names that stay German in an English message and must not count as German text: protocol words
# in capitals ("NICHT GEMESSEN", "FAKT"), TODO references with their German title
# ("cc0eb3ba (Batch-Kette bauen)", as in the trailers) and the scope of the subject prefix
# ("docs(entwicklung): ", the doc's file name).
CAPS_TOKEN = re.compile(r'(?<![\w-])[A-ZÄÖÜ]{2,}(?:[- ][A-ZÄÖÜ]{2,})*(?![\w-])')
TODO_REFERENCE = re.compile(r'\b[0-9a-f]{8}(?:-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})?\b:? '
                            r'\((?:[^()]|\([^()]*\))*\)')
SUBJECT_PREFIX = re.compile(r'^(\w+(?:\([^)]*\))?!?: |Merge \S+: |Merge )')


def is_german_text(text):
    text = FILE_OR_PATH.sub(' ', QUOTED.sub(' ', CAPS_TOKEN.sub(' ', TODO_REFERENCE.sub(' ', text))))
    if UMLAUT.search(text) or TRANSLITERATED.search(text):
        return True
    words = {w.lower() for w in WORD.findall(text)}
    if words & STRONG_WORDS:
        return True
    return bool(GERMAN_ENDING.search(text))


def subject_text(subject):
    """The subject without its prefix ("fix(staging): ", "Merge <branch>: "), which stays as it is."""
    return SUBJECT_PREFIX.sub('', subject, count=1)


def needs_translation(msg):
    subject, body, _ = split_message(msg)
    return is_german_text(subject_text(subject) + '\n' + body)


TOKEN_PATTERNS = [
    re.compile(r'`[^`\n]+`'),                                   # code spans
    re.compile(r'https?://\S+'),                                # URLs
    re.compile(r'\b[0-9a-f]{7,40}\b(?=[^0-9a-f]|$)'),           # hashes / short ids (filtered below)
    re.compile(r'(?<![\w/.-])(?:[\w.-]+/)+[\w.-]+'),            # paths (filtered by is_path)
    re.compile(r'\b[\w-]+\.(?:java|tsx?|py|sh|md|ya?ml|json|sql|xml|html|css|js)\b'),
    re.compile(r'\b[a-z]+[A-Z]\w*\b|\b[A-Z][a-z]+[A-Z]\w*\b'),  # camelCase / CamelCase identifiers
    re.compile(r'\b\w+_\w+\b'),                                 # snake_case identifiers
    re.compile(r'\bV\d+\b|\b\d+(?:[.,]\d+)*\b'),                # numbers, migration names
]


PATH_ROOTS = frozenset('''
    scripts docs src lib config deploy workflow-pool templates reports api tests test frontend backend
    ai-automation-backend ai-automation-frontend .github .claude node_modules target playwright cypress
'''.split())
FILE_EXT = re.compile(r'\.[A-Za-z][A-Za-z0-9]{0,5}$')
GERMAN_COMPOUND_TAIL = re.compile(r'-[A-ZÄÖÜ][a-zäöüß]+$')


def is_path(token):
    """A slash token is a path if a segment has a file extension, it starts at a root, or it has
    lower-case segments below a known top-level directory. German writes word pairs and compounds
    with slashes ("Aktionen/Einstellungen", "Maven-/Spring-Logzeilen") - those are no paths."""
    segments = token.split('/')
    if token.startswith(('/', './', '../', '~/')):
        # "../-Sequenzen" is German for "../ sequences", no path
        return not any(seg.startswith('-') and GERMAN_COMPOUND_TAIL.search(seg) for seg in segments)
    # Enumerations, not paths: "Port/-Duser.home/DB", ".java/.ts/.tsx-Datei", "Maven-/Spring-Logzeilen".
    if any(seg.endswith('-') or seg.startswith('-') or not seg or GERMAN_COMPOUND_TAIL.search(seg)
           for seg in segments):
        return False
    if any(FILE_EXT.search(seg) for seg in segments):
        return True
    if not all(re.fullmatch(r'[a-z0-9._-]+', seg) for seg in segments):
        return False
    return segments[0] in PATH_ROOTS or len(segments) >= 3


def is_prose_span(span):
    """A backtick pair around plain German words is no code span: a span closed on the next line
    pairs its closing backtick with the next opening one ("<anbieter>` und `instance.sh ...")."""
    inner = span[1:-1]
    return bool(re.fullmatch(r'[\sA-Za-zÄÖÜäöüß]+', inner)) and is_german_text(inner)


def preserved_tokens(text):
    found = set()
    for pattern in TOKEN_PATTERNS:
        for m in pattern.finditer(text):
            token = m.group(0).rstrip('.,;:)')
            if pattern is TOKEN_PATTERNS[2] and not re.search(r'\d', token):
                continue  # a word like "deadbeef" without digits is no id
            if pattern is TOKEN_PATTERNS[3] and not is_path(token):
                continue  # "Aktionen/Einstellungen" is a word pair, not a path
            if pattern is TOKEN_PATTERNS[0] and is_prose_span(token):
                continue  # "` und `": a closing and an opening backtick paired across a line break
            if len(token) > 1:
                found.add(token)
    return found


# ---------------------------------------------------------------------------------------------
# Glossary from the UI translations (de.ts / en.ts)
# ---------------------------------------------------------------------------------------------

I18N_ENTRY = re.compile(r"^\s*([A-Za-z_][\w]*):\s*'((?:[^'\\]|\\.)*)',?\s*$")


def i18n_pairs(repo):
    """Short UI terms: German value -> English value for the same key path."""
    def flat(path):
        out, stack = {}, []
        for line in Path(path).read_text(encoding='utf-8').split('\n'):
            opened = re.match(r'^(\s*)([A-Za-z_]\w*):\s*\{\s*$', line)
            if opened:
                depth = len(opened.group(1)) // 2
                stack = stack[:depth] + [opened.group(2)]
                continue
            m = I18N_ENTRY.match(line)
            if m:
                depth = (len(line) - len(line.lstrip())) // 2
                out['.'.join(stack[:depth - 1] + [m.group(1)])] = m.group(2)
        return out
    base = Path(repo) / 'ai-automation-frontend' / 'src' / 'i18n'
    de, en = flat(base / 'de.ts'), flat(base / 'en.ts')
    pairs = {}
    for key, value in de.items():
        target = en.get(key)
        if target and target.lower() != value.lower() and len(value.split()) <= 3 and '{' not in value:
            pairs.setdefault(value, target)
    return pairs


def glossary_for(texts, pairs):
    joined = '\n'.join(texts)
    return {de: en for de, en in pairs.items()
            if len(de) > 3 and re.search(r'(?<!\w)' + re.escape(de) + r'(?!\w)', joined)}


# ---------------------------------------------------------------------------------------------
# State files
# ---------------------------------------------------------------------------------------------

def read_jsonl(path):
    if not Path(path).exists():
        return []
    return [json.loads(line) for line in Path(path).read_text(encoding='utf-8').splitlines() if line.strip()]


def append_jsonl(path, items):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    with open(path, 'a', encoding='utf-8') as fh:
        for item in items:
            fh.write(json.dumps(item, ensure_ascii=False) + '\n')


def load_map(state):
    return {e['hash']: e for e in read_jsonl(Path(state) / 'map.jsonl')}


def load_index(state):
    path = Path(state) / 'todo-index.tsv'
    index = {}
    if path.exists():
        for line in path.read_text(encoding='utf-8').splitlines():
            full, title = line.split('\t', 1)
            index.setdefault(full[:8], []).append((full, title))
    # An ambiguous short id stays short - better a short id than the wrong TODO.
    return {k: v[0] for k, v in index.items() if len(v) == 1}


# ---------------------------------------------------------------------------------------------
# Commands
# ---------------------------------------------------------------------------------------------

def cmd_todo_index(args):
    base = os.environ.get('MYSURICATES_API_BASE', 'http://localhost:9090/api/v1')
    key = os.environ.get('MYSURICATES_API_KEY') or Path(os.path.expanduser('~/.mysuricates/api-key')).read_text().strip()
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    rows, page = [], 0
    while True:
        req = urllib.request.Request(f'{base}/todos?page={page}&size=200', headers={'X-API-Key': key})
        data = json.load(opener.open(req, timeout=60))
        content = data.get('content', data) if isinstance(data, dict) else data
        rows += [(t['id'], (t.get('title') or '').replace('\t', ' ').replace('\n', ' ')) for t in content]
        if not isinstance(data, dict) or page + 1 >= data.get('totalPages', 1):
            break
        page += 1
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text(''.join(f'{i}\t{t}\n' for i, t in rows), encoding='utf-8')
    print(f'{len(rows)} TODOs -> {args.out}')
    return 0


def git_messages(repo, rev):
    out = subprocess.run(['git', '-C', repo, 'log', '--format=%H%x00%B%x1e', rev],
                         capture_output=True, text=True, check=True).stdout
    for record in out.split('\x1e'):
        record = record.lstrip('\n')
        if record:
            sha, msg = record.split('\x00', 1)
            yield sha, msg.rstrip('\n') + '\n'


def cmd_extract(args):
    state, run = Path(args.state), Path(args.state) / 'runs' / args.run
    done = load_map(state)
    index = load_index(state)
    pairs = i18n_pairs(args.repo)
    pending, direct = [], []
    redo = 0
    for sha, msg in git_messages(args.repo, args.rev):
        if sha in done:
            # --redo-german: a map entry that still reads German goes to translation again; until
            # a new answer passes the check, the old entry stays the one the rewrite uses
            if not (args.redo_german and needs_translation(done[sha]['message'])):
                continue
            redo += 1
        moved = drop_private_trailers(move_todo_bracket(msg, index))
        if needs_translation(moved):
            pending.append({'hash': sha, 'source': msg, 'message': moved})
        else:
            direct.append({'hash': sha, 'message': moved, 'translated': False})
    if args.sample and len(pending) > args.sample:
        step = len(pending) / args.sample
        pending = [pending[int(i * step)] for i in range(args.sample)]
        direct = []  # a probe writes nothing but its own batches
    if not args.sample:
        append_jsonl(state / 'map.jsonl', direct)
    batch_dir = run / 'batches'
    batch_dir.mkdir(parents=True, exist_ok=True)
    names = []
    for n in range(0, len(pending), args.batch_size):
        chunk = pending[n:n + args.batch_size]
        name = f'{len(names) + 1:03d}'
        glossary = glossary_for([c['message'] for c in chunk], pairs)
        (batch_dir / f'{name}.json').write_text(json.dumps({
            'batch': name, 'glossary': glossary,
            'messages': [{'hash': c['hash'], 'message': c['message']} for c in chunk],
        }, ensure_ascii=False, indent=1), encoding='utf-8')
        (batch_dir / f'{name}.source.json').write_text(json.dumps(chunk, ensure_ascii=False), encoding='utf-8')
        names.append(name)
    (run / 'batches.txt').write_text(''.join(n + '\n' for n in names), encoding='utf-8')
    print(f'run {args.run}: {len(pending)} messages to translate in {len(names)} batches'
          f' of up to {args.batch_size}; {len(direct)} without German text'
          + (' (probe: not written to the map)' if args.sample else ' went straight into the map')
          + f'; {len(done)} already in the map'
          + (f', {redo} of them sent again because they still read German' if args.redo_german else ''))
    return 0


def json_object(text):
    """The first complete JSON object in text (answers may carry prose or code fences around it)."""
    decoder = json.JSONDecoder()
    start = text.find('{')
    while start >= 0:
        try:
            obj, _ = decoder.raw_decode(text, start)
            if isinstance(obj, dict):
                return obj
        except ValueError:
            pass
        start = text.find('{', start + 1)
    return None


def private_terms():
    path = Path(os.environ.get('HYGIENE_PRIVATE_TERMS',
                               os.path.expanduser('~/.config/mysuricates/hygiene-private-terms.txt')))
    if not path.exists():
        return []
    return [t.strip().lower() for t in path.read_text(encoding='utf-8').splitlines()
            if t.strip() and not t.startswith('#')]


def survives(token, text):
    """A preserved token is still there - allowing what a correct translation changes: the German
    decimal comma becomes a point ("1,5" -> "1.5"), German inflection on an identifier goes
    ("MvcRequestMatchern" -> "MvcRequestMatchers"), and the cases commented below."""
    if token in text:
        return True
    if re.fullmatch(r'\d+(?:,\d+)+', token) and token.replace(',', '.') in text:
        return True
    # German thousands separator: "1.024" -> "1,024" or "1024"
    if re.fullmatch(r'\d{1,3}(?:\.\d{3})+', token) \
            and (token.replace('.', ',') in text or token.replace('.', '') in text):
        return True
    # An identifier whose case or spacing the translation changes: "SystemPrompt-Building" ->
    # "system prompt building", "Browse/OpenPath" -> "browse/openPath"
    if TOKEN_PATTERNS[5].fullmatch(token):
        words = re.findall(r'[A-Z]?[a-z0-9]+|[A-Z]+(?![a-z])', token)
        lowered = text.lower()
        if any(form.lower() in lowered for form in (token, ' '.join(words), '-'.join(words))):
            return True
    # A German word list with slashes, translated as one: "abgelaufen/verbraucht/erraten" ->
    # "expired/consumed/guessed"; below a known top-level directory it stays a path
    segments = token.split('/')
    if len(segments) >= 2 and segments[0] not in PATH_ROOTS \
            and all(re.fullmatch(r'[a-zäöüß]+', seg) for seg in segments) \
            and re.search(r'[\w-]+' + r'/[\w-]+' * (len(segments) - 1), text):
        return True
    # A German compound in front of a file name: "Root-VISION.md" -> "root VISION.md"
    compound = re.fullmatch(r'[A-ZÄÖÜ]\w*-(\S+\.\w+)', token)
    if compound and compound.group(1) in text:
        return True
    for ending in ('n', 'en', 's', 'e', 'es'):
        if token.endswith(ending) and len(token) - len(ending) >= 4 and re.search(r'[A-Z_]', token) \
                and token[:-len(ending)] in text:
            return True
    return False


def problems(source, translated, terms):
    """(errors, warnings) for one translated message against its (trailer-moved) source."""
    errors, warnings = [], []
    s_subj, s_body, s_trailers = split_message(source)
    t_subj, t_body, t_trailers = split_message(translated)
    if t_trailers != s_trailers:
        errors.append('trailers changed')  # check() restores them before calling this
    src_text, out_text = s_subj + '\n' + s_body, t_subj + '\n' + t_body
    missing = sorted(t for t in preserved_tokens(src_text) if not survives(t, out_text))
    if missing:
        errors.append('lost: ' + ', '.join(missing[:6]))
    prefix = SUBJECT_PREFIX.match(s_subj)
    if prefix and not t_subj.startswith(prefix.group(1)):
        errors.append('prefix changed')
    # The model translated the body but handed back the German subject (about 100 messages of the
    # full run on 2026-10-08) - only a warning then, so they went into the map unchanged.
    if is_german_text(subject_text(s_subj)) and subject_text(t_subj).strip() == subject_text(s_subj).strip():
        errors.append('subject not translated')
    ratio = len(out_text) / max(len(src_text), 1)
    if not 0.45 <= ratio <= 1.9:
        errors.append(f'length ratio {ratio:.2f}')
    if needs_translation(translated):
        warnings.append('German left')
    if len(t_subj) > MAX_SUBJECT:
        warnings.append(f'subject {len(t_subj)} chars')
    if len(EMAIL.findall(out_text)) > len(EMAIL.findall(src_text)):
        errors.append('new email address')
    for term in terms:
        if out_text.lower().count(term) > src_text.lower().count(term):
            errors.append('new private term')
            break
    return errors, warnings


def cmd_check(args):
    state, run = Path(args.state), Path(args.state) / 'runs' / args.run
    source = {c['hash']: c for c in json.loads((run / 'batches' / f'{args.batch}.source.json').read_text(encoding='utf-8'))}
    stored = run / 'answers' / f'{args.batch}.txt'
    if args.stored:
        raw = stored.read_text(encoding='utf-8')
    else:
        raw = Path(args.answer).read_text(encoding='utf-8') if args.answer else sys.stdin.read()
        stored.parent.mkdir(parents=True, exist_ok=True)
        stored.write_text(raw, encoding='utf-8')
    # A re-check replaces this batch's earlier verdicts.
    for name in ('accepted.jsonl', 'rejects.jsonl'):
        kept = [e for e in read_jsonl(run / name) if e.get('batch') != args.batch]
        (run / name).write_text(''.join(json.dumps(e, ensure_ascii=False) + '\n' for e in kept), encoding='utf-8')
    answer = json_object(raw) or {}
    got = {m.get('hash'): m.get('message', '') for m in answer.get('messages', []) if isinstance(m, dict)}
    terms = private_terms()
    accepted, rejected = [], []
    for sha, item in source.items():
        text = got.get(sha)
        if not text:
            rejected.append({'hash': sha, 'batch': args.batch, 'reasons': ['missing in answer']})
            continue
        text = text.rstrip('\n') + '\n'
        restored = []
        s_subj, s_body, s_trailers = split_message(item['message'])
        t_subj, t_body, t_trailers = split_message(text)
        if t_trailers != s_trailers:
            # Trailers are never translated: put the original ones back instead of rejecting.
            text = join_message(t_subj, t_body, s_trailers)
            restored = ['trailers restored']
        errors, warnings = problems(item['message'], text, terms)
        warnings = restored + warnings
        if errors:
            rejected.append({'hash': sha, 'batch': args.batch, 'reasons': errors, 'warnings': warnings,
                             'translation': text})
        else:
            accepted.append({'hash': sha, 'message': text, 'translated': True, 'batch': args.batch,
                             'run': args.run, 'warnings': warnings})
    append_jsonl(run / 'accepted.jsonl', accepted)
    append_jsonl(run / 'rejects.jsonl', rejected)
    if not args.probe:
        append_jsonl(state / 'map.jsonl', [{k: v for k, v in a.items() if k in ('hash', 'message', 'translated')}
                                           for a in accepted])
    print(f'batch {args.batch}: {len(accepted)} accepted, {len(rejected)} rejected'
          + ''.join(f'\n  {r["hash"][:10]}: {"; ".join(r["reasons"])}' for r in rejected))
    return 0


def cmd_report(args):
    run = Path(args.state) / 'runs' / args.run
    sources = {}
    for f in sorted((run / 'batches').glob('*.source.json')):
        for c in json.loads(f.read_text(encoding='utf-8')):
            sources[c['hash']] = c
    accepted = {a['hash']: a for a in read_jsonl(run / 'accepted.jsonl')}
    rejected = {r['hash']: r for r in read_jsonl(run / 'rejects.jsonl')}
    warned = sum(1 for a in accepted.values() if a.get('warnings'))
    rows = []
    for sha, c in sources.items():
        a, r = accepted.get(sha), rejected.get(sha)
        status = 'ok' if a else ('rejected' if r else 'open')
        notes = '; '.join((a or {}).get('warnings') or (r or {}).get('reasons') or [])
        out = (a or {}).get('message') or (r or {}).get('translation') or ''
        rows.append(f'<tr class="{status}"><td><code>{sha[:10]}</code><br>{status}<br><small>{html.escape(notes)}</small></td>'
                    f'<td><pre>{html.escape(c["source"])}</pre></td><td><pre>{html.escape(out)}</pre></td></tr>')
    page = f'''<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>History rewrite probe {html.escape(args.run)}</title>
<style>body{{font:14px system-ui,sans-serif;margin:16px;background:#fff;color:#111}}
table{{border-collapse:collapse;width:100%}}td{{border-top:1px solid #ccc;vertical-align:top;padding:6px}}
pre{{white-space:pre-wrap;margin:0;font-size:12px}}tr.rejected td:first-child{{color:#b00}}tr.ok td:first-child{{color:#070}}
@media (prefers-color-scheme:dark){{body{{background:#111;color:#eee}}td{{border-color:#444}}}}</style></head><body>
<h1>History rewrite probe – run {html.escape(args.run)}</h1>
<p>{len(sources)} messages · {len(accepted)} accepted ({warned} with warnings) · {len(rejected)} rejected ·
{len(sources) - len(accepted) - len(rejected)} without answer</p>
<table><tr><th>Commit</th><th>Original</th><th>Rewritten</th></tr>{''.join(rows)}</table></body></html>
'''
    (run / 'review.html').write_text(page, encoding='utf-8')
    total = len(sources)
    print(f'## History rewrite – run {args.run}\n'
          f'Messages: {total}, accepted: {len(accepted)} (with warnings: {warned}), rejected: {len(rejected)}, '
          f'without answer: {total - len(accepted) - len(rejected)}\n'
          f'Review page: {run / "review.html"}')
    reasons = {}
    for r in rejected.values():
        for reason in r['reasons']:
            key = reason.split(':')[0]
            reasons[key] = reasons.get(key, 0) + 1
    for key, n in sorted(reasons.items(), key=lambda kv: -kv[1]):
        print(f'- rejected because of {key}: {n}')
    # Nothing to translate is the usual catch-up run before a cut (English commits go straight into the
    # map); a broken extract already fails the step before the report.
    ok = len(accepted) == total
    print('gate_status: ' + ('GREEN' if ok else 'YELLOW' if accepted else 'RED'))
    print('gate_category: history-rewrite')
    print('gate_description: ' + (f'{len(accepted)} of {total} messages accepted, {len(rejected)} rejected' if total
                                  else 'nothing to translate - every new message went straight into the map'))
    return 0


CALLBACK = '''import json, re
_state = globals().setdefault('_rewrite_state', {{}})
if not _state:
    _state['map'] = {{}}
    with open({path!r}, encoding='utf-8') as fh:
        for line in fh:
            if line.strip():
                entry = json.loads(line)
                _state['map'][entry['hash'].encode()] = entry['message'].encode('utf-8')
    _state['rules'] = []
    for raw in {rules!r}:
        pattern, _, repl = raw.partition('==>')
        repl = (repl if _ else '***REMOVED***').encode('utf-8')
        if pattern.startswith('regex:'):
            _state['rules'].append((re.compile(pattern[6:].encode('utf-8')), repl))
        else:
            _state['rules'].append((re.compile(re.escape(pattern.encode('utf-8'))), repl))
message = _state['map'].get(commit.original_id, commit.message)
# Private session links go regardless of the map (also for messages that were never translated).
message = re.sub(rb'(?m)^Claude-Session: [^\\n]*\\n?', b'', message).rstrip(b'\\n') + b'\\n'
for rx, repl in _state['rules']:
    message = rx.sub(lambda m: repl, message)
commit.message = message
'''


def replacement_rules(path):
    """Lines of a filter-repo style expressions file (literal==>new, regex:pattern==>new)."""
    if not path or not Path(path).exists():
        return []
    return [line for line in Path(path).read_text(encoding='utf-8').splitlines()
            if line.strip() and not line.lstrip().startswith('#')]


def cmd_callback(args):
    print(CALLBACK.format(path=str(Path(args.map).resolve()), rules=replacement_rules(args.replacements)))
    return 0


def cmd_map_missing(args):
    done = load_map_file(args.map)
    print(sum(1 for sha, _ in git_messages(args.repo, '--all') if sha not in done))
    return 0


def cmd_german_left(args):
    print(sum(1 for _, msg in git_messages(args.repo, '--all') if needs_translation(msg)))
    return 0


def compile_rules(path):
    out = []
    for raw in replacement_rules(path):
        pattern = raw.partition('==>')[0]
        out.append(re.compile(pattern[6:]) if pattern.startswith('regex:') else re.compile(re.escape(pattern)))
    return out


def cmd_patterns_left(args):
    """Number of replacement rules that still match some message (Python regex, like the callback)."""
    rules = compile_rules(args.replacements)
    texts = [msg for _, msg in git_messages(args.repo, '--all')]
    print(sum(1 for rx in rules if any(rx.search(t) for t in texts)))
    return 0


def load_map_file(path):
    return {e['hash'] for e in read_jsonl(path)}


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    sub = ap.add_subparsers(dest='cmd', required=True)
    p = sub.add_parser('todo-index')
    p.add_argument('--out', required=True)
    p = sub.add_parser('extract')
    p.add_argument('--repo', required=True)
    p.add_argument('--state', required=True)
    p.add_argument('--run', required=True)
    p.add_argument('--rev', default='main')
    p.add_argument('--sample', type=int, default=0, help='probe: N German messages spread over the history')
    p.add_argument('--batch-size', type=int, default=10)
    p.add_argument('--redo-german', action='store_true',
                   help='translate map entries again whose message still reads German')
    p = sub.add_parser('check')
    p.add_argument('--state', required=True)
    p.add_argument('--run', required=True)
    p.add_argument('--batch', required=True)
    p.add_argument('--answer')
    p.add_argument('--probe', action='store_true', help='keep accepted messages out of the map')
    p.add_argument('--stored', action='store_true', help='re-check the stored answer (no new AI call)')
    p = sub.add_parser('report')
    p.add_argument('--state', required=True)
    p.add_argument('--run', required=True)
    p = sub.add_parser('callback')
    p.add_argument('--map', required=True)
    p.add_argument('--replacements', help='message replacements, filter-repo expression syntax')
    p = sub.add_parser('map-missing')
    p.add_argument('--repo', required=True)
    p.add_argument('--map', required=True)
    p = sub.add_parser('patterns-left')
    p.add_argument('--repo', required=True)
    p.add_argument('--replacements', required=True)
    p = sub.add_parser('german-left')
    p.add_argument('--repo', required=True)
    args = ap.parse_args(argv)
    return {'todo-index': cmd_todo_index, 'extract': cmd_extract, 'check': cmd_check,
            'report': cmd_report, 'callback': cmd_callback, 'map-missing': cmd_map_missing,
            'german-left': cmd_german_left, 'patterns-left': cmd_patterns_left}[args.cmd](args)


if __name__ == '__main__':
    sys.exit(main())
