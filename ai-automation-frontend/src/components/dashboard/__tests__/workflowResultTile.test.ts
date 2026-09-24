import { describe, it, expect, beforeEach } from 'vitest';
import { selectActiveView, useDashboardLayoutStore } from '../../../stores/dashboardLayoutStore';
import { useDashboardSyncStore } from '../../../stores/dashboardSyncStore';
import { LAYOUT_LIMITS, createDefaultLayout, type WidgetInstance } from '../dashboardLayout';
import { addWorkflowResultTile, hasWorkflowResultTile } from '../workflowResultTile';
import { WORKFLOW_RESULT_WIDGET_ID, WORKFLOW_STATUS_WIDGET_ID } from '../workflowWidgetContract';

beforeEach(() => {
  localStorage.clear();
  useDashboardLayoutStore.setState(createDefaultLayout());
  useDashboardSyncStore.setState({ baseVersionId: null, dirty: false });
});

const activeWidgets = () => selectActiveView(useDashboardLayoutStore.getState())!.widgets;
const tile = (instanceId: string, workflowId: string, type = WORKFLOW_RESULT_WIDGET_ID): WidgetInstance =>
  ({ instanceId, type, config: { workflowId } });

describe('addWorkflowResultTile', () => {
  it('appends a result tile to the shown view behind its widgets, like the catalog does', () => {
    expect(addWorkflowResultTile('wf-guard')).toBe(true);

    const widgets = activeWidgets();
    expect(widgets.length).toBeGreaterThan(1);
    expect(widgets.at(-1)).toMatchObject({ type: WORKFLOW_RESULT_WIDGET_ID, config: { workflowId: 'wf-guard' } });
    expect(widgets.slice(0, -1).every((widget) => widget.type !== WORKFLOW_RESULT_WIDGET_ID)).toBe(true);
    expect(useDashboardSyncStore.getState().dirty).toBe(true);
  });

  it('adds nothing when a view already shows the result of this workflow', () => {
    const store = useDashboardLayoutStore.getState();
    store.addView('Goals', [tile('workflow-result-1', 'wf-guard')]);
    store.setActiveView('default');

    expect(addWorkflowResultTile('wf-guard')).toBe(false);
    expect(activeWidgets()).toEqual([]);
  });

  it('a status tile of the workflow is no result tile', () => {
    useDashboardLayoutStore.getState().setActiveViewWidgets([tile('workflow-status-1', 'wf-guard', WORKFLOW_STATUS_WIDGET_ID)]);

    expect(hasWorkflowResultTile(useDashboardLayoutStore.getState().views, 'wf-guard')).toBe(false);
    expect(addWorkflowResultTile('wf-guard')).toBe(true);
  });

  it('refuses ids the dashboard would never load and views at their widget limit', () => {
    expect(addWorkflowResultTile('Not An Id')).toBe(false);

    const full = Array.from({ length: LAYOUT_LIMITS.widgetsPerView }, (_, i) => tile(`workflow-result-${i}`, `wf-${i}`));
    useDashboardLayoutStore.getState().setActiveViewWidgets(full);
    useDashboardSyncStore.setState({ dirty: false });

    expect(addWorkflowResultTile('wf-guard')).toBe(false);
    expect(activeWidgets()).toHaveLength(LAYOUT_LIMITS.widgetsPerView);
    expect(useDashboardSyncStore.getState().dirty).toBe(false);
  });
});
