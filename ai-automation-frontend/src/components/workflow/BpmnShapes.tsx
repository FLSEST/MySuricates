import { DATA_LABEL_W, localizeName, wrapLabel, type BpmnLabelMaps, type RunStatus, type Shape } from './bpmnLayout';

/** BPMN standard marker: makes the block type recognizable on the box. */
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

export function ShapeSvg({ s, status, maps }: { s: Shape; status?: RunStatus; maps: BpmnLabelMaps }) {
  const cx = s.x + s.w / 2;
  const cy = s.y + s.h / 2;
  const stroke = statusStroke(status);
  const strokeW = status ? 2.4 : 1.6;
  const pulse = status === 'running' ? 'animate-pulse' : '';
  const label = localizeName(s.name, maps);
  switch (s.kind) {
    case 'startEvent':
    case 'endEvent':
      return (
        <g>
          <circle cx={cx} cy={cy} r={s.w / 2} className={`${statusFill(status, 'fill-surface')} ${stroke} ${pulse}`}
            strokeWidth={s.kind === 'endEvent' ? 3.5 : strokeW} />
          {s.name && <text x={cx} y={s.y + s.h + 16} textAnchor="middle" className="fill-text-secondary text-[11px]">{label}</text>}
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
          {s.name && <text x={cx} y={s.y - 8} textAnchor="middle" className="fill-text-secondary text-[11px]">{label}</text>}
        </g>
      );
    case 'dataObjectReference': {
      // BPMN-Datenobjekt: Rechteck mit umgeknickter Ecke, Beschriftung darunter
      const fold = 10;
      const right = s.x + s.w;
      // The label sits below the (narrow) symbol and may be wider than it;
      // the full name is kept in the group's tooltip.
      const lines = wrapLabel(label, DATA_LABEL_W);
      return (
        <g>
          <title>{`${maps.tip.dataObjectReference ?? s.kind}${s.name ? ` — ${label}` : ''}`}</title>
          <path
            data-bpmn="data-object"
            d={`M ${s.x} ${s.y} H ${right - fold} L ${right} ${s.y + fold} V ${s.y + s.h} H ${s.x} Z`}
            className="fill-surface stroke-border-strong" strokeWidth={1.4} strokeLinejoin="round" />
          <path d={`M ${right - fold} ${s.y} V ${s.y + fold} H ${right}`}
            fill="none" className="stroke-border-strong" strokeWidth={1.4} strokeLinejoin="round" />
          {s.name && (
            <text x={cx} y={s.y + s.h + 14} textAnchor="middle" className="fill-text-secondary text-[11px]">
              {lines.map((line, i) => (
                <tspan key={i} x={cx} dy={i === 0 ? 0 : 12}>{line}</tspan>
              ))}
            </text>
          )}
        </g>
      );
    }
    case 'subProcess':
      return (
        <g>
          <title>{maps.tip.subProcess}</title>
          <rect x={s.x} y={s.y} width={s.w} height={s.h} rx={12}
            className={`${statusFill(status, 'fill-accent/5')} ${stroke} ${pulse}`} strokeWidth={strokeW} />
          {s.name && <text x={cx} y={s.y + 18} textAnchor="middle" className="fill-text-secondary text-[11px]">{label}</text>}
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
          <title>{maps.tip[s.kind] ?? s.kind}</title>
          <rect x={s.x} y={s.y} width={s.w} height={s.h} rx={10}
            className={`${statusFill(status, 'fill-surface')} ${stroke} ${pulse}`} strokeWidth={strokeW} />
          <TaskMarker s={s} />
          {(() => {
            const lines = wrapLabel(label, s.w);
            return (
              <text x={cx} y={cy - (lines.length - 1) * 7} textAnchor="middle" className="fill-text text-[12px]">
                <title>{label}</title>
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
