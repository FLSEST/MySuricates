import { describe, it, expect } from 'vitest';
import { createDefaultLayout, isCustomizedLayout, type DashboardView } from '../dashboardLayout';
import { sanitizeLayout } from '../dashboardLayoutSanitize';
import { buildExampleProjectView, EXAMPLE_VIEW_WIDGET_IDS, exampleProjectViewId, hasViewForProject } from '../exampleProjectView';
import { DASHBOARD_WIDGETS } from '../widgetRegistry';
import { WORKFLOW_STATUS_WIDGET_ID } from '../workflowWidgetContract';

const PROJECT = '3f2b8c1e-5d4a-4b6f-9e21-7a0c5d3e8f10';
const WORKFLOW = 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d';

describe('buildExampleProjectView', () => {
  it('filters to the project and puts the example workflow up front', () => {
    const view = buildExampleProjectView(PROJECT, 'Beispielprojekt', WORKFLOW)!;

    expect(view.id).toBe(exampleProjectViewId(PROJECT));
    expect(view.name).toBe('Beispielprojekt');
    expect(view.filter).toEqual({ projectId: PROJECT });
    expect(view.widgets[0]).toMatchObject({ type: WORKFLOW_STATUS_WIDGET_ID, config: { workflowId: WORKFLOW } });
    const shown = view.widgets.filter((w) => !w.hidden && w.type !== WORKFLOW_STATUS_WIDGET_ID).map((w) => w.type);
    expect(shown).toEqual([...EXAMPLE_VIEW_WIDGET_IDS]);
  });

  it('uses only registered widgets', () => {
    const registered = new Set(DASHBOARD_WIDGETS.map((w) => w.id));
    for (const id of EXAMPLE_VIEW_WIDGET_IDS) expect(registered.has(id), id).toBe(true);
  });

  it('leaves the status widget out without a valid workflow id', () => {
    expect(buildExampleProjectView(PROJECT, 'x')!.widgets.some((w) => w.type === WORKFLOW_STATUS_WIDGET_ID)).toBe(false);
    expect(buildExampleProjectView(PROJECT, 'x', 'not a uuid')!.widgets.some((w) => w.type === WORKFLOW_STATUS_WIDGET_ID)).toBe(false);
  });

  it('refuses a project id the layout checks would drop', () => {
    expect(buildExampleProjectView('Not An Id', 'x')).toBeUndefined();
  });

  it('survives the check of stored layouts unchanged', () => {
    const view = buildExampleProjectView(PROJECT, 'Beispielprojekt', WORKFLOW)!;
    const layout = { activeViewId: 'default', views: [...createDefaultLayout().views, view] };

    expect(sanitizeLayout(layout).views[1]).toEqual(view);
  });

  it('keeps the untouched default layout uncustomised — only the example project adds a view', () => {
    expect(isCustomizedLayout(createDefaultLayout())).toBe(false);
  });
});

describe('hasViewForProject', () => {
  const own: DashboardView = { id: 'view-12345678', name: 'Mine', widgets: [], filter: { projectId: PROJECT } };

  it('finds the example view and any view of the user filtered to the project', () => {
    expect(hasViewForProject([buildExampleProjectView(PROJECT, 'x')!], PROJECT)).toBe(true);
    expect(hasViewForProject([own], PROJECT)).toBe(true);
    expect(hasViewForProject(createDefaultLayout().views, PROJECT)).toBe(false);
  });
});
