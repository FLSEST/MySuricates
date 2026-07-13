const NS_MODEL = 'http://www.omg.org/spec/BPMN/20100524/MODEL';
const NS_DI = 'http://www.omg.org/spec/BPMN/20100524/DI';
const NS_DC = 'http://www.omg.org/spec/DD/20100524/DC';
const NS_DDI = 'http://www.omg.org/spec/DD/20100524/DI';

interface Shape {
  id: string;
  kind: string;
  name: string;
  x: number;
  y: number;
  w: number;
  h: number;
  loop: 'multi' | 'standard' | null;
}

/** Menschenlesbare Typ-Bezeichnung für Tooltips (Block-Typ am Kasten erkennbar). */
const TYPE_LABELS: Record<string, string> = {
  scriptTask: 'Shell-Kommando',
  serviceTask: 'KI / Service',
  userTask: 'Benutzer-Interaktion',
  callActivity: 'Sub-Workflow',
  task: 'Unbekannter Block-Typ',
  subProcess: 'Schleife (Sub-Prozess)',
};

interface EdgeLine {
  id: string;
  name: string;
  points: [number, number][];
}

interface Diagram {
  shapes: Shape[];
  edges: EdgeLine[];
}

function parseBpmn(xml: string): Diagram | null {
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
  const edges: EdgeLine[] = Array.from(doc.getElementsByTagNameNS(NS_DI, 'BPMNEdge')).map((e) => ({
    id: e.getAttribute('id') ?? '',
    name: byId.get(e.getAttribute('bpmnElement') ?? '')?.getAttribute('name') ?? '',
    points: Array.from(e.getElementsByTagNameNS(NS_DDI, 'waypoint')).map(
      (w) => [Number(w.getAttribute('x')), Number(w.getAttribute('y'))] as [number, number],
    ),
  }));
  if (shapes.length === 0) return null;
  return { shapes, edges };
}

const LABEL_FONT_PX = 12;
const CHAR_PX = LABEL_FONT_PX * 0.55; // grobe Mittelwert-Breite eines Zeichens bei 12px
const MAX_LINES = 3;

/**
 * Bricht einen Namen so um, dass jede Zeile in die Box-Innenbreite passt.
 * Umbruch primär an Wortgrenzen; überlange Einzelwörter werden hart getrennt;
 * bei Überlänge endet die letzte Zeile mit Ellipsis — nichts ragt mehr heraus.
 */
function wrapLabel(name: string, boxWidth: number): string[] {
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

/** BPMN-Standard-Marker: macht den Block-Typ am Kasten erkennbar. */
function TaskMarker({ s }: { s: Shape }) {
  const x = s.x + 7;
  const y = s.y + 7;
  switch (s.kind) {
    case 'scriptTask': // Skript: drei Textzeilen
      return (
        <g className="stroke-text-secondary" strokeWidth={1.2} strokeLinecap="round">
          <line x1={x} y1={y + 1} x2={x + 10} y2={y + 1} />
          <line x1={x} y1={y + 4.5} x2={x + 8} y2={y + 4.5} />
          <line x1={x} y1={y + 8} x2={x + 10} y2={y + 8} />
        </g>
      );
    case 'userTask': // Person: Kopf + Schultern
      return (
        <g className="stroke-text-secondary" fill="none" strokeWidth={1.2}>
          <circle cx={x + 5} cy={y + 2.5} r={2.5} />
          <path d={`M ${x} ${y + 10} Q ${x + 5} ${y + 4.5} ${x + 10} ${y + 10}`} />
        </g>
      );
    case 'serviceTask': // Zahnrad (vereinfacht): Kreis + Speichen
      return (
        <g className="stroke-text-secondary" fill="none" strokeWidth={1.1}>
          <circle cx={x + 5} cy={y + 5} r={2.8} />
          <line x1={x + 5} y1={y - 0.5} x2={x + 5} y2={y + 10.5} />
          <line x1={x - 0.5} y1={y + 5} x2={x + 10.5} y2={y + 5} />
          <line x1={x + 1.1} y1={y + 1.1} x2={x + 8.9} y2={y + 8.9} />
          <line x1={x + 8.9} y1={y + 1.1} x2={x + 1.1} y2={y + 8.9} />
        </g>
      );
    case 'callActivity': { // [+]-Marker unten mittig (aufrufbarer Sub-Workflow)
      const bx = s.x + s.w / 2 - 6;
      const by = s.y + s.h - 15;
      return (
        <g className="stroke-text-secondary" fill="none" strokeWidth={1.2}>
          <rect x={bx} y={by} width={12} height={12} rx={2} />
          <line x1={bx + 6} y1={by + 2.5} x2={bx + 6} y2={by + 9.5} />
          <line x1={bx + 2.5} y1={by + 6} x2={bx + 9.5} y2={by + 6} />
        </g>
      );
    }
    case 'task': // Unbekannter Typ
      return <text x={x + 5} y={y + 9} textAnchor="middle" className="fill-text-secondary text-[11px]">?</text>;
    default:
      return null;
  }
}

export type RunStatus = 'running' | 'completed' | 'failed';

function statusStroke(status?: RunStatus): string {
  if (status === 'running') return 'stroke-accent';
  if (status === 'completed') return 'stroke-success';
  if (status === 'failed') return 'stroke-danger';
  return 'stroke-text';
}
function statusFill(status: RunStatus | undefined, base: string): string {
  if (status === 'running') return 'fill-accent/15';
  if (status === 'completed') return 'fill-success/10';
  if (status === 'failed') return 'fill-danger/10';
  return base;
}

function ShapeSvg({ s, status }: { s: Shape; status?: RunStatus }) {
  const cx = s.x + s.w / 2;
  const cy = s.y + s.h / 2;
  const stroke = statusStroke(status);
  const strokeW = status ? 2.4 : 1.6;
  const pulse = status === 'running' ? 'animate-pulse' : '';
  switch (s.kind) {
    case 'startEvent':
    case 'endEvent':
      return (
        <g>
          <circle cx={cx} cy={cy} r={s.w / 2} className={`${statusFill(status, 'fill-surface')} ${stroke} ${pulse}`}
            strokeWidth={s.kind === 'endEvent' ? 3.5 : strokeW} />
          {s.name && <text x={cx} y={s.y + s.h + 16} textAnchor="middle" className="fill-text-secondary text-[11px]">{s.name}</text>}
        </g>
      );
    case 'exclusiveGateway':
    case 'parallelGateway':
    case 'inclusiveGateway':
    case 'eventBasedGateway':
      return (
        <g>
          <polygon
            points={`${cx},${s.y} ${s.x + s.w},${cy} ${cx},${s.y + s.h} ${s.x},${cy}`}
            className={`${statusFill(status, 'fill-surface')} ${stroke} ${pulse}`} strokeWidth={strokeW} />
          {s.kind === 'exclusiveGateway' && (
            <>
              <line x1={cx - 8} y1={cy - 8} x2={cx + 8} y2={cy + 8} className="stroke-text" strokeWidth={2.2} />
              <line x1={cx + 8} y1={cy - 8} x2={cx - 8} y2={cy + 8} className="stroke-text" strokeWidth={2.2} />
            </>
          )}
          {s.name && <text x={cx} y={s.y - 8} textAnchor="middle" className="fill-text-secondary text-[11px]">{s.name}</text>}
        </g>
      );
    case 'subProcess':
      return (
        <g>
          <title>{TYPE_LABELS.subProcess}</title>
          <rect x={s.x} y={s.y} width={s.w} height={s.h} rx={12}
            className={`${statusFill(status, 'fill-accent/5')} ${stroke} ${pulse}`} strokeWidth={strokeW} />
          {s.name && <text x={cx} y={s.y + 18} textAnchor="middle" className="fill-text-secondary text-[11px]">{s.name}</text>}
          {s.loop === 'multi' && (
            <g className="stroke-text-secondary" strokeWidth={1.4} strokeLinecap="round">
              <line x1={cx - 6} y1={s.y + s.h - 13} x2={cx + 6} y2={s.y + s.h - 13} />
              <line x1={cx - 6} y1={s.y + s.h - 10} x2={cx + 6} y2={s.y + s.h - 10} />
              <line x1={cx - 6} y1={s.y + s.h - 7} x2={cx + 6} y2={s.y + s.h - 7} />
            </g>
          )}
          {s.loop === 'standard' && (
            <text x={cx} y={s.y + s.h - 6} textAnchor="middle" className="fill-text-secondary text-[12px]">↻</text>
          )}
        </g>
      );
    default: // task, serviceTask, scriptTask, userTask, callActivity
      return (
        <g>
          <title>{TYPE_LABELS[s.kind] ?? s.kind}</title>
          <rect x={s.x} y={s.y} width={s.w} height={s.h} rx={10}
            className={`${statusFill(status, 'fill-surface')} ${stroke} ${pulse}`} strokeWidth={strokeW} />
          <TaskMarker s={s} />
          {(() => {
            const lines = wrapLabel(s.name, s.w);
            return (
              <text x={cx} y={cy - (lines.length - 1) * 7} textAnchor="middle" className="fill-text text-[12px]">
                <title>{s.name}</title>
                {lines.map((line, i) => (
                  <tspan key={i} x={cx} dy={i === 0 ? 0 : 14}>{line}</tspan>
                ))}
              </text>
            );
          })()}
        </g>
      );
  }
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
function selectionOf(shapeId: string): DiagramSelection | null {
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

export default function BpmnDiagramView({
  xml,
  onSelectBlock,
  statusByBlock,
}: {
  xml: string;
  onSelectBlock?: (selection: DiagramSelection) => void;
  /** Live-Status je Top-Level-Block-Index (färbt den zugehörigen Kasten). */
  statusByBlock?: Map<number, RunStatus>;
}) {
  const diagram = parseBpmn(xml);
  if (!diagram) {
    return <div role="alert" className="p-4 text-sm text-text-secondary">Invalid BPMN XML</div>;
  }
  const pad = 40;
  const xs = [
    ...diagram.shapes.flatMap((s) => [s.x, s.x + s.w]),
    ...diagram.edges.flatMap((e) => e.points.map((p) => p[0])),
  ];
  const ys = [...diagram.shapes.flatMap((s) => [s.y, s.y + s.h + 20]), ...diagram.edges.flatMap((e) => e.points.map((p) => p[1]))];
  const minX = Math.min(...xs) - pad;
  const minY = Math.min(...ys) - pad;
  const width = Math.max(...xs) + pad - minX;
  const height = Math.max(...ys) + pad - minY;

  return (
    <div className="overflow-x-auto text-text">
      <svg viewBox={`${minX} ${minY} ${width} ${height}`} style={{ minWidth: width * 0.75 }} role="img">
        <defs>
          <marker id="bpmn-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M 0 0 L 10 5 L 0 10 z" className="fill-text" />
          </marker>
        </defs>
        {diagram.edges.map((e) => (
          <g key={e.id}>
            <polyline
              points={e.points.map((p) => p.join(',')).join(' ')}
              fill="none" className="stroke-text" strokeWidth={1.6} markerEnd="url(#bpmn-arrow)" />
            {e.name && e.points.length >= 2 && (
              <text
                x={(e.points[0]![0] + e.points[e.points.length - 1]![0]) / 2}
                y={(e.points[0]![1] + e.points[e.points.length - 1]![1]) / 2 - 6}
                textAnchor="middle" className="fill-text-secondary text-[11px]">
                {e.name}
              </text>
            )}
          </g>
        ))}
        {diagram.shapes.map((s) => {
          const sel = selectionOf(s.id);
          const clickable = sel !== null && !!onSelectBlock;
          const status = sel && statusByBlock ? statusByBlock.get(sel.blockIndex) : undefined;
          return (
            <g
              key={s.id}
              className={clickable ? 'cursor-pointer transition-opacity hover:opacity-70' : undefined}
              onClick={clickable ? () => onSelectBlock!(sel!) : undefined}
              role={clickable ? 'button' : undefined}
              tabIndex={clickable ? 0 : undefined}
              onKeyDown={clickable ? (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onSelectBlock!(sel!); } } : undefined}
            >
              <ShapeSvg s={s} status={status} />
            </g>
          );
        })}
      </svg>
    </div>
  );
}
