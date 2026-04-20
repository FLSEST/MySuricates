export type IterationEntry = Record<string, unknown>;

export type DiffKind = 'added' | 'changed';

export interface FieldDiff {
  key: 'analysis' | 'strategy' | 'summary' | 'issues';
  group: 'decomposition' | 'evaluation';
  kind: DiffKind;
  before: string;
  after: string;
}

function getNested(entry: IterationEntry | null | undefined, path: [string, string]): unknown {
  if (!entry) return undefined;
  const group = entry[path[0]];
  if (!group || typeof group !== 'object') return undefined;
  return (group as Record<string, unknown>)[path[1]];
}

function stringOrEmpty(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function issuesToString(value: unknown): string {
  if (!Array.isArray(value)) return '';
  return value
    .map(v => (typeof v === 'string' ? v : JSON.stringify(v)))
    .filter(v => v.length > 0)
    .join('\n');
}

const FIELDS: Array<{
  key: FieldDiff['key'];
  group: FieldDiff['group'];
  path: [string, string];
  read: (raw: unknown) => string;
}> = [
  { key: 'analysis', group: 'decomposition', path: ['decomposition', 'analysis'], read: stringOrEmpty },
  { key: 'strategy', group: 'decomposition', path: ['decomposition', 'strategy'], read: stringOrEmpty },
  { key: 'summary', group: 'evaluation', path: ['evaluation', 'summary'], read: stringOrEmpty },
  { key: 'issues', group: 'evaluation', path: ['evaluation', 'issues'], read: issuesToString },
];

export function computeIterationDiff(
  prev: IterationEntry | null,
  curr: IterationEntry
): FieldDiff[] {
  const diffs: FieldDiff[] = [];
  for (const field of FIELDS) {
    const before = field.read(getNested(prev, field.path));
    const after = field.read(getNested(curr, field.path));
    if (before === after) continue;
    if (!before && !after) continue;
    diffs.push({
      key: field.key,
      group: field.group,
      kind: !before ? 'added' : 'changed',
      before,
      after,
    });
  }
  return diffs;
}
