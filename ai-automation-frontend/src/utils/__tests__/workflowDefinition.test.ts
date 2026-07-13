import { describe, it, expect } from 'vitest';
import { buildDefinitionText, blockLineRanges } from '../workflowDefinition';
import type { BlockDefinition } from '../../types';

const blocks: BlockDefinition[] = [
  { type: 'shell', config: { command: 'echo eins' } },
  { type: 'simple', config: { command: 'Fasse zusammen' } },
  { type: 'approval', config: { question: 'Freigeben?' } },
];

describe('blockLineRanges', () => {
  it('deckt jeden Block lückenlos ab und stimmt mit dem Dokument überein', () => {
    const text = buildDefinitionText(blocks);
    const lines = text.split('\n');
    const ranges = blockLineRanges(blocks);

    // Zeile 1 ist die öffnende Klammer, die letzte die schließende
    expect(lines[0]).toBe('[');
    expect(lines[lines.length - 1]).toBe(']');

    // Jeder Block beginnt mit "{" und die berechnete Startzeile trifft das zu
    for (let i = 0; i < blocks.length; i++) {
      const r = ranges.get(i)!;
      expect(lines[r.start - 1]!.trim()).toBe('{');
      expect(lines[r.end - 1]!.trim()).toMatch(/^\}/); // "}" oder "},"
    }

    // Bereiche sind aufsteigend und berühren sich (kein Überlappen/Loch)
    expect(ranges.get(0)!.start).toBe(2);
    expect(ranges.get(1)!.start).toBe(ranges.get(0)!.end + 1);
    expect(ranges.get(2)!.start).toBe(ranges.get(1)!.end + 1);
    // Letzter Block endet genau vor der schließenden Klammer
    expect(ranges.get(2)!.end).toBe(lines.length - 1);
  });

  it('liefert für einen leeren Workflow keine Bereiche', () => {
    expect(blockLineRanges([]).size).toBe(0);
  });
});
