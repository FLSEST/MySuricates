import { describe, it, expect } from 'vitest';
import { applyPreset, compressedSourcesUsed, compressionSummary, COMPRESS_PRESETS, isCompressionBlock } from '../compression';
import type { BlockDefinition } from '../../../types';

/**
 * Faithful mirror of the backend stages a log preset uses — `TextNormalizeStage`,
 * `LinesFilterStage`, `TextTruncateStage` (ai-automation-backend, executor/compress). It lets a
 * preset be checked against a real log fixture here instead of only by the shape of its config.
 * Regexes in the presets are Java syntax; the ones used here are identical in JavaScript.
 */
function runStages(stages: Array<Record<string, unknown>>, input: string): string {
  return stages.reduce((text, stage) => {
    switch (stage.op) {
      case 'text.normalize': return normalize(text);
      case 'lines.filter': return filterLines(text, stage);
      case 'text.truncate': {
        const max = stage.max_chars as number;
        if (text.length > max) throw new Error(`fixture longer than max_chars ${max} — truncation not emulated`);
        return text;
      }
      default: throw new Error(`stage ${String(stage.op)} not emulated`);
    }
  }, input);
}

/** ANSI/CR dropped, trailing whitespace stripped, runs of blank lines collapsed to one. */
function normalize(text: string): string {
  const out: string[] = [];
  let previousBlank = true; // also drops leading blank lines
  for (const line of text.replace(/\r\n?/g, '\n').split('\n')) {
    const stripped = line.replace(/\s+$/, '');
    if (stripped === '' && previousBlank) continue;
    out.push(stripped);
    previousBlank = stripped === '';
  }
  while (out.length > 0 && out[out.length - 1] === '') out.pop();
  return out.join('\n');
}

/** Exclude wins over include; with an include list, dropped runs leave a "[… N Zeilen …]" marker. */
function filterLines(text: string, stage: Record<string, unknown>): string {
  const include = ((stage.include as string[]) ?? []).map((r) => new RegExp(r));
  const exclude = ((stage.exclude as string[]) ?? []).map((r) => new RegExp(r));
  const lines = text.split('\n');
  const out: string[] = [];
  let gap = 0;
  let kept = 0;
  for (const line of lines) {
    const keep = !exclude.some((r) => r.test(line)) && (include.length === 0 || include.some((r) => r.test(line)));
    if (!keep) { gap += 1; continue; }
    if (gap > 0 && include.length > 0) out.push(`[… ${gap} Zeilen …]`);
    gap = 0;
    out.push(line);
    kept += 1;
  }
  if (gap > 0 && include.length > 0 && kept > 0) out.push(`[… ${gap} Zeilen …]`);
  return out.join('\n');
}

describe('compression helpers', () => {
  it('recognises both compression types', () => {
    expect(isCompressionBlock('compress')).toBe(true);
    expect(isCompressionBlock('compress-ai')).toBe(true);
    expect(isCompressionBlock('shell')).toBe(false);
  });

  it('summarises a rule chain and an AI compression', () => {
    expect(compressionSummary('compress', {
      source: 'backend_tests', max_chars: 20000,
      stages: [{ op: 'text.normalize' }, { op: 'lines.dedupe' }],
    }, 'AI')).toBe('backend_tests → text.normalize › lines.dedupe (≤ 20000)');
    expect(compressionSummary('compress-ai', { source: 'kompakt', instruction: 'Fehler nennen' }, 'KI'))
      .toBe('kompakt → KI „Fehler nennen“');
    expect(compressionSummary('compress', {}, 'AI')).toBe('? → ');
  });

  it('applies a preset to the stages and keeps source and max_chars', () => {
    const next = JSON.parse(applyPreset('{"source":"backend_tests","max_chars":9000,"stages":[]}', 'java-log'));

    expect(next.source).toBe('backend_tests');
    expect(next.max_chars).toBe(9000);
    expect(next.stages).toEqual(COMPRESS_PRESETS['java-log']);
  });

  it('strips both log timestamp forms and normalises again after the filter (java-log)', () => {
    const stages = COMPRESS_PRESETS['java-log'];
    const ops = stages.map((s) => s.op as string);
    const replace = stages.find((s) => s.op === 'lines.replace') as { pattern: string };
    const timestamps = new RegExp(replace.pattern);

    // the filter leaves runs of blank lines behind — normalize runs again right after it
    expect(ops.filter((op) => op === 'text.normalize')).toHaveLength(2);
    expect(ops[ops.indexOf('lines.filter') + 1]).toBe('text.normalize');
    // Maven/Spring log lines start with a time only, not with an ISO date
    expect('21:32:58.761 [main] INFO c.a.SomeTest : Started'.replace(timestamps, ''))
      .toBe('[main] INFO c.a.SomeTest : Started');
    expect('2026-09-14T10:54:00.123Z  INFO 4711 --- [main] c.a.SomeTest : Started'.replace(timestamps, ''))
      .toBe('INFO 4711 --- [main] c.a.SomeTest : Started');
    expect('[ERROR] Tests run: 2140, Failures: 2'.replace(timestamps, ''))
      .toBe('[ERROR] Tests run: 2140, Failures: 2');
  });

  it('keeps only the verdict lines of a Maven log (maven-summary)', () => {
    const log = [
      '[INFO] Scanning for projects...',
      '[INFO] ',
      '[INFO] --- surefire:3.2.5:test (default-test) @ ai-automation-backend ---',
      '[INFO] Running com.automation.executor.CompressBlockTest',
      '21:32:58.761 [main] INFO c.a.Boot -- Started in 4.2 s',
      '[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0 -- in com.automation.executor.CompressBlockTest',
      '[INFO] Running com.automation.workflow.WorkflowExecutorTest',
      '[ERROR] Tests run: 3, Failures: 1, Errors: 0, Skipped: 0 <<< FAILURE! -- in com.automation.workflow.WorkflowExecutorTest',
      '[ERROR] com.automation.workflow.WorkflowExecutorTest.runsBlocks -- Time elapsed: 0.04 s <<< FAILURE!',
      'org.opentest4j.AssertionFailedError: expected: <2> but was: <1>',
      '[ERROR] \tat com.automation.workflow.WorkflowExecutorTest.runsBlocks(WorkflowExecutorTest.java:42)',
      '[ERROR] \t... 17 more',
      '[INFO] ',
      '[INFO] Results:',
      '[INFO] ',
      '[ERROR] Failures: ',
      '[ERROR]   WorkflowExecutorTest.runsBlocks:42 expected: <2> but was: <1>',
      '[INFO] ',
      '[ERROR] Tests run: 15, Failures: 1, Errors: 0, Skipped: 0',
      '[INFO] ',
      '[INFO] ------------------------------------------------------------------------',
      '[INFO] BUILD FAILURE',
      '[INFO] ------------------------------------------------------------------------',
      '[INFO] Total time:  2:14 min',
    ].join('\n');

    expect(runStages(COMPRESS_PRESETS['maven-summary'], log)).toBe([
      '[… 5 Zeilen …]',
      '[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0 -- in com.automation.executor.CompressBlockTest',
      '[… 1 Zeilen …]',
      '[ERROR] Tests run: 3, Failures: 1, Errors: 0, Skipped: 0 <<< FAILURE! -- in com.automation.workflow.WorkflowExecutorTest',
      '[ERROR] com.automation.workflow.WorkflowExecutorTest.runsBlocks -- Time elapsed: 0.04 s <<< FAILURE!',
      '[… 4 Zeilen …]',
      '[INFO] Results:',
      '[… 1 Zeilen …]',
      '[ERROR] Failures:',
      '[ERROR]   WorkflowExecutorTest.runsBlocks:42 expected: <2> but was: <1>',
      '[… 1 Zeilen …]',
      '[ERROR] Tests run: 15, Failures: 1, Errors: 0, Skipped: 0',
      '[… 2 Zeilen …]',
      '[INFO] BUILD FAILURE',
      '[… 2 Zeilen …]',
    ].join('\n'));
  });

  it('maven-summary keeps the stack frames out that java-log would carry in', () => {
    const frames = '[ERROR] \tat com.automation.X.y(X.java:1)\n[ERROR] \t... 3 more';

    expect(runStages(COMPRESS_PRESETS['maven-summary'], frames)).toBe('');
  });

  it('leaves invalid JSON untouched', () => {
    expect(applyPreset('{kaputt', 'npm-build')).toBe('{kaputt');
  });

  it('finds compression blocks a block uses via template or source', () => {
    const blocks: BlockDefinition[] = [
      { type: 'shell', name: 'backend_tests', config: { command: 'mvn test' } },
      { type: 'compress', name: 'backend_kompakt', config: { source: 'backend_tests', stages: [] } },
      { type: 'compress-ai', name: 'kern', config: { source: 'backend_kompakt' } },
      { type: 'simple', name: 'report', config: { command: 'A {{ block.kern.output }} B {{block.backend_kompakt.output}} C {{ block.backend_tests.output }}' } },
    ];

    expect(compressedSourcesUsed(blocks[3], blocks)).toEqual(['kern', 'backend_kompakt']);
    expect(compressedSourcesUsed(blocks[2], blocks)).toEqual(['backend_kompakt']);
    expect(compressedSourcesUsed(blocks[1], blocks)).toEqual([]);
  });

  it('counts only the symmetric spellings the backend resolves', () => {
    const blocks: BlockDefinition[] = [
      { type: 'compress', name: 'kompakt', config: { source: 'x', stages: [] } },
      { type: 'compress', name: 'Build Report', config: { source: 'x', stages: [] } },
      { type: 'simple', name: 'asym', config: { command: 'A {{ block.kompakt.output}} B {{block.kompakt.output }}' } },
      { type: 'simple', name: 'spaced', config: { command: 'R {{ block.Build Report.output }}' } },
    ];

    expect(compressedSourcesUsed(blocks[2], blocks)).toEqual([]);
    expect(compressedSourcesUsed(blocks[3], blocks)).toEqual(['Build Report']);
  });
});
