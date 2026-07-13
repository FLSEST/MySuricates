import type { BlockDefinition } from '../types';

export interface LineRange {
  start: number;
  end: number;
}

/**
 * Kanonisches Definitions-Dokument: alle Blöcke als ein pretty-printed JSON-Array.
 * Referenz für die Zeilennummern, die das Diagramm je Kasten anzeigt.
 */
export function buildDefinitionText(blocks: BlockDefinition[]): string {
  return JSON.stringify(blocks, null, 2);
}

/**
 * Zeilenbereich (1-basiert) jedes Top-Level-Blocks im Definitions-Dokument von
 * {@link buildDefinitionText}. Deterministisch abgeleitet aus der Zeilenzahl der
 * einzelnen Blöcke: Zeile 1 ist `[`, danach folgt Block 0 usw. Ein an einen Block
 * angehängtes Komma verändert die Zeilenzahl nicht.
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
