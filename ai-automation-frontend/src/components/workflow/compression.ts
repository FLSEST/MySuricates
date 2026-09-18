import type { BlockDefinition } from '../../types';

export function isCompressionBlock(type: string): boolean {
  return type === 'compress' || type === 'compress-ai';
}

export type CompressPresetId = 'java-log' | 'maven-summary' | 'npm-build' | 'feed-json' | 'ai-report';

/** Ready-made stage chains for the compress block (spec § 7.1). Regexes are Java syntax. */
export const COMPRESS_PRESETS: Record<CompressPresetId, Array<Record<string, unknown>>> = {
  'java-log': [
    { op: 'text.normalize' },
    { op: 'lines.filter', exclude: ['^\\s+at ', '^\\s+\\.\\.\\. \\d+ more', ':: Spring Boot ::', "^[\\s/\\\\|()_'`=.,-]+$"] },
    // again after the filter: removed lines leave runs of blank lines behind
    { op: 'text.normalize' },
    // ISO date or time only ("21:32:58.761 [main] …", the usual Maven/Spring console form)
    { op: 'lines.replace', pattern: '^(?:\\d{4}-\\d{2}-\\d{2}[T ][\\d:.,]+Z?|\\d{2}:\\d{2}:\\d{2}[.,]\\d+)\\s*', replacement: '' },
    { op: 'lines.dedupe', count: true },
    { op: 'text.truncate', max_chars: 18000, keep: 'head_tail' },
  ],
  // java-log removes noise and keeps the rest; this one keeps the verdict and nothing else —
  // what a health report needs from a Maven run (TODO 643d0ebd, pilot self-care-platform-health).
  'maven-summary': [
    { op: 'text.normalize' },
    {
      op: 'lines.filter',
      // exclude wins over include: the [ERROR]-prefixed stack frames are noise, not a finding
      exclude: ['^\\[ERROR\\]\\s+at ', '^\\[ERROR\\]\\s+\\.\\.\\. \\d+ more'],
      // surefire per-class and total lines, failure details, the results header and the build verdict
      include: ['Tests run:', 'FAIL', '^\\[ERROR\\]', '^\\[INFO\\] Results:', 'BUILD (?:SUCCESS|FAILURE)'],
    },
    { op: 'text.truncate', max_chars: 8000, keep: 'head_tail' },
  ],
  'npm-build': [
    { op: 'text.normalize' },
    { op: 'lines.dedupe', count: true, consecutive_only: true },
    { op: 'lines.tail', lines: 200 },
    { op: 'text.truncate', max_chars: 12000, keep: 'tail' },
  ],
  'feed-json': [
    { op: 'json.sort', path: 'items', by: 'date', order: 'desc' },
    { op: 'json.limit', path: 'items', count: 30 },
    { op: 'json.to_lines', path: 'items', template: '{title} — {url}' },
  ],
  'ai-report': [
    { op: 'markdown.section', headings: ['score', 'gesamt', 'empfehlung', 'fazit', 'summary', 'recommendation'] },
    { op: 'text.truncate', max_chars: 4000, keep: 'head' },
  ],
};

/** Replaces the stages of a compress config (JSON text); other keys stay. Invalid JSON is returned unchanged. */
export function applyPreset(configJson: string, preset: CompressPresetId): string {
  try {
    const config = JSON.parse(configJson) as Record<string, unknown>;
    return JSON.stringify({ ...config, stages: COMPRESS_PRESETS[preset] }, null, 2);
  } catch {
    return configJson;
  }
}

/** One-line preview for the block list, e.g. "backend_tests → text.normalize › lines.dedupe (≤ 20000)". */
export function compressionSummary(type: string, config: Record<string, unknown>, aiLabel: string): string {
  const source = typeof config.source === 'string' && config.source ? config.source : '?';
  const max = typeof config.max_chars === 'number' ? ` (≤ ${config.max_chars})` : '';
  if (type === 'compress-ai') {
    const instruction = typeof config.instruction === 'string' && config.instruction ? ` „${config.instruction}“` : '';
    return `${source} → ${aiLabel}${instruction}${max}`;
  }
  const ops = Array.isArray(config.stages)
    ? config.stages.map((s) => (s && typeof s === 'object' && typeof (s as { op?: unknown }).op === 'string' ? (s as { op: string }).op : '?'))
    : [];
  return `${source} → ${ops.join(' › ')}${max}`;
}

/** Names of compression blocks whose output this block uses — via {{ block.<name>.output }} or as its own source. */
export function compressedSourcesUsed(block: BlockDefinition, blocks: BlockDefinition[]): string[] {
  const names = new Set(blocks.filter((b) => isCompressionBlock(b.type) && b.name).map((b) => b.name as string));
  if (names.size === 0) return [];
  const found: string[] = [];
  const add = (name: string) => {
    if (names.has(name) && name !== block.name && !found.includes(name)) found.push(name);
  };
  if (isCompressionBlock(block.type) && typeof block.config.source === 'string') add(block.config.source);
  // Same spellings as the backend resolver: {{block.x.output}} or {{ block.x.output }}, names may contain spaces
  for (const match of JSON.stringify(block.config).matchAll(/\{\{( ?)block\.([^{}]+?)\.output\1\}\}/g)) add(match[2]!);
  return found;
}
