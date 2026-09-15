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
});
