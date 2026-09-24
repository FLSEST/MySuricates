import { useMemo, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Info } from 'lucide-react';
import { Card } from '../ui';
import { Hint } from '../hints/Hint';
import { FIT_MIN_WIDTH_RATIO, useDiagramZoom } from '../workflow/useDiagramZoom';
import { useT } from '../../i18n';
import { COLUMNS, highlightFor, layoutRelations, type MapNode, type NodeTarget } from './goalRelationLayout';
import { RelationMapNode } from './RelationMapNode';
import { RelationMapLegend } from './RelationMapLegend';
import type { GoalRelations } from '../../types';

/** Wohin ein Klick auf einen Knoten führt; TODOs öffnet die Zielseite selbst. */
function hrefOf(target: NodeTarget): string | null {
  switch (target.type) {
    case 'workflow': return `/workflows/${target.id}`;
    case 'run': return `/runs/${target.id}`;
    case 'finding': return `/reports?finding=${encodeURIComponent(target.id)}`;
    case 'audit': return '/reports';
    default: return null;
  }
}

/**
 * Relationskarte eines Ziels (TODO c6ea8f5c): Herkunft → TODOs → Bearbeitung → Ergebnis, als SVG
 * mit Zoom. Hover hebt den ganzen Pfad eines TODOs hervor; Workflows, Läufe und Befunde sind
 * Links, ein TODO öffnet den Bearbeiten-Dialog, ein Kandidat lässt sich zuordnen.
 */
export function GoalRelationMap({ relations, onOpenTodo, onAssign }: {
  relations: GoalRelations;
  onOpenTodo: (todoId: string) => void;
  onAssign: (todoId: string) => void;
}) {
  const t = useT();
  const m = t.goals.map;
  const navigate = useNavigate();
  const layout = useMemo(() => layoutRelations(relations.todos), [relations.todos]);
  const [hovered, setHovered] = useState<string | null>(null);
  const highlight = highlightFor(layout, hovered);
  const scrollRef = useRef<HTMLDivElement>(null);
  const zoom = useDiagramZoom(scrollRef, 'goal-relations', layout.width, 'width');
  const { scale } = zoom;
  const fixedSize = scale !== null ? { width: layout.width * scale, height: layout.height * scale } : null;
  const members = relations.todos.filter((todo) => !todo.candidate);

  const activate = (node: MapNode) => {
    if (!node.target) return undefined;
    if (node.target.type === 'todo') {
      const id = node.target.id;
      return () => onOpenTodo(id);
    }
    const href = hrefOf(node.target);
    return href ? () => navigate(href) : undefined;
  };
  const lit = (todoIds: string[]) => !highlight || todoIds.some((id) => highlight.has(id));

  return (
    <div className="space-y-3">
      <Hint id="goal-relation-map" />
      {members.length > 0 && !layout.hasWorkers && (
        <div className="flex gap-2 rounded-lg p-3 text-sm bg-info-subtle text-info" data-testid="goal-map-no-workers">
          <Info className="w-4 h-4 shrink-0 mt-0.5" />
          <p>
            {m.noWorkersHint}{' '}
            <Link to="/todos#todo-triggers" className="underline font-medium">{m.manageTriggers}</Link>
          </p>
        </div>
      )}
      <Card padding={false} className="relative">
        <div role="group" aria-label={t.workflows.zoomHint} title={t.workflows.zoomHint}
          className="absolute top-1 right-1 z-10 flex items-center gap-0.5 rounded-md border border-border bg-surface/85 p-0.5 text-text-secondary shadow-sm">
          <button type="button" onClick={zoom.zoomOut} disabled={!zoom.canZoomOut} aria-label={t.workflows.zoomOut}
            className="h-6 w-6 rounded text-base leading-none hover:bg-surface-raised hover:text-text disabled:opacity-40">−</button>
          <button type="button" onClick={zoom.fit} disabled={scale === null} aria-label={t.workflows.zoomFit}
            className="min-w-[3.25rem] rounded px-1 py-0.5 text-xs tabular-nums hover:bg-surface-raised hover:text-text">
            {scale !== null ? `${Math.round(scale * 100)} %` : t.workflows.zoomFit}
          </button>
          <button type="button" onClick={zoom.zoomIn} disabled={!zoom.canZoomIn} aria-label={t.workflows.zoomIn}
            className="h-6 w-6 rounded text-base leading-none hover:bg-surface-raised hover:text-text disabled:opacity-40">+</button>
        </div>
        <div ref={scrollRef} className={`px-3 pb-3 pt-10 ${fixedSize ? 'overflow-auto max-h-[75vh]' : 'overflow-x-auto'}`}>
          <svg
            viewBox={`-8 0 ${layout.width + 16} ${layout.height}`}
            {...(fixedSize ?? {})}
            style={fixedSize ? { maxWidth: 'none' } : { minWidth: layout.width * FIT_MIN_WIDTH_RATIO, width: '100%' }}
            role="img"
            aria-label={m.label}
            data-testid="goal-relation-map"
          >
            {m.columns.map((label, i) => (
              <text key={label} x={COLUMNS[i]!.x} y={16} className="fill-text-secondary text-[11px] font-semibold uppercase tracking-wide">
                {label}
              </text>
            ))}
            {layout.groups.map((group) => (
              <text key={`${group.y}`} x={COLUMNS[1].x} y={group.y + 16} className="fill-text-tertiary text-[11px] font-medium">
                {group.candidate ? m.candidates : group.label ?? t.goals.noProject}
              </text>
            ))}
            {layout.edges.map((edge) => {
              const on = highlight?.has(edge.todoId) ?? false;
              return (
                <path key={edge.id} d={edge.path} fill="none"
                  style={{ stroke: on ? 'var(--color-accent)' : 'var(--color-border-strong)' }}
                  strokeWidth={on ? 2 : 1.3}
                  strokeLinecap="round"
                  strokeDasharray={edge.style === 'dashed' ? '6 4' : edge.style === 'dotted' ? '1 4' : undefined}
                  opacity={lit([edge.todoId]) ? 1 : 0.2} />
              );
            })}
            {layout.nodes.map((node) => (
              <RelationMapNode
                key={node.id}
                node={node}
                dimmed={!lit(node.todoIds)}
                onHover={setHovered}
                onActivate={activate(node)}
                onAssign={node.kind === 'todo' && node.candidate ? () => onAssign(node.todoIds[0]!) : undefined}
                assignLabel={m.assign}
              />
            ))}
          </svg>
        </div>
      </Card>
      <RelationMapLegend />
    </div>
  );
}
