import { useMemo, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ArrowDown, Info, List, Workflow } from 'lucide-react';
import { Button, Card } from '../ui';
import { Hint } from '../hints/Hint';
import { FIT_MIN_WIDTH_RATIO, useDiagramZoom } from '../workflow/useDiagramZoom';
import { useT } from '../../i18n';
import { COLUMNS, highlightFor, layoutRelations, type MapNode, type MapStructure, type NodeTarget } from './goalRelationLayout';
import { GoalDropTarget, PackageDropTarget, TodoDropTarget } from './WorkflowMapTargets';
import { useWorkflowMapDrag } from './workflowMapDrag';
import { RelationMapNode } from './RelationMapNode';
import { RelationMapLegend } from './RelationMapLegend';
import type { PlanBadge } from './planDisplay';
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

/** Überschrift von „Könnte dazugehören“ (`GoalCandidates`) – dorthin führt der leere Zustand. */
const CANDIDATES_ANCHOR = 'goal-candidates';

/**
 * Das Backend kennt `/relations` noch nicht (404, TODO 49080447): Hinweis statt Fehlerkarte, die
 * Liste funktioniert weiter.
 */
export function GoalMapOutdatedBackend({ onShowList }: { onShowList: () => void }) {
  const t = useT();
  return (
    <div className="flex flex-wrap items-center gap-3 rounded-lg p-3 text-sm bg-info-subtle text-info" data-testid="goal-map-outdated-backend">
      <Info className="w-4 h-4 shrink-0" />
      <p className="flex-1 min-w-[12rem]">{t.goals.map.outdatedBackend}</p>
      <Button variant="secondary" size="sm" icon={<List className="w-3.5 h-3.5" />} onClick={onShowList}>
        {t.goals.map.toList}
      </Button>
    </div>
  );
}

/** Ziel ohne TODOs und ohne gezeigte Kandidaten: kein leeres SVG, sondern der Weg zu den Vorschlägen. */
function EmptyGoalMap() {
  const t = useT();
  const scrollToCandidates = () =>
    document.getElementById(CANDIDATES_ANCHOR)?.scrollIntoView?.({ behavior: 'smooth', block: 'start' });
  return (
    <Card>
      <button type="button" onClick={scrollToCandidates}
        className="flex w-full items-center justify-center gap-2 py-6 text-sm text-text-secondary hover:text-text">
        <ArrowDown className="w-4 h-4 shrink-0" />
        <span>{t.goals.map.emptyMap}</span>
      </button>
    </Card>
  );
}

/**
 * Relationskarte eines Ziels (TODO c6ea8f5c): Herkunft → TODOs → Bearbeitung → Ergebnis, als SVG
 * mit Zoom. Hover hebt den ganzen Pfad eines TODOs hervor; Workflows, Läufe und Befunde sind
 * Links, ein TODO öffnet den Bearbeiten-Dialog, ein Kandidat lässt sich zuordnen.
 */
export function GoalRelationMap({ relations, onOpenTodo, onAssign, badges, structure, goalTitle, onOpenWorkflows }: {
  relations: GoalRelations;
  onOpenTodo: (todoId: string) => void;
  onAssign: (todoId: string) => void;
  /** Fälligkeit und Meilenstein je TODO des Ziels (TODO e192df18); Kandidaten haben keine. */
  badges?: ReadonlyMap<string, PlanBadge>;
  /** Gliederung nach Projekt → Arbeitspaket (52bc1e8c); ohne nur nach Projekt. */
  structure?: MapStructure;
  goalTitle?: string;
  /** Öffnet die Workflow-Leiste, wenn sie zu ist. */
  onOpenWorkflows?: () => void;
}) {
  const t = useT();
  const m = t.goals.map;
  const tm = t.goals.workflowMap;
  const navigate = useNavigate();
  const drag = useWorkflowMapDrag();
  const layout = useMemo(() => layoutRelations(relations.todos, structure), [relations.todos, structure]);
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

  if (relations.todos.length === 0) return <EmptyGoalMap />;

  return (
    <div className="space-y-3">
      <Hint id="goal-relation-map" />
      {members.length === 0 && <Card><p className="text-sm text-text-secondary">{m.empty}</p></Card>}
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
        {onOpenWorkflows && (
          <button type="button" onClick={onOpenWorkflows} data-testid="workflow-map-open"
            className="absolute left-2 top-1.5 z-10 inline-flex items-center gap-1.5 rounded-md border border-border bg-surface px-2 py-1 text-xs text-text-secondary shadow-sm hover:border-accent hover:text-accent focus-ring">
            <Workflow className="h-3.5 w-3.5" aria-hidden="true" />{tm.open}
          </button>
        )}
        {goalTitle && drag && (
          <div className="px-3 pt-10">
            <GoalDropTarget label={tm.goalBand.replace('{title}', goalTitle)} hint={tm.goalBandHint} />
          </div>
        )}
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
        <div ref={scrollRef} className={`px-3 pb-3 ${goalTitle && drag?.context.attachSupported ? 'pt-3' : 'pt-10'} ${fixedSize ? 'overflow-auto max-h-[75vh]' : 'overflow-x-auto'}`}>
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
            {drag && layout.groups.map((group) => (typeof group.workPackageId === 'string'
              ? <PackageDropTarget key={`drop-${group.y}`} group={group} workPackageId={group.workPackageId} /> : null))}
            {layout.groups.map((group) => (
              <text key={`${group.y}`} x={COLUMNS[1].x} y={group.y + 16} className="fill-text-tertiary text-[11px] font-medium"
                data-testid="map-group-label">
                {group.candidate ? m.candidates : group.label ?? t.goals.noProject}
                {group.workPackageId !== undefined && !group.candidate && (
                  <tspan className={group.workPackageId ? 'fill-text-secondary' : 'fill-text-tertiary italic'}>
                    {` · ${group.packageTitle ?? tm.noPackage}`}
                  </tspan>
                )}
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
            {layout.nodes.map((node) => {
              const view = (
              <RelationMapNode
                key={node.id}
                node={node}
                dimmed={!lit(node.todoIds)}
                onHover={setHovered}
                onActivate={activate(node)}
                onAssign={node.kind === 'todo' && node.candidate ? () => onAssign(node.todoIds[0]!) : undefined}
                assignLabel={m.assign}
                badge={node.kind === 'todo' && !node.candidate ? badges?.get(node.todoIds[0]!) : undefined}
              />
              );
              // In der Workflow-Zuordnung ist jedes TODO des Ziels eine Ablagefläche (52bc1e8c)
              return drag && node.kind === 'todo' && !node.candidate
                ? <TodoDropTarget key={node.id} node={node}>{view}</TodoDropTarget> : view;
            })}
          </svg>
        </div>
      </Card>
      <RelationMapLegend />
    </div>
  );
}
