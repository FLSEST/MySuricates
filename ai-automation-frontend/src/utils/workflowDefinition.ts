import type { BlockDefinition } from '../types';

export interface LineRange {
  start: number;
  end: number;
}

/**
 * Canonical definition document: all blocks as one pretty-printed JSON array.
 * Reference for the line numbers the diagram shows per box.
 */
export function buildDefinitionText(blocks: BlockDefinition[]): string {
  return JSON.stringify(blocks, null, 2);
}

/**
 * Line range (1-based) of each top-level block in the definition document of
 * {@link buildDefinitionText}. Deterministically derived from the line count of the
 * individual blocks: line 1 is `[`, then block 0 follows, etc. A comma
 * appended to a block does not change the line count.
 */
export function blockLineRanges(blocks: BlockDefinition[]): Map<number, LineRange> {
  const ranges = new Map<number, LineRange>();
  let line = 2; // Zeile 1 = "["
  for (let i = 0; i < blocks.length; i++) {
    const lineCount = JSON.stringify(blocks[i], null, 2).split('\n').length;
    const start = line;
    const end = line + lineCount - 1;
    ranges.set(i, { start, end });
    line = end + 1;
  }
  return ranges;
}
