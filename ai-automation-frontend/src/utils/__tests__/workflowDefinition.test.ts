import { describe, it, expect } from 'vitest';
import { buildDefinitionText, blockLineRanges } from '../workflowDefinition';
import type { BlockDefinition } from '../../types';

const blocks: BlockDefinition[] = [
  { type: 'shell', config: { command: 'echo eins' } },
  { type: 'simple', config: { command: 'Fasse zusammen' } },
  { type: 'approval', config: { question: 'Freigeben?' } },
];

describe('blockLineRanges', () => {
  it('covers every block without gaps and matches the document', () => {
    const text = buildDefinitionText(blocks);
    const lines = text.split('\n');
    const ranges = blockLineRanges(blocks);

    // Line 1 is the opening bracket, the last one the closing bracket
    expect(lines[0]).toBe('[');
    expect(lines[lines.length - 1]).toBe(']');

    // Every block starts with "{" and the computed start line matches that
    for (let i = 0; i < blocks.length; i++) {
      const r = ranges.get(i)!;
      expect(lines[r.start - 1]!.trim()).toBe('{');
      expect(lines[r.end - 1]!.trim()).toMatch(/^\}/); // "}" or "},"
    }

    // Ranges are ascending and touch each other (no overlap/gap)
    expect(ranges.get(0)!.start).toBe(2);
    expect(ranges.get(1)!.start).toBe(ranges.get(0)!.end + 1);
    expect(ranges.get(2)!.start).toBe(ranges.get(1)!.end + 1);
    // Last block ends exactly before the closing bracket
    expect(ranges.get(2)!.end).toBe(lines.length - 1);
  });

  it('returns no ranges for an empty workflow', () => {
    expect(blockLineRanges([]).size).toBe(0);
  });
});
