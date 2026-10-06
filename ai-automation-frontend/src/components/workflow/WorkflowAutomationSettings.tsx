import { Toggle } from '../ui';
import { useUpdateWorkflow } from '../../hooks/useWorkflows';
import { useT } from '../../i18n';
import type { Workflow } from '../../types';
import { auditConfigOf } from './auditWorkflow';
import { WorkflowAuditSettings } from './WorkflowAuditSettings';

function SwitchRow({ title, description, enabled, onChange }: {
  title: string;
  description: string;
  enabled: boolean;
  onChange: (enabled: boolean) => void;
}) {
  return (
    <div className="flex items-center justify-between px-3 py-2 rounded-lg bg-surface-raised border border-border">
      <div>
        <span className="text-sm font-medium text-text">{title}</span>
        <p className="text-xs text-text-tertiary">{description}</p>
      </div>
      <Toggle enabled={enabled} onChange={onChange} ariaLabel={title} />
    </div>
  );
}

/**
 * Automations of a workflow in the panel of the workflow list: TODOs from findings (`autoTodo`),
 * repair after a failed run (`autoRepair`) and memory extraction every N runs
 * (`autoMemoryExtraction`). Each is a switch; its options show only while it is on. Rendered as
 * siblings (fragment), so the spacing of the panel applies to every row. An audit workflow shows its audit part
 * instead of Auto-Todo: the backend ignores `autoTodo` there, its TODOs come from the findings (d8607f77).
 */
export function WorkflowAutomationSettings({ workflow }: { workflow: Workflow }) {
  const t = useT();
  const updateWorkflow = useUpdateWorkflow();
  const autoTodoEnabled = !!workflow.metadata?.autoTodo?.enabled;
  const autoTodoMode = workflow.metadata?.autoTodo?.mode ?? 'auto';
  const autoRepairEnabled = !!workflow.metadata?.autoRepair?.enabled;
  const autoRepairMode = workflow.metadata?.autoRepair?.mode ?? 'auto';
  const autoMemoryEnabled = !!workflow.metadata?.autoMemoryExtraction?.enabled;
  const autoMemoryInterval = workflow.metadata?.autoMemoryExtraction?.interval ?? 5;
  const audit = auditConfigOf(workflow.metadata);

  const saveMetadata = (metadata: Record<string, unknown>) =>
    updateWorkflow.mutate({ id: workflow.id, data: { metadata } });

  return (
    <>
      {audit && (
        <WorkflowAuditSettings projectId={workflow.projectId} audit={audit}
          onChange={(next) => saveMetadata({ audit: next })} />
      )}

      {!audit && <SwitchRow
        title={t.workflows.autoTodo}
        description={t.workflows.autoTodoDescription}
        enabled={autoTodoEnabled}
        onChange={(enabled) => saveMetadata({ autoTodo: { ...workflow.metadata?.autoTodo, enabled } })}
      />}

      {!audit && autoTodoEnabled && (
        <div className="px-3 py-2 rounded-lg bg-surface-raised border border-border space-y-1">
          <span className="text-xs font-medium text-text-secondary">{t.workflows.autoTodoMode}</span>
          <select
            value={autoTodoMode}
            onChange={(e) =>
              saveMetadata({ autoTodo: { ...workflow.metadata?.autoTodo, mode: e.target.value as 'auto' | 'approval' | 'track' } })
            }
            className="w-full text-xs bg-surface border border-border rounded-md px-2 py-1.5 text-text focus:outline-none focus:ring-1 focus:ring-accent"
          >
            <option value="auto">{t.workflows.autoTodoModeAuto}</option>
            <option value="approval">{t.workflows.autoTodoModeApproval}</option>
            <option value="track">{t.workflows.autoTodoModeTrack}</option>
          </select>
          <p className="text-xs text-text-tertiary">{t.workflows.autoTodoModeHint}</p>
        </div>
      )}

      <SwitchRow
        title={t.workflows.autoRepair}
        description={t.workflows.autoRepairDescription}
        enabled={autoRepairEnabled}
        onChange={(enabled) => saveMetadata({ autoRepair: { ...workflow.metadata?.autoRepair, enabled } })}
      />

      {autoRepairEnabled && (
        <div className="px-3 py-2 rounded-lg bg-surface-raised border border-border space-y-1">
          <span className="text-xs font-medium text-text-secondary">{t.workflows.autoRepairMode}</span>
          <select
            value={autoRepairMode}
            onChange={(e) => saveMetadata({ autoRepair: { ...workflow.metadata?.autoRepair, mode: e.target.value } })}
            className="w-full text-xs bg-surface border border-border rounded-md px-2 py-1.5 text-text focus:outline-none focus:ring-1 focus:ring-accent"
          >
            <option value="auto">{t.workflows.autoRepairModeAuto}</option>
            <option value="test">{t.workflows.autoRepairModeTest}</option>
            <option value="local">{t.workflows.autoRepairModeLocal}</option>
          </select>
          <p className="text-xs text-text-tertiary">{t.workflows.autoRepairModeHint}</p>
        </div>
      )}

      <SwitchRow
        title={t.workflows.autoMemory}
        description={t.workflows.autoMemoryDescription}
        enabled={autoMemoryEnabled}
        onChange={(enabled) => saveMetadata({ autoMemoryExtraction: { ...workflow.metadata?.autoMemoryExtraction, enabled } })}
      />

      {autoMemoryEnabled && (
        <div className="ml-4 pl-3 border-l-2 border-accent/20 space-y-2">
          <span className="text-xs font-medium text-text-secondary">{t.workflows.autoMemoryInterval}</span>
          <input
            type="number"
            min={1}
            max={50}
            value={autoMemoryInterval}
            onChange={(e) => {
              const val = Math.max(1, Math.min(50, Number(e.target.value) || 5));
              saveMetadata({ autoMemoryExtraction: { ...workflow.metadata?.autoMemoryExtraction, interval: val } });
            }}
            className="w-20 px-2 py-1 text-sm border border-border rounded-lg bg-surface text-text"
          />
          <p className="text-xs text-text-tertiary">{t.workflows.autoMemoryIntervalHint}</p>
        </div>
      )}
    </>
  );
}
