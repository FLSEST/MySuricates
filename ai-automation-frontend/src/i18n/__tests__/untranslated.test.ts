import { describe, expect, it } from 'vitest';
import { de } from '../de';
import { en } from '../en';

// Guard against keys copied into de.ts without being translated (TODO 99ceb2b5). The type check
// only asks that de.ts has every key of en.ts; `cancel: 'Cancel'` in de.ts passes it and shows
// English in the German UI.
//
// A value that is the same in both files is fine when it consists only of SHARED_TERMS — words the
// German UI keeps on purpose (product terms, names, loanwords). Paths, file names and {placeholders}
// are ignored. Anything else is either translated, or its word goes into SHARED_TERMS if German
// really keeps it.

const SHARED_TERMS = new Set([
  // Product terms
  'agent', 'audit', 'auto-memory', 'auto-repair', 'auto-todo', 'backlog', 'block', 'board', 'chains', 'cockpit',
  'dashboard', 'dead-letter queue', 'factory', 'findings', 'gate', 'insights', 'live', 'pool', 'preset', 'pro',
  'provider', 'quality gates', 'retry', 'run', 'runs', 'session', 'session2workflow', 'sessions', 'shell',
  'simple', 'skill', 'skills', 'sub-workflow', 'tag', 'tags', 'todo', 'todo-trigger', 'todos', 'todotrigger',
  'tokens', 'tracking', 'webhook', 'webhooks', 'workflow', 'workflows',
  // Names
  'caldav', 'discord', 'docker', 'forgejo', 'gitea', 'github', 'gitlab', 'google calendar', 'java',
  'mysuricates', 'ntfy', 'open-meteo', 'slack', 'tailscale', 'telegram', 'todoist',
  // A language named in itself
  'deutsch', 'english',
  // Values shown as they stand in a definition (pool scope badge, see PoolBrowser.test.tsx)
  'external', 'portable',
  // Words German uses as they are
  'api', 'business', 'code', 'container', 'definition', 'deployment', 'details', 'dev', 'devops', 'diagnose',
  'emoji', 'format', 'horizontal', 'icon', 'id', 'import', 'info', 'integration', 'iteration', 'name',
  'offline', 'optional', 'pause', 'person', 'phase', 'plugin', 'repo', 'stable', 'standard', 'start',
  'status', 'step', 'sync', 'system', 'team', 'teams', 'template', 'terminal', 'test', 'trend', 'update',
  'version',
]);

/** Words of a value that need a translation; empty when only paths, placeholders or symbols remain. */
function words(value: string): string[] {
  return value
    .replace(/\{[^}]*\}/g, ' ')
    .split(/\s+/)
    .map((token) => token.replace(/^[()[\]:,&]+|[()[\]:,&]+$/g, '').toLowerCase())
    .filter((token) => !token.includes('/') && !token.includes('.') && /\p{L}{2,}/u.test(token));
}

function isShared(value: string): boolean {
  if (SHARED_TERMS.has(value.trim().toLowerCase())) return true;
  return words(value).every(
    (token) => SHARED_TERMS.has(token) || (token.match(/\p{L}{2,}/gu) ?? []).every((part) => SHARED_TERMS.has(part)),
  );
}

/** Keys whose German and English text are identical and not made of shared terms only. Arrays count by index. */
function untranslatedKeys(english: unknown, german: unknown, prefix = ''): string[] {
  if (typeof english === 'string') {
    return english === german && words(english).length > 0 && !isShared(english) ? [prefix.slice(0, -1)] : [];
  }
  if (typeof english !== 'object' || english === null || typeof german !== 'object' || german === null) return [];
  const other = german as Record<string, unknown>;
  return Object.entries(english).flatMap(([key, value]) => untranslatedKeys(value, other[key], `${prefix}${key}.`));
}

describe('untranslated German text', () => {
  it('has no key whose German text is the English one', () => {
    expect(untranslatedKeys(en, de), 'translate these in de.ts (or add the word to SHARED_TERMS if German keeps it)').toEqual([]);
  });

  it('flags copied English, also in arrays, and accepts shared terms, names, paths and placeholders', () => {
    const english = {
      a: { cancel: 'Cancel', title: 'Top Workflows' },
      ok: { term: 'Workflow {n}', name: 'Google Calendar', path: 'config/application.yml', hyphen: 'Sub-Workflow' },
      columns: ['Open', 'Workflow'],
      translated: 'Save',
    };
    const german = {
      a: { cancel: 'Cancel', title: 'Top Workflows' },
      ok: { term: 'Workflow {n}', name: 'Google Calendar', path: 'config/application.yml', hyphen: 'Sub-Workflow' },
      columns: ['Open', 'Workflow'],
      translated: 'Speichern',
    };

    expect(untranslatedKeys(english, german)).toEqual(['a.cancel', 'a.title', 'columns.0']);
  });
});
