import { useId, useMemo, useState } from 'react';
import { useDraggable } from '@dnd-kit/core';
import { GripVertical, Search, Workflow } from 'lucide-react';
import { Spinner, Toggle } from '../ui';
import { useT } from '../../i18n';
import { useWorkflows } from '../../hooks/useWorkflows';
import { SidePanelFrame } from './SidePanelFrame';
import { WORKFLOW_PANEL_KEY } from './panelGeometry';
import { workflowDragId, type DraggedWorkflow } from './workflowMap';
import { useWorkflowMapDrag } from './workflowMapDrag';
import type { GoalProject } from '../../types';

/**
 * Die Workflow-Leiste der Workflow-Zuordnung (52bc1e8c, Flo: „dass der auf der rechten Seite
 * ziehbar wäre“): dieselbe Leiste wie die Seitenleiste (`SidePanelFrame`: andocken, Breite, als
 * Fenster lösen), darin die Workflows der Zielprojekte – mit Suche und „Alle Workflows zeigen“ –, jeder
 * ziehbar auf die Karte, mit Maus oder Tastatur.
 */
export function WorkflowMapPanel({ projects, onClose }: { projects: GoalProject[]; onClose: () => void }) {
  const t = useT();
  const tm = t.goals.workflowMap;
  const tw = t.goals.workflows;
  const headingId = useId();
  const searchId = useId();
  const { data: workflows, isLoading } = useWorkflows();
  const [query, setQuery] = useState('');
  const [showAll, setShowAll] = useState(false);
  const goalProjects = useMemo(() => new Set(projects.map((project) => project.projectId)), [projects]);
  const needle = query.trim().toLowerCase();
  const shown = (workflows ?? [])
    .filter((workflow) => showAll || goalProjects.has(workflow.projectId))
    .filter((workflow) => !needle || workflow.name.toLowerCase().includes(needle) || workflow.projectName?.toLowerCase().includes(needle))
    .sort((a, b) => a.name.localeCompare(b.name));

  return (
    <SidePanelFrame labelledBy={headingId} onClose={onClose} testId="workflow-map-panel" kind="workflows"
      storageKey={WORKFLOW_PANEL_KEY} closeLabel={tm.close}
      header={(
        <>
          <p className="flex items-center gap-1.5 text-xs text-text-tertiary">
            <Workflow className="h-3.5 w-3.5" aria-hidden="true" />{t.goals.views.map}
          </p>
          <h2 id={headingId} className="mt-0.5 text-lg font-semibold text-text">{tm.panelTitle}</h2>
        </>
      )}>
      <p className="text-xs text-text-secondary">{tm.panelHint}</p>
      <div className="mt-3 space-y-2">
        <label htmlFor={searchId} className="relative block">
          <span className="sr-only">{tw.search}</span>
          <Search className="absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-text-tertiary" aria-hidden="true" />
          <input id={searchId} type="search" value={query} placeholder={tw.search} onChange={(event) => setQuery(event.target.value)}
            className="input-base w-full pl-8" />
        </label>
        <Toggle enabled={showAll} onChange={setShowAll} label={tw.showAll} ariaLabel={tw.showAll} />
        {!showAll && <p className="text-xs text-text-tertiary">{tw.onlyGoalProjects}</p>}
      </div>
      {isLoading ? (
        <div className="flex justify-center py-6"><Spinner /></div>
      ) : shown.length === 0 ? (
        <p className="mt-3 rounded-md border border-dashed border-border px-3 py-4 text-center text-sm text-text-secondary">{tw.none}</p>
      ) : (
        <ul className="mt-3 space-y-1.5" data-testid="workflow-map-list">
          {shown.map((workflow) => (
            <li key={workflow.id}>
              <DraggableWorkflow workflow={{ id: workflow.id, name: workflow.name, projectId: workflow.projectId, projectName: workflow.projectName ?? null }} />
            </li>
          ))}
        </ul>
      )}
    </SidePanelFrame>
  );
}

/** Ein Workflow zum Ziehen: Griff, Name, Projekt. Leertaste nimmt ihn auf (dnd-kit), Pfeile springen von Ziel zu Ziel. */
function DraggableWorkflow({ workflow }: { workflow: DraggedWorkflow }) {
  const tm = useT().goals.workflowMap;
  const drag = useWorkflowMapDrag();
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({ id: workflowDragId(workflow.id), data: { workflow } });
  const lifted = isDragging || drag?.dragged?.id === workflow.id;
  return (
    <button ref={setNodeRef} type="button" {...attributes} {...listeners}
      aria-label={tm.drag.replace('{name}', workflow.name)} data-testid={`workflow-map-item-${workflow.id}`}
      className={`flex w-full cursor-grab items-center gap-2 rounded-md border border-border bg-surface px-2.5 py-2 text-left shadow-sm focus-ring hover:border-accent active:cursor-grabbing ${
        lifted ? 'opacity-40' : ''}`}
      style={{ touchAction: 'none' }}>
      <GripVertical className="h-4 w-4 shrink-0 text-text-tertiary" aria-hidden="true" />
      <span className="min-w-0 flex-1">
        <span className="block truncate text-sm font-medium text-text">{workflow.name}</span>
        {workflow.projectName && <span className="block truncate text-xs text-text-tertiary">{workflow.projectName}</span>}
      </span>
    </button>
  );
}
