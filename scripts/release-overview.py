#!/usr/bin/env python3
"""Release overview: where the working repo (archive) and the public repo stand, which checks the
way between them has, and how the kinds of release run - one self-contained HTML page.

Deterministic, standard library only, no AI. Two commands:

  event   --log FILE --kind KIND --tag TAG --status STATUS [--output FILE] [--note TEXT] [--commit SHA]
          Append one line to the event log (JSON lines). With --output the checks are read from the
          output of rewrite.sh ("  ok ..." / "  FEHLER ..."), release-handover.sh ("## Gates" list) or
          pre-push-checks.sh ("  - name: OK" / "  ✗ ...").
          KIND: cut | handover | push | discard | archive-gate | reset | backport
          STATUS: ok | ready | pushed | nothing | discarded | failed
  render  --archive REPO [--archive-ref main] [--archive-upstream REF]
          --public REPO [--public-ref main] [--public-upstream REF]
          --log FILE --out FILE [--mode probe|live] [--status-out FILE]
          Write the page. --status-out gets GREEN/YELLOW/RED and a one-line summary for the
          dashboard (two lines). A public repo that does not exist yet is shown as "not created".

The four release workflows of the maintainer's instance call this script; they are listed in
docs/entwicklung.md, section on the release overview and the probe.
"""
import argparse
import datetime as dt
import html
import json
import os
import re
import subprocess
import sys
from pathlib import Path

# ---------------------------------------------------------------------------------------------
# The process model: stations on the way from a commit to a public release, and the kinds of
# release as paths through them. Page text is German like the rest of the maintainer tooling.
# ---------------------------------------------------------------------------------------------

STATIONS = [
    ('commit', 'Commit', 'Arbeits-Repo', [
        'commit-msg-Hook: Betreff ≤ 72 Zeichen, noreply-Adresse',
        'keine verbotenen Literale, keine Mailadresse außerhalb eines Trailers',
    ]),
    ('push-archive', 'Push ins Archiv', 'Arbeits-Repo → Archiv', [
        'Pre-Push-Gate: Autor, Nachrichten, Hygiene des Bereichs, gitleaks',
        'Doku-Gates (Zahlen, Verweise DE/EN, Diagramme, API-Referenz)',
        'Build nach Scope (tsc + lint, mvn test-compile)',
        'geänderte Ausnahmedateien: Freigabe nur durch einen Menschen',
    ]),
    ('tag', 'Release-Tag', 'Arbeits-Repo', [
        'release.sh: annotiertes Tag vX.Y.Z auf main',
    ]),
    ('rewrite', 'Rewrite', 'nur beim Erstschnitt', [
        'filter-repo: private Pfade raus, Mailmap auf noreply, Nachrichten englisch',
        'Gegenprobe: Pfade, Adressen, Nachrichten-Hygiene, Session-Trailer',
        'Baum der Spitze gleich dem Tag, nur main, nur v*-Tags, gitleaks, README-Marke',
    ]),
    ('handover', 'Übergabe', 'Tag → öffentlicher Klon', [
        'Treue: jede Datei wie im Tag, sonst nur release-notes/',
        'Public-Hygiene (--tracked), Pool-Neutralität, README-Sync-Stand',
        'gitleaks, Doku-Gates; mit with_build zusätzlich mvn test, tsc + lint',
    ]),
    ('approval', 'Freigabe', 'Mensch', [
        'zeigt Diff-Statistik und Prüfergebnis',
        'Pushen oder Verwerfen',
    ]),
    ('push-public', 'Push öffentlich', 'öffentlicher Klon → öffentliches Repo', [
        'HEAD und Tag sind noch der freigegebene Commit',
        'git push --atomic: main und Tag zusammen oder gar nicht',
    ]),
    ('ci', 'Öffentliches Repo', 'GitHub', [
        'CI des öffentlichen Repos',
        'release.yml legt das GitHub-Release aus release-notes/<tag>.md an',
    ]),
]
STATION_TITLES = {key: title for key, title, _, _ in STATIONS}

KINDS = [
    ('cut', 'Erstschnitt', 'einmalig',
     ['commit', 'push-archive', 'tag', 'rewrite', 'approval', 'push-public', 'ci'],
     'Die bereinigte Historie bis zum ersten Release-Tag geht einmal ins neue Repo. Danach bekommt '
     'es nie wieder Historie, nur noch Releases.'),
    ('release', 'Release', 'je Version',
     ['commit', 'push-archive', 'tag', 'handover', 'approval', 'push-public', 'ci'],
     'Ein Tag im Arbeits-Repo wird als genau ein geprüfter Commit „Release vX.Y.Z“ samt Tag '
     'übergeben. Alles zwischen zwei Tags erscheint öffentlich als ein Schritt.'),
    ('nothing', 'Nichts zu übergeben', 'Abbruch ohne Spuren',
     ['tag', 'handover'],
     'Der Stand des Tags gleicht dem öffentlichen Repo: Die Übergabe endet sauber, im Klon bleibt '
     'nichts zurück.'),
    ('discard', 'Verworfen', 'Freigabe abgelehnt',
     ['tag', 'handover', 'approval'],
     'Commit und Tag im Klon werden zurückgenommen (--discard). Ein neuer Lauf für dasselbe Tag '
     'ist danach möglich.'),
    ('backport', 'Rückweg', 'Fix aus dem öffentlichen Repo',
     ['commit', 'push-archive', 'tag', 'handover', 'approval', 'push-public', 'ci'],
     'Ein Beitrag im öffentlichen Repo kommt per git format-patch | git am -3 ins Arbeits-Repo und '
     'geht mit dem nächsten Release regulär zurück.'),
]

EVENT_KINDS = ('cut', 'handover', 'push', 'discard', 'archive-gate', 'reset', 'backport')
EVENT_STATUS = ('ok', 'ready', 'pushed', 'nothing', 'discarded', 'failed')
GOOD = {'ok', 'ready', 'pushed', 'nothing', 'discarded'}
TAG_RE = re.compile(r'^v\d+\.\d+\.\d+$')


# ---------------------------------------------------------------------------------------------
# Events
# ---------------------------------------------------------------------------------------------

# Exactly two leading spaces: deeper indented lines are the log tail of a gate (release-handover.sh
# indents it by four), whose own sub-results must not count as checks of their own.
CHECK_REWRITE = re.compile(r'^  (ok|FEHLER|hinweis) +(.*\S)\s*$')
CHECK_HANDOVER = re.compile(r'^- (.+?): (OK|FEHLER|übersprungen|nicht angefordert)\b\s*(.*)$')
CHECK_GATE_OK = re.compile(r'^  – (.+?): (OK|keine Funde)\b\s*(.*)$')
CHECK_GATE_SKIPPED = re.compile(r'^  – (.*(?:uebersprungen|übersprungen).*\S)')
CHECK_GATE_FAIL = re.compile(r'^  ✗ (.*\S)')
REWRITE_RESULT = {'ok': 'ok', 'FEHLER': 'failed', 'hinweis': 'skipped'}


def parse_checks(text):
    """Checks from the output of rewrite.sh, release-handover.sh or pre-push-checks.sh."""
    checks, in_gates = [], False
    for line in text.splitlines():
        if line.startswith('## '):
            in_gates = line.strip() == '## Gates'
            continue
        m = CHECK_HANDOVER.match(line) if in_gates else None
        if m:
            result = {'OK': 'ok', 'FEHLER': 'failed'}.get(m.group(2), 'skipped')
            detail = m.group(3).strip()
            if detail.startswith('(') and detail.endswith(')'):
                detail = detail[1:-1]
            checks.append({'name': m.group(1), 'result': result, 'detail': detail})
            continue
        m = CHECK_REWRITE.match(line)
        if m:
            checks.append({'name': m.group(2), 'result': REWRITE_RESULT[m.group(1)]})
            continue
        m = CHECK_GATE_OK.match(line)
        if m:
            check = {'name': m.group(1), 'result': 'ok'}
            if m.group(3).strip(' —-'):
                check['detail'] = m.group(3).strip(' —-')  # "OK — 2 Warnung(en)"
            checks.append(check)
            continue
        m = CHECK_GATE_SKIPPED.match(line)
        if m:
            checks.append({'name': m.group(1)[:160], 'result': 'skipped'})
            continue
        m = CHECK_GATE_FAIL.match(line)
        if m:
            checks.append({'name': m.group(1)[:160], 'result': 'failed'})
    return checks


def output_value(text, key):
    m = re.search(r'^' + re.escape(key) + r':\s*(\S+)', text, re.M)
    return m.group(1) if m else None


def cmd_event(args):
    text = Path(args.output).read_text(encoding='utf-8', errors='replace') if args.output else ''
    event = {
        'time': dt.datetime.now(dt.timezone.utc).isoformat(timespec='seconds'),
        'kind': args.kind,
        'tag': args.tag or None,
        'status': args.status,
        'commit': args.commit or output_value(text, 'handover_commit'),
        'checks': parse_checks(text),
    }
    if args.note:
        event['note'] = args.note
    log = Path(args.log)
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open('a', encoding='utf-8') as fh:
        fh.write(json.dumps(event, ensure_ascii=False) + '\n')
    failed = sum(1 for c in event['checks'] if c['result'] == 'failed')
    print(f"event: {args.kind} {args.tag or '-'} {args.status} ({len(event['checks'])} Prüfungen, {failed} Fehler)")
    return 0


def read_events(path):
    """The event log; a torn or foreign line must not hide the rest."""
    events = []
    if path and Path(path).is_file():
        for line in Path(path).read_text(encoding='utf-8', errors='replace').splitlines():
            try:
                event = json.loads(line) if line.strip() else None
            except json.JSONDecodeError:
                continue
            if isinstance(event, dict) and event.get('kind') in EVENT_KINDS:
                event.setdefault('time', '')
                event.setdefault('status', 'ok')
                event['checks'] = [c for c in event.get('checks') or [] if isinstance(c, dict) and 'name' in c]
                events.append(event)
    return events


# ---------------------------------------------------------------------------------------------
# Reading a line (archive or public)
# ---------------------------------------------------------------------------------------------

def git(repo, *args):
    env = {k: v for k, v in os.environ.items() if k not in ('GIT_DIR', 'GIT_WORK_TREE', 'GIT_INDEX_FILE')}
    r = subprocess.run(['git', '-C', str(repo), *args], capture_output=True, text=True, env=env)
    return r.stdout.strip() if r.returncode == 0 else None


def read_line(repo, ref, upstream=None):
    """What a page needs of one line: head, v* tags with their position, count, unpushed."""
    if not repo or not Path(repo).exists() or git(repo, 'rev-parse', '--verify', '--quiet', ref + '^{commit}') is None:
        return None
    head = git(repo, 'log', '-1', '--format=%H%x1f%cI%x1f%s', ref).split('\x1f')
    order = git(repo, 'rev-list', '--first-parent', '--reverse', ref).split()
    index = {sha: i for i, sha in enumerate(order)}
    tags = []
    for line in (git(repo, 'for-each-ref', '--format=%(refname:short)%1f%(*objectname)%1f%(objectname)%1f%(creatordate:iso-strict)',
                     'refs/tags') or '').splitlines():
        name, peeled, obj, date = line.split('\x1f')
        sha = peeled or obj
        if not TAG_RE.match(name) or git(repo, 'merge-base', '--is-ancestor', sha, ref) is None:
            continue
        pos = index.get(sha)
        if pos is None:  # tag on a merged side branch: place it at the merge into the line
            merged = (git(repo, 'rev-list', '--first-parent', '--ancestry-path', '--reverse', f'{sha}..{ref}') or '').split()
            pos = index.get(merged[0], len(order) - 1) if merged else len(order) - 1
        subject = git(repo, 'log', '-1', '--format=%s', sha) or ''
        tags.append({'name': name, 'sha': sha, 'date': date, 'pos': pos,
                     'commits': int(git(repo, 'rev-list', '--count', sha)), 'subject': subject,
                     'handover': subject.startswith('Release v')})
    tags.sort(key=lambda t: t['pos'])
    unpushed = None
    if upstream and git(repo, 'rev-parse', '--verify', '--quiet', upstream) is not None:
        unpushed = int(git(repo, 'rev-list', '--count', f'{upstream}..{ref}'))
        behind = int(git(repo, 'rev-list', '--count', f'{ref}..{upstream}'))
    else:
        behind = None
    since_tag = None
    if tags:
        since_tag = int(git(repo, 'rev-list', '--count', f"{tags[-1]['sha']}..{ref}"))
    return {
        'repo': str(repo), 'ref': ref, 'upstream': upstream,
        'head': {'sha': head[0], 'date': head[1], 'subject': head[2]},
        'count': int(git(repo, 'rev-list', '--count', ref)), 'span': len(order),
        'tags': tags, 'unpushed': unpushed, 'behind': behind,
        'since_tag': since_tag,
    }


def cut_tag(public):
    """The tag the first cut brought: the newest public tag that is no "Release vX" handover commit
    (the rewrite keeps the older v* tags of the history as well)."""
    rewritten = [t for t in (public or {}).get('tags', []) if not t['handover']]
    return rewritten[-1] if rewritten else None


def pending_stat(archive, public):
    """What the next handover would bring: commits and files since the last published tag, and the
    archive tags after it that are not published yet. Before the first cut nothing is open."""
    if not archive:
        return None
    public_names = {t['name'] for t in (public or {}).get('tags', [])}
    published = [t for t in archive['tags'] if t['name'] in public_names]
    if not published:
        return {'base': None, 'open_tags': [], 'commits': archive['count'], 'files': None}
    base = published[-1]
    open_tags = [t['name'] for t in archive['tags'] if t['pos'] > base['pos'] and t['name'] not in public_names]
    commits = int(git(archive['repo'], 'rev-list', '--count', f"{base['sha']}..{archive['ref']}") or 0)
    shortstat = git(archive['repo'], 'diff', '--shortstat', base['sha'], archive['ref']) or ''
    m = re.search(r'(\d+) files? changed', shortstat)
    return {'base': base['name'], 'open_tags': open_tags, 'commits': commits,
            'files': int(m.group(1)) if m else 0}


# ---------------------------------------------------------------------------------------------
# Assessment
# ---------------------------------------------------------------------------------------------

def last_event(events, *kinds):
    for e in reversed(events):
        if e.get('kind') in kinds:
            return e
    return None


def station_states(archive, public, events, mode):
    """Per station: (state, text). state is ok | failed | open | none."""
    s = {}
    if archive:
        s['commit'] = ('ok', f"HEAD {archive['head']['sha'][:9]} · {archive['count']} Commits")
        if archive['unpushed'] is None:
            s['push-archive'] = ('none', 'kein Upstream bekannt')
        elif archive['unpushed']:
            s['push-archive'] = ('open', f"{archive['unpushed']} Commit(s) nicht im Archiv gesichert")
        else:
            s['push-archive'] = ('ok', 'Archiv auf dem Stand von main')
        gate = last_event(events, 'archive-gate')
        if gate and gate.get('status') == 'failed':
            s['push-archive'] = ('failed', 'letztes Pre-Push-Gate: ' + failed_names(gate))
        if archive['tags']:
            last = archive['tags'][-1]
            since = archive['since_tag']
            s['tag'] = ('ok' if not since else 'open',
                        f"{last['name']}" + (f" · {since} Commit(s) danach, noch ohne Tag" if since else ' auf HEAD'))
        else:
            s['tag'] = ('none', 'noch kein v*-Tag')
    for key, kinds in (('rewrite', ('cut',)), ('handover', ('handover',))):
        e = last_event(events, *kinds)
        s[key] = event_state(e) if e else ('none', 'noch nie gelaufen')
    pending = pending_stat(archive, public)
    if pending and pending['open_tags'] and public:
        base = s['handover'][1] if s['handover'][0] != 'none' else 'noch nie gelaufen'
        s['handover'] = ('open', f"wartet: {', '.join(pending['open_tags'])} · zuletzt {base}")
    decision = last_event(events, 'push', 'discard')
    if decision:
        s['approval'] = ('ok', ('Pushen' if decision['kind'] == 'push' else 'Verwerfen')
                         + f" · {decision.get('tag') or '-'} · {short_time(decision['time'])}")
    else:
        s['approval'] = ('none', 'noch keine Entscheidung')
    push = last_event(events, 'push')
    if push:
        s['push-public'] = event_state(push)
    elif public:
        s['push-public'] = ('ok', f"{len(public['tags'])} Tag(s) im öffentlichen Repo")
    else:
        s['push-public'] = ('none', 'öffentliches Repo noch nicht angelegt')
    if mode == 'probe':
        s['ci'] = ('none', 'in der Probe nicht vorhanden (lokales Bare-Repo)')
    elif public:
        s['ci'] = ('none', 'Status auf GitHub ansehen')
    else:
        s['ci'] = ('none', 'öffentliches Repo noch nicht angelegt')
    return s


def event_state(e):
    checks = e.get('checks') or []
    failed = [c for c in checks if c['result'] == 'failed']
    text = f"{e.get('tag') or '-'} · {status_word(e['status'])} · {short_time(e['time'])}"
    if checks:
        text += f" · {len(checks) - len(failed)}/{len(checks)} Prüfungen grün"
    return ('failed' if e['status'] == 'failed' else 'ok', text)


def failed_names(e):
    names = [c['name'] for c in e.get('checks') or [] if c['result'] == 'failed']
    return ', '.join(names[:3]) or 'fehlgeschlagen'


def overall(archive, public, events, pending):
    """GREEN / YELLOW / RED and a one-line summary for the dashboard."""
    if not archive:
        return 'RED', 'Arbeits-Repo nicht lesbar'
    parts = [f"Archiv {archive['tags'][-1]['name'] if archive['tags'] else 'ohne Tag'}"
             + (f" +{archive['since_tag']}" if archive['since_tag'] else '')]
    if not public:
        parts.append('öffentlich noch nicht angelegt')
    else:
        parts.append(f"öffentlich {public['tags'][-1]['name'] if public['tags'] else 'noch leer'}")
    open_reasons = []
    if pending and pending['open_tags']:
        open_reasons.append('offen: ' + ', '.join(pending['open_tags']))
    if archive['unpushed'] is None:
        open_reasons.append('Archiv ohne Upstream')
    elif archive['unpushed']:
        open_reasons.append(f"{archive['unpushed']} Commit(s) nicht im Archiv")
    if archive['behind']:
        open_reasons.append(f"Archiv {archive['behind']} Commit(s) hinter {archive['upstream']}")
    if not public or not public['tags']:
        open_reasons.append('Erstschnitt steht aus')
    last = last_event(events, 'cut', 'handover', 'push', 'archive-gate')
    if last and last['status'] == 'failed':
        return 'RED', ' · '.join(parts + [f"letzter Lauf fehlgeschlagen ({KIND_WORDS.get(last['kind'], last['kind'])} "
                                         f"{last.get('tag') or ''})".replace(' )', ')')])
    if open_reasons:
        return 'YELLOW', ' · '.join(parts + open_reasons)
    return 'GREEN', ' · '.join(parts)


# ---------------------------------------------------------------------------------------------
# HTML
# ---------------------------------------------------------------------------------------------

def esc(value):
    return html.escape(str(value), quote=True)


def short_time(iso):
    try:
        t = dt.datetime.fromisoformat(iso)
        return t.astimezone().strftime('%d.%m. %H:%M')
    except (TypeError, ValueError):
        return str(iso or '')


def status_word(status):
    return {'ok': 'ok', 'ready': 'bereit', 'pushed': 'gepusht', 'nothing': 'nichts zu tun',
            'discarded': 'verworfen', 'failed': 'fehlgeschlagen'}.get(status, status)


KIND_WORDS = {'cut': 'Erstschnitt', 'handover': 'Übergabe', 'push': 'Push', 'discard': 'Verworfen',
              'archive-gate': 'Pre-Push-Gate', 'reset': 'Probe zurückgesetzt', 'backport': 'Rückweg'}


def lanes_svg(archive, public, pending):
    """Two lanes. The history up to the cut (before it exists: up to the last tag) is compressed to a
    quarter of the width, the time after it is linear in first-parent commits. Older v* tags of the
    history are small ticks; the cut and every handover get a dot and an arrow between the lanes."""
    width, left, right = 1000, 110, 960
    y_arch, y_pub = 62, 182
    out = [f'<svg viewBox="0 0 {width} 250" role="img" aria-label="Archiv und öffentliches Repo">',
           '<defs><marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" '
           'orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" class="arrowhead"/></marker></defs>',
           f'<text x="12" y="{y_arch + 5}" class="lane-label">Archiv</text>',
           f'<text x="12" y="{y_pub + 5}" class="lane-label">Öffentlich</text>']
    if not archive:
        out.append(f'<text x="{left}" y="{y_arch + 5}" class="muted">Arbeits-Repo nicht lesbar</text></svg>')
        return '\n'.join(out)
    by_name = {t['name']: t for t in archive['tags']}
    cut = cut_tag(public)
    split_tag = by_name.get(cut['name']) if cut else None
    split_tag = split_tag or (archive['tags'][-1] if archive['tags'] else None)
    first = split_tag['pos'] if split_tag else archive['span'] - 1
    end = max(archive['span'] - 1, first + 1)
    split = left + (right - left) * 0.28

    def x(pos):
        pos = min(max(pos, 0), end)
        if pos <= first:
            return left + (split - left) * (pos / max(first, 1))
        return split + (right - split) * ((pos - first) / max(end - first, 1))

    # Archive lane
    out.append(f'<line x1="{left}" y1="{y_arch}" x2="{split:.1f}" y2="{y_arch}" class="lane archive compressed"/>')
    out.append(f'<line x1="{split:.1f}" y1="{y_arch}" x2="{x(end):.1f}" y2="{y_arch}" class="lane archive"/>')
    upto = split_tag['commits'] if split_tag else archive['count']
    out.append(f'<text x="{(left + split) / 2:.1f}" y="{y_arch - 14}" class="note" text-anchor="middle">'
               f'{upto} Commits bis {esc(split_tag["name"]) if split_tag else "HEAD"}</text>')
    for t in archive['tags']:
        tip = f'<title>{esc(t["name"])} · {esc(t["sha"][:9])} · {esc(t["subject"])}</title>'
        if t['pos'] < first:
            out.append(f'<circle cx="{x(t["pos"]):.1f}" cy="{y_arch}" r="3" class="tick archive">{tip}</circle>')
            continue
        out.append(f'<circle cx="{x(t["pos"]):.1f}" cy="{y_arch}" r="7" class="dot archive">{tip}</circle>')
        if t is not split_tag:
            out.append(f'<text x="{x(t["pos"]):.1f}" y="{y_arch - 14}" class="tag" text-anchor="middle">{esc(t["name"])}</text>')
    head_x = x(end)
    out.append(f'<circle cx="{head_x:.1f}" cy="{y_arch}" r="5" class="dot head"><title>main {esc(archive["head"]["sha"][:9])} · '
               f'{esc(archive["head"]["subject"])}</title></circle>')
    if archive['since_tag'] or not archive['tags']:
        out.append(f'<text x="{head_x:.1f}" y="{y_arch - 14}" class="tag" text-anchor="end">main</text>')
    if archive['since_tag']:
        x0 = x(archive['tags'][-1]['pos']) if archive['tags'] else left
        out.append(f'<line x1="{x0:.1f}" y1="{y_arch + 12}" x2="{head_x:.1f}" y2="{y_arch + 12}" class="bracket"/>')
        out.append(f'<text x="{(x0 + head_x) / 2:.1f}" y="{y_arch + 38}" class="note" text-anchor="middle">'
                   f'{archive["since_tag"]} Commit(s) noch ohne Tag</text>')

    # Public lane
    if not public or not public['tags']:
        out.append(f'<line x1="{left}" y1="{y_pub}" x2="{right}" y2="{y_pub}" class="lane public missing"/>')
        out.append(f'<text x="{left + 8}" y="{y_pub - 12}" class="muted">'
                   f'{"noch nicht angelegt" if not public else "angelegt, noch ohne Release"} – der Erstschnitt steht aus</text>')
    else:
        cut_x = x(by_name[cut['name']]['pos']) if cut and cut['name'] in by_name else split
        out.append(f'<line x1="{left}" y1="{y_pub}" x2="{cut_x:.1f}" y2="{y_pub}" class="lane public compressed"/>')
        if cut:
            out.append(f'<text x="{(left + cut_x) / 2:.1f}" y="{y_pub + 26}" class="note" text-anchor="middle">'
                       f'{cut["commits"]} Commits, bereinigt</text>')
        last_x = cut_x
        for t in public['tags']:
            in_archive = by_name.get(t['name'])
            tip = f'<title>{esc(t["name"])} · {esc(t["sha"][:9])} · {esc(t["subject"])}</title>'
            if not t['handover'] and t is not cut:  # older release tags that came with the rewrite
                if in_archive:
                    out.append(f'<circle cx="{x(in_archive["pos"]):.1f}" cy="{y_pub}" r="3" class="tick public">{tip}</circle>')
                continue
            tx = x(in_archive['pos']) if in_archive else last_x + 30
            if t is not cut:
                out.append(f'<line x1="{last_x:.1f}" y1="{y_pub}" x2="{tx:.1f}" y2="{y_pub}" class="lane public"/>')
            out.append(f'<circle cx="{tx:.1f}" cy="{y_pub}" r="7" class="dot public">{tip}</circle>')
            out.append(f'<text x="{tx:.1f}" y="{y_pub + 26}" class="tag" text-anchor="{"start" if t is cut else "middle"}">'
                       f'{esc(t["name"])}</text>')
            if in_archive:
                ax, kind = x(in_archive['pos']), ('cut' if t is cut else 'handover')
                out.append(f'<path d="M{ax:.1f},{y_arch + 9} C{ax:.1f},{(y_arch + y_pub) / 2:.1f} {tx:.1f},{(y_arch + y_pub) / 2:.1f} '
                           f'{tx:.1f},{y_pub - 10}" class="flow {kind}" marker-end="url(#arrow)"/>')
                out.append(f'<text x="{(ax + tx) / 2 + 6:.1f}" y="{(y_arch + y_pub) / 2 + 4:.1f}" class="flow-label">'
                           f'{"Rewrite" if t is cut else "Übergabe"}</text>')
            last_x = tx
    for name in (pending or {}).get('open_tags', []):
        ax = x(by_name[name]['pos'])
        out.append(f'<path d="M{ax:.1f},{y_arch + 9} L{ax:.1f},{y_pub - 10}" class="flow pending" marker-end="url(#arrow)"/>')
        out.append(f'<text x="{ax + 6:.1f}" y="{(y_arch + y_pub) / 2 + 4:.1f}" class="flow-label pending">offen</text>')
    out.append('</svg>')
    return '\n'.join(out)


def line_card(title, line, role, extra):
    if not line:
        body = '<p class="muted">noch nicht angelegt</p>' if role == 'public' else '<p class="muted">nicht lesbar</p>'
        return f'<div class="card {role}"><h3>{esc(title)}</h3>{body}{extra}</div>'
    last = line['tags'][-1]['name'] if line['tags'] else '—'
    rows = [
        ('Pfad', f'<code>{esc(line["repo"])}</code>'),
        ('main', f'<code>{esc(line["head"]["sha"][:9])}</code> {esc(line["head"]["subject"][:70])}'),
        ('Commits', f'{line["count"]}'),
        ('letztes Tag', esc(last)),
    ]
    if line['since_tag'] is not None:
        rows.append(('seit dem Tag', f'{line["since_tag"]} Commit(s)'))
    if line['unpushed'] is not None:
        rows.append(('nicht gesichert', f'{line["unpushed"]} Commit(s) vor <code>{esc(line["upstream"])}</code>'))
    table = ''.join(f'<tr><th>{k}</th><td>{v}</td></tr>' for k, v in rows)
    return f'<div class="card {role}"><h3>{esc(title)}</h3><table>{table}</table>{extra}</div>'


STATE_ICON = {'ok': '✓', 'failed': '✗', 'open': '●', 'none': '○'}


def render(archive, public, events, mode, generated):
    pending = pending_stat(archive, public)
    states = station_states(archive, public, events, mode)
    status, summary = overall(archive, public, events, pending)

    if pending and pending['base']:
        gap = (f'<p>Seit <b>{esc(pending["base"])}</b> (zuletzt übergeben): <b>{pending["commits"]}</b> Commit(s), '
               f'<b>{pending["files"]}</b> Datei(en) geändert. Eine Übergabe fasst das zu einem Commit zusammen.</p>')
    elif pending:
        gap = '<p>Noch nichts übergeben: Der erste Schritt ist der Erstschnitt mit dem ersten Release-Tag.</p>'
    else:
        gap = ''
    if pending and pending['open_tags']:
        gap += f'<p>Getaggt, aber nicht übergeben: <b>{esc(", ".join(pending["open_tags"]))}</b></p>'
    gap += f'<p class="next">Nächster Schritt: {next_step(archive, public, pending)}</p>'

    stations = []
    for key, title, where, checks in STATIONS:
        state, text = states.get(key, ('none', ''))
        items = ''.join(f'<li>{esc(c)}</li>' for c in checks)
        stations.append(f'<div class="station {state}" id="st-{key}"><div class="st-head"><span class="icon">{STATE_ICON[state]}</span>'
                        f'<b>{esc(title)}</b></div><div class="where">{esc(where)}</div><ul>{items}</ul>'
                        f'<div class="state-text">{esc(text)}</div></div>')

    last_checks = ''
    for kind, label in (('cut', 'Rewrite-Gegenprobe'), ('handover', 'Übergabe-Gates'), ('archive-gate', 'Pre-Push-Gate')):
        e = last_event(events, kind)
        if not e or not e.get('checks'):
            continue
        rows = ''.join(f'<li class="{c["result"]}">{STATE_ICON.get("ok" if c["result"] == "ok" else "failed" if c["result"] == "failed" else "none")} '
                       f'{esc(c["name"])}{(" — " + esc(c["detail"])) if c.get("detail") else ""}</li>' for c in e['checks'])
        last_checks += (f'<details><summary>{esc(label)} · {esc(e.get("tag") or "-")} · {esc(short_time(e["time"]))}</summary>'
                        f'<ul class="checks">{rows}</ul></details>')

    kinds = []
    for key, title, sub, path, text in KINDS:
        chips = ''.join(f'<span class="chip{" on" if s in path else ""}">{esc(STATION_TITLES[s])}</span>'
                        for s, _, _, _ in STATIONS)
        relevant = {'cut': ('cut',), 'release': ('push',), 'nothing': ('handover',), 'discard': ('discard',),
                    'backport': ('backport',)}[key]
        seen = [e for e in events if e.get('kind') in relevant
                and (key != 'nothing' or e.get('status') == 'nothing')]
        last = (f'zuletzt {esc(seen[-1].get("tag") or "-")} am {esc(short_time(seen[-1]["time"]))}'
                if seen else 'noch nie')
        kinds.append(f'<div class="kind"><div class="kind-head"><b>{esc(title)}</b> <span class="muted">{esc(sub)}</span>'
                     f'<span class="last">{last}</span></div><div class="chips">{chips}</div><p>{esc(text)}</p></div>')

    history = ''.join(
        f'<tr class="{"failed" if e.get("status") == "failed" else ""}"><td>{esc(short_time(e.get("time")))}</td>'
        f'<td>{esc(KIND_WORDS.get(e.get("kind"), e.get("kind")))}</td><td>{esc(e.get("tag") or "—")}</td>'
        f'<td>{esc(status_word(e.get("status")))}</td>'
        f'<td>{checks_count(e)}</td><td>{esc(e.get("note") or "")}</td></tr>'
        for e in reversed(events[-60:]))
    history = history or '<tr><td colspan="6" class="muted">noch keine Läufe</td></tr>'

    mode_label = 'Probe (lokale Bare-Repos statt GitHub)' if mode == 'probe' else 'Live'
    return PAGE.format(
        status=status, status_word={'GREEN': 'alles übergeben', 'YELLOW': 'etwas ist offen', 'RED': 'Lauf fehlgeschlagen'}[status],
        summary=esc(summary), mode=esc(mode_label), mode_class=esc(mode), generated=esc(generated),
        lanes=lanes_svg(archive, public, pending),
        archive_card=line_card('Archiv · Arbeits-Repo', archive, 'archive', ''),
        public_card=line_card('Öffentliches Repo', public, 'public', ''),
        gap=gap, stations=''.join(stations), last_checks=last_checks or '<p class="muted">Noch keine Prüfergebnisse.</p>',
        kinds=''.join(kinds), history=history)


def next_step(archive, public, pending):
    if not archive:
        return 'Arbeits-Repo prüfen – es ist nicht lesbar.'
    if archive['unpushed']:
        return f'{archive["unpushed"]} Commit(s) ins Archiv pushen (Sicherung).'
    if not public or not public['tags']:
        if archive['tags'] and not archive['since_tag']:
            return f'Workflow <b>„Release: Erstschnitt“</b> mit <code>tag={esc(archive["tags"][-1]["name"])}</code>.'
        return ('Release-Tag auf main setzen (<code>scripts/release.sh</code>), dann Workflow '
                '<b>„Release: Erstschnitt“</b> mit diesem Tag.')
    if pending and pending['open_tags']:
        return f'Workflow <b>„Release: Übergabe“</b> mit <code>tag={esc(pending["open_tags"][0])}</code>.'
    if archive['since_tag']:
        return (f'Nichts offen. {archive["since_tag"]} Commit(s) seit {esc(archive["tags"][-1]["name"])} – '
                'für das nächste Release erst taggen (<code>scripts/release.sh</code>), dann übergeben.')
    return 'Nichts offen – beide Linien stehen auf demselben Release.'


def checks_count(e):
    checks = e.get('checks') or []
    if not checks:
        return ''
    failed = sum(1 for c in checks if c['result'] == 'failed')
    return f'{len(checks) - failed}/{len(checks)}' + (f' · <b class="bad">{failed} Fehler</b>' if failed else '')


PAGE = '''<!doctype html>
<html lang="de"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Release-Lagebild</title>
<style>
:root {{ --bg:#f6f7f9; --card:#fff; --text:#1d2330; --muted:#6a7282; --line:#d8dce3;
  --archive:#3b6fd8; --public:#1f9d6b; --ok:#1f9d6b; --bad:#d23c3c; --open:#c98a12; --none:#9aa1ad; }}
@media (prefers-color-scheme: dark) {{ :root {{ --bg:#14171d; --card:#1c2029; --text:#e6e9ef; --muted:#9aa3b2;
  --line:#333a47; --archive:#6f9bff; --public:#3fcf95; --ok:#3fcf95; --bad:#ff6b6b; --open:#f0b43c; --none:#6b7280; }} }}
* {{ box-sizing:border-box; }}
body {{ margin:0; font:15px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif; background:var(--bg); color:var(--text); }}
main {{ max-width:1180px; margin:0 auto; padding:28px 24px 60px; }}
h1 {{ font-size:26px; margin:0; }} h2 {{ font-size:18px; margin:34px 0 12px; }} h3 {{ font-size:15px; margin:0 0 8px; }}
header {{ display:flex; gap:16px; align-items:center; flex-wrap:wrap; }}
.badge {{ padding:3px 10px; border-radius:999px; font-size:13px; border:1px solid var(--line); }}
.badge.probe {{ border-color:var(--open); color:var(--open); }}
.ampel {{ padding:4px 12px; border-radius:6px; color:#fff; font-weight:600; font-size:13px; }}
.ampel.GREEN {{ background:var(--ok); }} .ampel.YELLOW {{ background:var(--open); }} .ampel.RED {{ background:var(--bad); }}
.summary {{ color:var(--muted); margin:6px 0 0; }}
.muted {{ color:var(--muted); fill:var(--muted); }}
.panel {{ background:var(--card); border:1px solid var(--line); border-radius:10px; padding:16px; }}
svg {{ width:100%; height:auto; display:block; }}
.lane {{ stroke-width:6; stroke-linecap:round; }} .lane.archive {{ stroke:var(--archive); }} .lane.public {{ stroke:var(--public); }}
.lane.compressed {{ stroke-dasharray:2 7; }} .lane.missing {{ stroke:var(--line); stroke-dasharray:6 6; stroke-width:3; }}
.dot.archive, .tick.archive {{ fill:var(--archive); }} .tick.public {{ fill:var(--public); }} .dot.public {{ fill:var(--public); }} .dot.head {{ fill:var(--card); stroke:var(--archive); stroke-width:3; }}
.lane-label {{ font-weight:700; font-size:14px; fill:var(--text); }} .tag {{ font-size:13px; font-weight:600; fill:var(--text); }}
.note {{ font-size:12px; fill:var(--muted); }} .bracket {{ stroke:var(--open); stroke-width:2; stroke-dasharray:4 3; }}
.flow {{ fill:none; stroke-width:2; }} .flow.cut {{ stroke:var(--muted); }} .flow.handover {{ stroke:var(--public); }}
.flow.pending {{ stroke:var(--open); stroke-dasharray:5 4; }} .arrowhead {{ fill:var(--muted); }}
.flow-label {{ font-size:12px; fill:var(--muted); }} .flow-label.pending {{ fill:var(--open); }}
.cards {{ display:grid; grid-template-columns:1fr 1fr; gap:14px; margin-top:14px; }}
.card {{ background:var(--card); border:1px solid var(--line); border-radius:10px; padding:14px; border-top:4px solid var(--line); }}
.card.archive {{ border-top-color:var(--archive); }} .card.public {{ border-top-color:var(--public); }}
.card table {{ border-collapse:collapse; width:100%; font-size:14px; }} .card th {{ text-align:left; color:var(--muted); font-weight:500; width:130px; vertical-align:top; padding:2px 0; }}
.card td {{ padding:2px 0; }} code {{ font:13px ui-monospace,SFMono-Regular,Menlo,monospace; }}
.gap {{ margin-top:14px; }}
.stations {{ display:grid; grid-template-columns:repeat(4, 1fr); gap:12px; }}
.station {{ background:var(--card); border:1px solid var(--line); border-radius:10px; padding:12px; position:relative; border-left:5px solid var(--none); }}
.station.ok {{ border-left-color:var(--ok); }} .station.failed {{ border-left-color:var(--bad); }} .station.open {{ border-left-color:var(--open); }}
.st-head {{ display:flex; gap:8px; align-items:center; }} .icon {{ font-weight:700; }}
.station.ok .icon {{ color:var(--ok); }} .station.failed .icon {{ color:var(--bad); }} .station.open .icon {{ color:var(--open); }} .station.none .icon {{ color:var(--none); }}
.where {{ font-size:12px; color:var(--muted); }} .station ul {{ margin:8px 0; padding-left:18px; font-size:13px; }}
.state-text {{ font-size:13px; border-top:1px solid var(--line); padding-top:6px; }}
details {{ background:var(--card); border:1px solid var(--line); border-radius:8px; padding:8px 12px; margin-top:8px; }}
summary {{ cursor:pointer; font-weight:600; }} ul.checks {{ list-style:none; padding:0; margin:8px 0 0; font-size:13px; columns:2; }}
ul.checks li.ok {{ color:var(--text); }} ul.checks li.failed {{ color:var(--bad); font-weight:600; }} ul.checks li.skipped {{ color:var(--muted); }}
.kind {{ background:var(--card); border:1px solid var(--line); border-radius:10px; padding:12px 14px; margin-bottom:10px; }}
.kind-head {{ display:flex; gap:8px; align-items:baseline; }} .kind-head .last {{ margin-left:auto; font-size:13px; color:var(--muted); }}
.chips {{ display:flex; flex-wrap:wrap; gap:6px; margin:8px 0; }}
.chip {{ font-size:12px; padding:2px 8px; border-radius:999px; border:1px dashed var(--line); color:var(--muted); }}
.chip.on {{ border:1px solid var(--archive); color:var(--text); background:color-mix(in srgb, var(--archive) 12%, transparent); }}
.kind p {{ margin:0; font-size:14px; }}
table.history {{ width:100%; border-collapse:collapse; background:var(--card); border:1px solid var(--line); border-radius:10px; font-size:14px; }}
table.history th, table.history td {{ text-align:left; padding:6px 10px; border-bottom:1px solid var(--line); }}
table.history tr.failed td {{ color:var(--bad); }} .bad {{ color:var(--bad); }}
footer {{ margin-top:30px; font-size:13px; color:var(--muted); }}
.next {{ border-top:1px solid var(--line); padding-top:10px; margin-bottom:0; }}
@media (max-width:900px) {{ .stations {{ grid-template-columns:1fr 1fr; }} .cards {{ grid-template-columns:1fr; }} }}
</style></head><body><main>
<header><h1>Release-Lagebild</h1><span class="ampel {status}">{status_word}</span>
<span class="badge {mode_class}">{mode}</span><span class="muted">Stand {generated}</span></header>
<p class="summary">{summary}</p>

<h2>Wo die beiden Linien stehen</h2>
<div class="panel">{lanes}</div>
<div class="cards">{archive_card}{public_card}</div>
<div class="panel gap">{gap}</div>

<h2>Prüfungen auf dem Weg</h2>
<div class="stations">{stations}</div>
{last_checks}

<h2>Wie die Release-Arten laufen</h2>
{kinds}

<h2>Verlauf</h2>
<table class="history"><thead><tr><th>Zeit</th><th>Art</th><th>Tag</th><th>Ergebnis</th><th>Prüfungen</th><th>Notiz</th></tr></thead>
<tbody>{history}</tbody></table>

<footer>Erzeugt von <code>scripts/release-overview.py</code> (Workflow „Release: Lagebild“), deterministisch aus den
Repos und dem Ereignisprotokoll, ohne KI. Neu erzeugen: Workflow starten.</footer>
</main></body></html>
'''


def cmd_render(args):
    archive = read_line(args.archive, args.archive_ref, args.archive_upstream)
    public = read_line(args.public, args.public_ref, args.public_upstream) if args.public else None
    events = read_events(args.log)
    generated = dt.datetime.now().astimezone().strftime('%d.%m.%Y %H:%M')
    page = render(archive, public, events, args.mode, generated)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(page, encoding='utf-8')
    status, summary = overall(archive, public, events, pending_stat(archive, public))
    if args.status_out:
        Path(args.status_out).write_text(f'{status}\n{summary}\n', encoding='utf-8')
    print(f'Lagebild: {out}')
    print(f'overview_status: {status}')
    print(f'overview_summary: {summary}')
    return 0


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    sub = p.add_subparsers(dest='cmd', required=True)
    e = sub.add_parser('event')
    e.add_argument('--log', required=True)
    e.add_argument('--kind', required=True, choices=EVENT_KINDS)
    e.add_argument('--tag', default='')
    e.add_argument('--status', required=True, choices=EVENT_STATUS)
    e.add_argument('--output')
    e.add_argument('--note')
    e.add_argument('--commit')
    r = sub.add_parser('render')
    r.add_argument('--archive', required=True)
    r.add_argument('--archive-ref', default='main')
    r.add_argument('--archive-upstream')
    r.add_argument('--public')
    r.add_argument('--public-ref', default='main')
    r.add_argument('--public-upstream')
    r.add_argument('--log')
    r.add_argument('--out', required=True)
    r.add_argument('--mode', choices=('probe', 'live'), default='live')
    r.add_argument('--status-out')
    args = p.parse_args(argv)
    return cmd_event(args) if args.cmd == 'event' else cmd_render(args)


if __name__ == '__main__':
    sys.exit(main())
