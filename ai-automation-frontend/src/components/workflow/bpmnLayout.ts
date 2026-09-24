/** Reine BPMN-Logik der Diagrammansicht: XML lesen, oben→unten-Layout, Beschriftungen umbrechen, Kasten → Block-Auswahl. */
const NS_MODEL = 'http://www.omg.org/spec/BPMN/20100524/MODEL';
const NS_DI = 'http://www.omg.org/spec/BPMN/20100524/DI';
const NS_DC = 'http://www.omg.org/spec/DD/20100524/DC';
const NS_DDI = 'http://www.omg.org/spec/DD/20100524/DI';

/** Übersetzt Backend-Beschriftungen (deutsches Typ-Präfix, Event-Namen) in die App-Sprache. */
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
  if (maps.prefix[name] !== undefined) return maps.prefix[name]!;      // namenlos ohne Befehl: "Freigabe" → "Approval"
  return name;                                                         // Block-Name / Befehl: unverändert
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
  /** Datenassoziation statt Kontrollfluss — wird gestrichelt gezeichnet. */
  dashed: boolean;
}

export interface Diagram {
  shapes: Shape[];
  edges: EdgeLine[];
}

export type RunStatus = 'running' | 'completed' | 'failed';

const VERTICAL_GAP = 70; // Leerraum zwischen Kästen im vertikalen Fluss
/** Abstand zwischen Unterkante des Erzeugers und Oberkante seines Datenobjekts. */
const DATA_OBJECT_GAP = 10;

/**
 * Baut aus dem links→rechts-Layout ein sauberes oben→unten-Layout:
 * 1) Kästen transponieren (Position x↔y, Größe bleibt — horizontaler Text lesbar),
 * 2) Leerräume entlang der Fluss-Achse auf {@link VERTICAL_GAP} stauchen,
 * 2b) Datenobjekte unter ihren Erzeuger legen (statt auf gleiche Höhe daneben),
 * 3) Kanten aus den Kasten-Positionen NEU routen (unten-Mitte → oben-Mitte, orthogonal).
 *
 * Schritt 3 ist entscheidend: Ein reiner Punkt-Tausch der Waypoints dockt falsch an,
 * weil die Kästen nicht quadratisch sind (100×80). Neu-Routing setzt die Pfeile exakt
 * an die Kasten-Mitten.
 */
export function verticalLayout(d: Diagram): Diagram {
  // 1) Kästen transponieren — über die MITTE (nicht die Ecke), sonst wandern nicht-quadratische
  //    Kästen (100×80) um (w-h)/2 seitlich weg und die Hauptlinie bekommt einen Zickzack.
  //    Ausnahme Subprozess (Schleife): er ist kein Kasten mit Text, sondern ein Rahmen um seinen
  //    Inhalt. Dessen Kinder liegen transponiert entlang seiner alten Breite; behielte der Rahmen
  //    seine Größe, lägen Start und Ende der Schleife über bzw. unter ihm. Er tauscht daher Breite
  //    und Höhe — und bleibt im Schritt 2 ein belegtes Intervall, das seinen Inhalt einschließt.
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

  // 2) Leerräume stauchen: belegte y-Intervalle mergen, überschüssige Lücken kürzen
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

  // 2b) Datenobjekte unter ihren Erzeuger legen. Das Backend setzt sie im waagerechten Layout
  //     UNTER die Task-Reihe; transponiert landen sie damit auf gleicher Höhe rechts neben dem
  //     Erzeuger, und die Ausgabe-Assoziation läuft erst zurück nach oben (sichtbarer Haken).
  //     Unterhalb des Erzeugers — seitlich versetzt, damit die Fluss-Spalte frei bleibt —
  //     verläuft sie monoton in Fluss-Richtung. Sie passt in den {@link VERTICAL_GAP}-Zwischenraum.
  //     Erst NACH dem Stauchen: die Datenobjekte bleiben dort als belegte Intervalle stehen, wo sie
  //     transponiert lagen, und würden sonst mitverschoben wieder neben dem Erzeuger landen.
  const dataIds = new Set(shapes.filter((s) => s.kind === 'dataObjectReference').map((s) => s.id));
  const producerOf = new Map<string, string>();
  for (const e of d.edges) {
    if (e.dashed && dataIds.has(e.targetRef) && !producerOf.has(e.targetRef)) {
      producerOf.set(e.targetRef, e.sourceRef); // dataOutputAssociation: Erzeuger → Datenobjekt
    }
  }
  if (producerOf.size > 0) {
    const placed = new Map(shapes.map((s) => [s.id, s]));
    shapes = shapes.map((s) => {
      const producer = placed.get(producerOf.get(s.id) ?? '');
      return producer ? { ...s, y: producer.y + producer.h + DATA_OBJECT_GAP } : s;
    });
  }

  // 3) Kanten neu routen — orthogonal von Unterkante-Mitte der Quelle zur Oberkante-Mitte des Ziels
  const byId = new Map(shapes.map((s) => [s.id, s]));
  const edges = d.edges.map((e) => {
    const src = byId.get(e.sourceRef);
    const tgt = byId.get(e.targetRef);
    if (!src || !tgt) return { ...e }; // Fallback: alte (gespiegelte) Punkte behalten
    const sx = src.x + src.w / 2;
    const sy = src.y + src.h;
    const tx = tgt.x + tgt.w / 2;
    const ty = tgt.y;
    const points: [number, number][] =
      Math.abs(sx - tx) < 1
        ? [[sx, sy], [tx, ty]]
        : [[sx, sy], [sx, (sy + ty) / 2], [tx, (sy + ty) / 2], [tx, ty]]; // orthogonaler Versatz (Verzweigung)
    return { ...e, points };
  });

  return { shapes, edges };
}

/** Zeichenfläche des Diagramms (viewBox) inklusive Rand und Platz für Beschriftungen unter den Kästen. */
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
    // sequenceFlow trägt die Enden als Attribute, Datenassoziationen als Kind-Elemente; deren
    // zweites Ende ist die Aktivität, in der sie steht (bei dataInputAssociation zeigt targetRef
    // nur auf den property-Platzhalter).
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
const CHAR_PX = LABEL_FONT_PX * 0.55; // grobe Mittelwert-Breite eines Zeichens bei 12px
const MAX_LINES = 3;
/** Zeilenbreite der Datenobjekt-Beschriftung — sie steht unter dem schmalen 36px-Symbol. */
export const DATA_LABEL_W = 130;

/**
 * Bricht einen Namen so um, dass jede Zeile in die Box-Innenbreite passt.
 * Umbruch primär an Wortgrenzen; überlange Einzelwörter werden hart getrennt;
 * bei Überlänge endet die letzte Zeile mit Ellipsis — nichts ragt mehr heraus.
 */
export function wrapLabel(name: string, boxWidth: number): string[] {
  const maxChars = Math.max(4, Math.floor((boxWidth - 12) / CHAR_PX));
  const lines: string[] = [];
  let current = '';

  const pushWord = (word: string) => {
    // Wort länger als eine ganze Zeile → hart in Stücke schneiden
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
  // Überlänge: auf MAX_LINES kürzen und die letzte Zeile ellipsieren
  const kept = lines.slice(0, MAX_LINES);
  let last = kept[MAX_LINES - 1]!;
  last = last.length > maxChars - 1 ? last.slice(0, maxChars - 1) : last;
  kept[MAX_LINES - 1] = last.replace(/\s+$/, '') + '…';
  return kept;
}

/** Verweist auf einen (ggf. verschachtelten) Teil einer Block-Definition. */
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
 * Leitet aus einer BPMN-Element-ID die Block-Auswahl ab. Die IDs kodieren den Pfad
 * deterministisch: `b_<i>_c<k>_…` (Switch-Case k), `b_<i>_default`, `b_<i>_then/else`
 * (Conditional), `b_<i>_body` (Schleife), sonst der Block selbst.
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
