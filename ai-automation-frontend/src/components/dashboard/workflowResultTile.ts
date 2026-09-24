/**
 * The result tile of a workflow, added from outside the dashboard (TODO e192df18: switching on a
 * goal's guard brings its tile along). Same path as "add widget" in the catalog: appended to the
 * shown view behind its widgets and marked as a local change, so the next save point of the version
 * history stores it. A workflow whose result some view already shows gets no second tile.
 */

import { selectActiveView, useDashboardLayoutStore } from '../../stores/dashboardLayoutStore';
import type { DashboardView } from './dashboardLayout';
import { addWidgetToView, normalizeWidgets } from './dashboardWidgets';
import { DASHBOARD_WIDGETS } from './widgetRegistry';
import { WORKFLOW_RESULT_WIDGET_ID, isWorkflowId } from './workflowWidgetContract';

export function hasWorkflowResultTile(views: readonly DashboardView[], workflowId: string): boolean {
  return views.some((view) => view.widgets.some(
    (widget) => widget.type === WORKFLOW_RESULT_WIDGET_ID && widget.config?.workflowId === workflowId,
  ));
}

/** True when the tile was added; false for an invalid id, an existing tile or a full view. */
export function addWorkflowResultTile(workflowId: string): boolean {
  if (!isWorkflowId(workflowId)) return false;
  const state = useDashboardLayoutStore.getState();
  if (hasWorkflowResultTile(state.views, workflowId)) return false;
  const view = selectActiveView(state);
  if (!view) return false;
  // The full list incl. the standard widgets, exactly what the catalog appends to.
  const widgets = normalizeWidgets(view.widgets, DASHBOARD_WIDGETS);
  const next = addWidgetToView(widgets, DASHBOARD_WIDGETS, WORKFLOW_RESULT_WIDGET_ID, { workflowId });
  if (next === widgets) return false;
  state.setActiveViewWidgets(next);
  return true;
}
