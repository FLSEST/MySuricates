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

/** Bricht einen Namen in maximal 3 Zeilen à ~24 Zeichen um (rein visuell). */
function wrapLabel(name: string): string[] {
  const words = name.split(/\s+/).filter(Boolean);
  const lines: string[] = [];
  let current = '';
  for (const word of words) {
    if (current && (current + ' ' + word).length > 24) {
      lines.push(current);
      current = word;
    } else {
      current = current ? current + ' ' + word : word;
    }
  }
  if (current) lines.push(current);
  return lines.slice(0, 3);
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

function ShapeSvg({ s }: { s: Shape }) {
  const cx = s.x + s.w / 2;
  const cy = s.y + s.h / 2;
  switch (s.kind) {
    case 'startEvent':
    case 'endEvent':
      return (
        <g>
          <circle cx={cx} cy={cy} r={s.w / 2} className="fill-surface stroke-text"
            strokeWidth={s.kind === 'endEvent' ? 3.5 : 1.6} />
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
            className="fill-surface stroke-text" strokeWidth={1.6} />
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
            className="fill-accent/5 stroke-text" strokeWidth={1.6} />
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
            className="fill-surface stroke-text" strokeWidth={1.6} />
          <TaskMarker s={s} />
          <text x={cx} y={cy - (wrapLabel(s.name).length - 1) * 7} textAnchor="middle" className="fill-text text-[12px]">
            {wrapLabel(s.name).map((line, i) => (
              <tspan key={i} x={cx} dy={i === 0 ? 0 : 14}>{line}</tspan>
            ))}
          </text>
        </g>
      );
  }
}

export default function BpmnDiagramView({ xml }: { xml: string }) {
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
        {diagram.shapes.map((s) => <ShapeSvg key={s.id} s={s} />)}
      </svg>
    </div>
  );
}
