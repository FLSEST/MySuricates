/**
 * The dashboard view the example project brings along (TODO e284eb3d). Whoever creates the example
 * project gets a second tab, filtered to that project, with its report example as a status widget
 * up front — so views, the per-view filter and workflow widgets show themselves on something real.
 * The default layout stays untouched: nobody else gets an extra tab, and a fresh default layout is
 * still recognised as uncustomised (isCustomizedLayout). Pure builders, plus addExampleProjectView,
 * which applies them to the stored layout.
 */

import { api } from '../../api/client';
import { useDashboardLayoutStore } from '../../stores/dashboardLayoutStore';
import type { Project } from '../../types';
import { cleanViewName, createInstanceId, isLayoutId, type DashboardView, type WidgetInstance } from './dashboardLayout';
import { templateWidgets } from './dashboardWidgets';
import { DASHBOARD_WIDGETS } from './widgetRegistry';
import { WORKFLOW_STATUS_WIDGET_ID, isWorkflowId } from './workflowWidgetContract';

/** Widgets of the view, in this order — all of them follow the view's project filter. */
export const EXAMPLE_VIEW_WIDGET_IDS: readonly string[] = ['recent-runs', 'stat-runs-today', 'stat-success-rate', 'chart-runs'];

/**
 * The example whose runs the status widget shows (TODO a0b414ce): the dashboard report, the one
 * example made for the dashboard. Mirrors ExampleProjectService.STATUS_TEMPLATE_ID in the backend
 * (ExampleTemplatesShippedTest compares the two).
 */
export const EXAMPLE_STATUS_TEMPLATE_ID = 'blueprint-report';

type ListedWorkflow = { id: string; projectId?: string | null; templateId?: string | null };

/**
 * The workflow for the status widget: the project's report example, else — an older copy without
 * it, or an instance whose dashboard-report module is off — the project's first workflow.
 */
export function pickStatusWorkflowId(workflows: readonly ListedWorkflow[], projectId: string): string | undefined {
  const ofProject = workflows.filter((w) => w.projectId === projectId);
  return (ofProject.find((w) => w.templateId === EXAMPLE_STATUS_TEMPLATE_ID) ?? ofProject[0])?.id;
}

export function exampleProjectViewId(projectId: string): string {
  return `view-example-${projectId}`;
}

/**
 * The view for the example project, or undefined when the project id would not pass the layout
 * checks. Without a (valid) workflow id the view simply has no status widget.
 */
export function buildExampleProjectView(projectId: string, name: string, workflowId?: string): DashboardView | undefined {
  const id = exampleProjectViewId(projectId);
  if (!isLayoutId(projectId) || !isLayoutId(id)) return undefined;
  const widgets: WidgetInstance[] = [];
  if (isWorkflowId(workflowId)) {
    widgets.push({ instanceId: createInstanceId(WORKFLOW_STATUS_WIDGET_ID), type: WORKFLOW_STATUS_WIDGET_ID, config: { workflowId } });
  }
  widgets.push(...templateWidgets(EXAMPLE_VIEW_WIDGET_IDS, DASHBOARD_WIDGETS));
  return { id, name: cleanViewName(name), widgets, filter: { projectId } };
}

/** True when a view already follows the project — the example view never comes twice or next to one of the user's own. */
export function hasViewForProject(views: readonly DashboardView[], projectId: string): boolean {
  return views.some((view) => view.id === exampleProjectViewId(projectId) || view.filter?.projectId === projectId);
}

/**
 * Adds the example project's view without switching to it. Best effort: the project is there either
 * way, and without a workflow the view just lacks the status widget.
 */
export async function addExampleProjectView(project: Project): Promise<void> {
  if (hasViewForProject(useDashboardLayoutStore.getState().views, project.id)) return;
  let workflowId: string | undefined;
  try {
    const workflows = await api.get<ListedWorkflow[]>('/workflows');
    workflowId = pickStatusWorkflowId(workflows, project.id);
  } catch {
    // The view works without the status widget.
  }
  const view = buildExampleProjectView(project.id, project.name, workflowId);
  // Checked again: a second click may have added the view while the workflows were loading.
  if (view && !hasViewForProject(useDashboardLayoutStore.getState().views, project.id)) {
    useDashboardLayoutStore.getState().upsertView(view);
  }
}
