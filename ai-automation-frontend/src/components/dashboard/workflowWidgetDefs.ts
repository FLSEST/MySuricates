import { describedBy, type DashboardWidgetDef } from './widgetDefinition';
import { WORKFLOW_RESULT_WIDGET_ID, WORKFLOW_STATUS_WIDGET_ID } from './workflowWidgetContract';
import { WorkflowStatusWidget } from './WorkflowStatusWidget';
import { WorkflowResultWidget } from './WorkflowResultWidget';
import { WorkflowWidgetConfigForm } from './WorkflowWidgetConfigForm';

/**
 * The two workflow widgets (spec E3). Multi widgets: they never appear by themselves, they are added
 * from the catalog with a workflow, they can be removed, and templates never contain them.
 */
export const WORKFLOW_WIDGETS: readonly DashboardWidgetDef[] = [
  {
    id: WORKFLOW_STATUS_WIDGET_ID, category: 'workflows', size: 'third', multiple: true,
    component: WorkflowStatusWidget, configForm: WorkflowWidgetConfigForm,
    label: (t) => t.dashboard.workflowWidgets.status, description: describedBy('workflowStatus'),
  },
  {
    id: WORKFLOW_RESULT_WIDGET_ID, category: 'workflows', size: 'third', multiple: true,
    component: WorkflowResultWidget, configForm: WorkflowWidgetConfigForm,
    label: (t) => t.dashboard.workflowWidgets.result, description: describedBy('workflowResult'),
  },
];
