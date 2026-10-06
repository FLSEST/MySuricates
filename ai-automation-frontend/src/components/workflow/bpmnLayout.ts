/** Pure BPMN logic of the diagram view: read XML, top→bottom layout, wrap labels, box → block selection. */
const NS_MODEL = 'http://www.omg.org/spec/BPMN/20100524/MODEL';
const NS_DI = 'http://www.omg.org/spec/BPMN/20100524/DI';
const NS_DC = 'http://www.omg.org/spec/DD/20100524/DC';
const NS_DDI = 'http://www.omg.org/spec/DD/20100524/DI';

/** Translates backend labels (German type prefix, event names) into the app language. */
export interface BpmnLabelMaps {
  prefix: Record<string, string>;
  tip: Record<string, string>;
  event: Record<string, string>;
}
export function localizeName(name: string, maps: BpmnLabelMaps): string {
  if (maps.event[name] !== undefined) return maps.event[name]!;      // "Start" / "Ende"
  const idx = name.indexOf(': ');
  if (idx > 0 && maps.prefix[name.slice(0, idx)] !== undefined) {
    return maps.prefix[name.slice(0, idx)]! + name.slice(idx);        // "KI: git status" → "AI: git status"
  }
  if (maps.prefix[name] !== undefined) return maps.prefix[name]!;      // unnamed without command: "Freigabe" → "Approval"
  return name;                                                         // block name / command: unchanged
}

export interface Shape {
  id: string;
  kind: string;
  name: string;
  x: number;
  y: number;
  w: number;
  h: number;
  loop: 'multi' | 'standard' | null;
}

export interface EdgeLine {
  id: string;
  name: string;
  sourceRef: string;
  targetRef: string;
  points: [number, number][];
  /** Data association instead of control flow — drawn dashed. */
  dashed: boolean;
}

export interface Diagram {
  shapes: Shape[];
  edges: EdgeLine[];
}

export type RunStatus = 'running' | 'completed' | 'failed';

const VERTICAL_GAP = 70; // gap between boxes in the vertical flow
/** Distance between the bottom edge of the producer and the top edge of its data object. */
const DATA_OBJECT_GAP = 10;

/**
 * Builds a clean top→bottom layout from the left→right layout:
 * 1) transpose boxes (position x↔y, size stays — horizontal text stays readable),
 * 2) compress gaps along the flow axis to {@link VERTICAL_GAP},
 * 2b) place data objects below their producer (instead of beside it at the same height),
 * 3) route edges ANEW from the box positions (bottom center → top center, orthogonal).
 *
 * Step 3 is crucial: A plain swap of the waypoints attaches wrongly,
 * because the boxes are not square (100×80). Re-routing puts the arrows exactly
 * on the box centers.
 */
export function verticalLayout(d: Diagram): Diagram {
  // 1) Transpose boxes — about the CENTER (not the corner), otherwise non-square
  //    boxes (100×80) drift sideways by (w-h)/2 and the main line gets a zigzag.
  //    Exception subprocess (loop): it is not a box with text but a frame around its
  //    content. Its children lie transposed along its old width; if the frame kept
  //    its size, start and end of the loop would lie above or below it. It therefore swaps width
  //    and height — and stays an occupied interval in step 2 that encloses its content.
  let shapes = d.shapes.map((s) => {
    const [w, h] = s.kind === 'subProcess' ? [s.h, s.w] : [s.w, s.h];
    return {
      ...s,
      w,
      h,
      x: s.y + s.h / 2 - w / 2,
      y: s.x + s.w / 2 - h / 2,
    };
  });

  // 2) Compress gaps: merge occupied y intervals, shorten excess gaps
  const intervals = shapes.map((s) => [s.y, s.y + s.h] as [number, number]).sort((a, b) => a[0] - b[0]);
  const merged: [number, number][] = [];
  for (const [a, b] of intervals) {
    const last = merged[merged.length - 1];
    if (last && a <= last[1] + 1) last[1] = Math.max(last[1], b);
    else merged.push([a, b]);
  }
  const cuts: { threshold: number; shift: number }[] = [];
  let cumulative = 0;
  for (let i = 0; i < merged.length - 1; i++) {
    cumulative += Math.max(0, merged[i + 1]![0] - merged[i]![1] - VERTICAL_GAP);
    cuts.push({ threshold: merged[i + 1]![0], shift: cumulative });
  }
  const shiftFor = (y: number): number => {
    let s = 0;
    for (const c of cuts) if (y >= c.threshold - 0.001) s = c.shift;
    return s;
  };
  shapes = shapes.map((s) => ({ ...s, y: s.y - shiftFor(s.y) }));

  // 2b) Place data objects below their producer. In the horizontal layout the backend puts them
  //     BELOW the task row; transposed, they end up at the same height to the right of the
  //     producer, and the output association first runs back up (visible hook).
  //     Below the producer — offset sideways so the flow column stays free —
  //     it runs monotonically in flow direction. It fits into the {@link VERTICAL_GAP} gap.
  //     Only AFTER compressing: the data objects stay where they lay transposed as occupied
  //     intervals and would otherwise be shifted along and end up beside the producer again.
  const dataIds = new Set(shapes.filter((s) => s.kind === 'dataObjectReference').map((s) => s.id));
  const producerOf = new Map<string, string>();
  for (const e of d.edges) {
    if (e.dashed && dataIds.has(e.targetRef) && !producerOf.has(e.targetRef)) {
      producerOf.set(e.targetRef, e.sourceRef); // dataOutputAssociation: producer → data object
    }
  }
  if (producerOf.size > 0) {
    const placed = new Map(shapes.map((s) => [s.id, s]));
    shapes = shapes.map((s) => {
      const producer = placed.get(producerOf.get(s.id) ?? '');
      return producer ? { ...s, y: producer.y + producer.h + DATA_OBJECT_GAP } : s;
    });
  }

  // 3) Re-route edges — orthogonal from the bottom center of the source to the top center of the target
  const byId = new Map(shapes.map((s) => [s.id, s]));
  const edges = d.edges.map((e) => {
    const src = byId.get(e.sourceRef);
    const tgt = byId.get(e.targetRef);
    if (!src || !tgt) return { ...e }; // fallback: keep the old (mirrored) points
    const sx = src.x + src.w / 2;
    const sy = src.y + src.h;
    const tx = tgt.x + tgt.w / 2;
    const ty = tgt.y;
    const points: [number, number][] =
      Math.abs(sx - tx) < 1
        ? [[sx, sy], [tx, ty]]
        : [[sx, sy], [sx, (sy + ty) / 2], [tx, (sy + ty) / 2], [tx, ty]]; // orthogonal offset (branching)
    return { ...e, points };
  });

  return { shapes, edges };
}

/** Canvas of the diagram (viewBox) including margin and space for labels below the boxes. */
export function diagramBounds(d: Diagram): { minX: number; minY: number; width: number; height: number } {
  const pad = 40;
  const xs = [...d.shapes.flatMap((s) => [s.x, s.x + s.w]), ...d.edges.flatMap((e) => e.points.map((p) => p[0]))];
  const ys = [...d.shapes.flatMap((s) => [s.y, s.y + s.h + 20]), ...d.edges.flatMap((e) => e.points.map((p) => p[1]))];
  const minX = Math.min(...xs) - pad;
  const minY = Math.min(...ys) - pad;
  return { minX, minY, width: Math.max(...xs) + pad - minX, height: Math.max(...ys) + pad - minY };
}

export function parseBpmn(xml: string): Diagram | null {
  const doc = new DOMParser().parseFromString(xml, 'application/xml');
  if (doc.getElementsByTagName('parsererror').length > 0) return null;
  const byId = new Map<string, Element>();
  for (const el of Array.from(doc.getElementsByTagNameNS(NS_MODEL, '*'))) {
    const id = el.getAttribute('id');
    if (id) byId.set(id, el);
  }
  const shapes: Shape[] = Array.from(doc.getElementsByTagNameNS(NS_DI, 'BPMNShape')).flatMap((s) => {
    const bounds = s.getElementsByTagNameNS(NS_DC, 'Bounds')[0];
    const ref = byId.get(s.getAttribute('bpmnElement') ?? '');
    if (!bounds || !ref) return [];
    const loopEl = Array.from(ref.children).find(
      (c) => c.localName === 'multiInstanceLoopCharacteristics' || c.localName === 'standardLoopCharacteristics',
    );
    return [{
      id: ref.getAttribute('id') ?? '',
      kind: ref.localName,
      name: ref.getAttribute('name') ?? '',
      x: Number(bounds.getAttribute('x')),
      y: Number(bounds.getAttribute('y')),
      w: Number(bounds.getAttribute('width')),
      h: Number(bounds.getAttribute('height')),
      loop: loopEl ? (loopEl.localName === 'multiInstanceLoopCharacteristics' ? 'multi' : 'standard') : null,
    }];
  });
  const edges: EdgeLine[] = Array.from(doc.getElementsByTagNameNS(NS_DI, 'BPMNEdge')).map((e) => {
    const flow = byId.get(e.getAttribute('bpmnElement') ?? '');
    const kind = flow?.localName ?? '';
    const dashed = kind === 'dataInputAssociation' || kind === 'dataOutputAssociation';
    // sequenceFlow carries the ends as attributes, data associations as child elements; their
    // second end is the activity they sit in (for dataInputAssociation targetRef points
    // only to the property placeholder).
    const endpoint = (name: string): string =>
      flow?.getAttribute(name) ??
      Array.from(flow?.children ?? []).find((c) => c.localName === name)?.textContent?.trim() ??
      '';
    const owner = dashed ? ((flow?.parentNode as Element | null)?.getAttribute?.('id') ?? '') : '';
    return {
      id: e.getAttribute('id') ?? '',
      name: flow?.getAttribute('name') ?? '',
      sourceRef: kind === 'dataOutputAssociation' ? owner : endpoint('sourceRef'),
      targetRef: kind === 'dataInputAssociation' ? owner : endpoint('targetRef'),
      dashed,
      points: Array.from(e.getElementsByTagNameNS(NS_DDI, 'waypoint')).map(
        (w) => [Number(w.getAttribute('x')), Number(w.getAttribute('y'))] as [number, number],
      ),
    };
  });
  return { shapes, edges };
}

const LABEL_FONT_PX = 12;
const CHAR_PX = LABEL_FONT_PX * 0.55; // rough average width of a character at 12px
const MAX_LINES = 3;
/** Line width of the data object label — it sits below the narrow 36px symbol. */
export const DATA_LABEL_W = 130;

/**
 * Wraps a name so that every line fits the box's inner width.
 * Wrapping primarily at word boundaries; overlong single words are split hard;
 * on overflow the last line ends with an ellipsis — nothing sticks out anymore.
 */
export function wrapLabel(name: string, boxWidth: number): string[] {
  const maxChars = Math.max(4, Math.floor((boxWidth - 12) / CHAR_PX));
  const lines: string[] = [];
  let current = '';

  const pushWord = (word: string) => {
    // word longer than a whole line → cut hard into pieces
    while (word.length > maxChars) {
      if (current) {
        lines.push(current);
        current = '';
      }
      lines.push(word.slice(0, maxChars));
      word = word.slice(maxChars);
    }
    if (!current) {
      current = word;
    } else if ((current + ' ' + word).length <= maxChars) {
      current += ' ' + word;
    } else {
      lines.push(current);
      current = word;
    }
  };

  for (const word of name.split(/\s+/).filter(Boolean)) {
    pushWord(word);
  }
  if (current) lines.push(current);

  if (lines.length <= MAX_LINES) return lines;
  // overflow: truncate to MAX_LINES and ellipsize the last line
  const kept = lines.slice(0, MAX_LINES);
  let last = kept[MAX_LINES - 1]!;
  last = last.length > maxChars - 1 ? last.slice(0, maxChars - 1) : last;
  kept[MAX_LINES - 1] = last.replace(/\s+$/, '') + '…';
  return kept;
}

/** Points to a (possibly nested) part of a block definition. */
export type DiagramSelection = {
  blockIndex: number;
  sub?:
    | { kind: 'case'; caseIndex: number }
    | { kind: 'default' }
    | { kind: 'then' }
    | { kind: 'else' }
    | { kind: 'body' };
};

/**
 * Derives the block selection from a BPMN element ID. The IDs encode the path
 * deterministically: `b_<i>_c<k>_…` (switch case k), `b_<i>_default`, `b_<i>_then/else`
 * (conditional), `b_<i>_body` (loop), otherwise the block itself.
 */
export function selectionOf(shapeId: string): DiagramSelection | null {
  const base = shapeId.match(/^b_(\d+)/);
  if (!base) return null;
  const blockIndex = Number(base[1]);
  const caseMatch = shapeId.match(/^b_\d+_c(\d+)/);
  if (caseMatch) return { blockIndex, sub: { kind: 'case', caseIndex: Number(caseMatch[1]) } };
  if (/^b_\d+_default/.test(shapeId)) return { blockIndex, sub: { kind: 'default' } };
  if (/^b_\d+_then/.test(shapeId)) return { blockIndex, sub: { kind: 'then' } };
  if (/^b_\d+_else/.test(shapeId)) return { blockIndex, sub: { kind: 'else' } };
  if (/^b_\d+_body/.test(shapeId)) return { blockIndex, sub: { kind: 'body' } };
  return { blockIndex };
}
