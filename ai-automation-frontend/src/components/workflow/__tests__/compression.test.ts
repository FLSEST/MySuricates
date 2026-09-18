import { describe, it, expect } from 'vitest';
import { applyPreset, compressedSourcesUsed, compressionSummary, COMPRESS_PRESETS, isCompressionBlock } from '../compression';
import type { BlockDefinition } from '../../../types';

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
