import { useState } from 'react';
import { Plus, Pencil, Zap, Clock, FileText, Bot, Trash2, StickyNote } from 'lucide-react';
import { Card, CardHeader, Button, Input, Select } from '../ui';
import { StatusBadge } from '../shared/StatusBadge';
import { GateStatusBadge } from '../shared/GateStatusBadge';
import { Pagination } from '../shared/Pagination';
import { WorkflowMemoryPanel } from './WorkflowMemoryPanel';
import { useUpdateWorkflow, useWorkflowRuns } from '../../hooks/useWorkflows';
import { useAiProviders, usePiCatalog } from '../../hooks/useSystem';
import { useT } from '../../i18n';
import { formatDuration } from '../../utils/formatTime';
import { modelOptionsForProvider, isFreeTextModelProvider } from '../../utils/aiProviderModels';
import type { Workflow, WorkflowRun } from '../../types';

interface WorkflowDetailSidebarProps {
  workflow: Workflow;
  workflowVariables: string[];
  onOpenEditInstructions: () => void;
  onOpenAddSkill: () => void;
  onDeactivateSkill: (workflowId: string, skillId: string) => void;
  onNavigateToRun: (runId: string) => void;
}

const RUN_PAGE_SIZE = 5;

export function WorkflowDetailSidebar({
  workflow,
  workflowVariables,
  onOpenEditInstructions,
  onOpenAddSkill,
  onDeactivateSkill,
  onNavigateToRun,
}: WorkflowDetailSidebarProps) {
  const t = useT();
  const updateWorkflow = useUpdateWorkflow();
  const { data: aiProviders } = useAiProviders();
  const isPi = isFreeTextModelProvider(workflow.provider);
  const { data: piCatalog } = usePiCatalog();
  const [runPage, setRunPage] = useState(0);
  const { data: runs } = useWorkflowRuns(workflow.id, runPage, RUN_PAGE_SIZE);

  // For PI: filter the static preset list down to backends the user actually has
  // configured locally (auth.json or custom models.json). Avoids offering options
  // that would fail at runtime with "No API key found for …".
  const piPresets = isPi
    ? (() => {
        const allowed = new Set<string>([
          ...(piCatalog?.authenticatedProviders ?? []),
          ...(piCatalog?.customProviders ?? []),
        ]);
        const all = modelOptionsForProvider('PI').filter((opt) => opt.value !== '');
        return allowed.size > 0
          ? all.filter((opt) => {
              const backend = opt.value.split('/')[0] ?? '';
              return allowed.has(backend);
            })
          : all;
      })()
    : [];

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader title={t.common.info} />
        <div className="space-y-2 text-sm">
          <div className="flex justify-between">
            <span className="text-text-secondary">{t.common.project}</span>
            <span className="text-text">{workflow.projectName}</span>
          </div>
          <div className="flex justify-between">
            <span className="text-text-secondary">{t.common.version}</span>
            <span className="text-text font-mono">{workflow.version}</span>
          </div>
          <div className="flex justify-between">
            <span className="text-text-secondary">{t.common.created}</span>
            <span className="text-text">{new Date(workflow.createdAt).toLocaleString(undefined, { year: 'numeric', month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })}</span>
          </div>
        </div>
      </Card>

      <Card>
        <CardHeader
          title={t.workflows.instructions}
          action={
            <Button
              size="sm"
              variant="ghost"
              icon={<Pencil className="w-3.5 h-3.5" />}
              onClick={onOpenEditInstructions}
            >
              {t.common.edit}
            </Button>
          }
        />
        {workflow.instructions ? (
          <pre className="text-xs text-text-secondary whitespace-pre-wrap font-mono bg-surface rounded-lg p-3 max-h-40 overflow-y-auto">
            {workflow.instructions}
          </pre>
        ) : (
          <p className="text-sm text-text-tertiary flex items-center gap-2">
            <FileText className="w-4 h-4" />
            {t.workflows.noInstructions}
          </p>
        )}
      </Card>

      <WorkflowMemoryPanel workflowId={workflow.id} runs={runs?.content} />

      <Card>
        <CardHeader title={t.workflows.provider} />
        <Select
          value={workflow.provider || ''}
          onChange={(e) => {
            const newProvider = e.target.value || undefined;
            // Reset model when switching providers — values like "anthropic/claude-…" are
            // pi-only and break Claude/Codex routing if left around.
            updateWorkflow.mutate({ id: workflow.id, data: { provider: newProvider, model: '' } });
          }}
          options={[
            { value: '', label: t.workflows.providerDefault },
            ...(aiProviders?.providers ?? []).map((p) => ({
              value: p,
              label: p === 'CLAUDE' ? 'Claude CLI' : p === 'CODEX' ? 'Codex CLI' : p === 'PI' ? 'pi (Mario Zechner)' : p,
            })),
          ]}
        />
        <p className="text-xs text-text-tertiary mt-2 flex items-center gap-1.5">
          <Bot className="w-3.5 h-3.5" />
          {t.workflows.providerHint}
        </p>
      </Card>

      <Card>
        <CardHeader title={t.workflows.model} />
        {isPi ? (
          <>
            <Input
              list="pi-model-suggestions"
              value={workflow.model || ''}
              onChange={(e) => {
                updateWorkflow.mutate({ id: workflow.id, data: { model: e.target.value } });
              }}
              placeholder="<backend>/<model-id>  z.B. anthropic/claude-sonnet-4-6"
            />
            <datalist id="pi-model-suggestions">
              {piPresets.map((opt) => (
                <option key={opt.value} value={opt.value}>{opt.label}</option>
              ))}
            </datalist>
            {piCatalog && piCatalog.installed && (
              <div className="text-xs text-text-tertiary mt-2 space-y-1">
                {piCatalog.authenticatedProviders.length > 0 && (
                  <p>
                    Authentifiziert: <span className="font-mono">{piCatalog.authenticatedProviders.join(', ')}</span>
                  </p>
                )}
                {piCatalog.customProviders.length > 0 && (
                  <p>
                    Custom (~/.pi/agent/models.json): <span className="font-mono">{piCatalog.customProviders.join(', ')}</span>
                  </p>
                )}
                {piCatalog.defaultProvider && (
                  <p>
                    pi-Default: <span className="font-mono">{piCatalog.defaultProvider}{piCatalog.defaultModel ? `/${piCatalog.defaultModel}` : ''}</span> (wird genutzt wenn das Feld leer bleibt)
                  </p>
                )}
              </div>
            )}
            {piCatalog && !piCatalog.installed && (
              <p className="text-xs text-warning mt-2">
                pi nicht gefunden in ~/.pi/agent — bitte einmalig <code className="font-mono">pi /login</code> ausführen.
              </p>
            )}
          </>
        ) : (
          <Select
            value={workflow.model || ''}
            onChange={(e) => {
              updateWorkflow.mutate({ id: workflow.id, data: { model: e.target.value } });
            }}
            options={modelOptionsForProvider(workflow.provider || aiProviders?.default).map((opt) => ({
              value: opt.value,
              label: opt.value === '' ? t.workflows.modelDefault : opt.label,
            }))}
          />
        )}
        <p className="text-xs text-text-tertiary mt-2 flex items-center gap-1.5">
          <Bot className="w-3.5 h-3.5" />
          {t.workflows.modelHint}
        </p>
      </Card>

      <Card>
        <div className="flex items-center justify-between">
          <div>
            <span className="text-sm font-medium text-text">{t.workflows.autoRepair}</span>
            <p className="text-xs text-text-tertiary">{t.workflows.autoRepairDescription}</p>
          </div>
          <button
            type="button"
            role="switch"
            aria-checked={!!workflow.metadata?.autoRepair?.enabled}
            onClick={() =>
              updateWorkflow.mutate({
                id: workflow.id,
                data: { metadata: { autoRepair: { ...workflow.metadata?.autoRepair, enabled: !workflow.metadata?.autoRepair?.enabled } } },
              })
            }
            className={`relative inline-flex h-5 w-9 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors ${
              workflow.metadata?.autoRepair?.enabled ? 'bg-accent' : 'bg-border'
            }`}
          >
            <span
              className={`pointer-events-none inline-block h-4 w-4 rounded-full bg-white shadow transform transition-transform ${
                workflow.metadata?.autoRepair?.enabled ? 'translate-x-4' : 'translate-x-0'
              }`}
            />
          </button>
        </div>
        {workflow.metadata?.autoRepair?.enabled && (
          <div className="mt-3 space-y-1">
            <span className="text-xs font-medium text-text-secondary">{t.workflows.autoRepairMode}</span>
            <select
              value={workflow.metadata?.autoRepair?.mode ?? 'auto'}
              onChange={(e) =>
                updateWorkflow.mutate({
                  id: workflow.id,
                  data: { metadata: { autoRepair: { ...workflow.metadata?.autoRepair, mode: e.target.value } } },
                })
              }
              className="w-full text-xs bg-surface border border-border rounded-md px-2 py-1.5 text-text focus:outline-none focus:ring-1 focus:ring-accent"
            >
              <option value="auto">{t.workflows.autoRepairModeAuto}</option>
              <option value="test">{t.workflows.autoRepairModeTest}</option>
              <option value="local">{t.workflows.autoRepairModeLocal}</option>
            </select>
            <p className="text-xs text-text-tertiary">{t.workflows.autoRepairModeHint}</p>
            <div className="mt-2 grid grid-cols-2 gap-2">
              <div>
                <label className="text-xs text-text-secondary">{t.workflows.autoRepairMaxRetries}</label>
                <input
                  type="number"
                  min={1}
                  max={5}
                  value={workflow.metadata?.autoRepair?.maxRetries ?? 2}
                  onChange={(e) => {
                    const val = Math.max(1, Math.min(5, parseInt(e.target.value) || 2));
                    updateWorkflow.mutate({
                      id: workflow.id,
                      data: { metadata: { autoRepair: { ...workflow.metadata?.autoRepair, maxRetries: val } } },
                    });
                  }}
                  className="w-full text-xs bg-surface border border-border rounded-md px-2 py-1.5 text-text focus:outline-none focus:ring-1 focus:ring-accent"
                />
              </div>
              <div>
                <label className="text-xs text-text-secondary">{t.workflows.autoRepairTimeout}</label>
                <input
                  type="number"
                  min={60}
                  max={3600}
                  step={60}
                  value={workflow.metadata?.autoRepair?.timeoutSeconds ?? 600}
                  onChange={(e) => {
                    const val = Math.max(60, Math.min(3600, parseInt(e.target.value) || 600));
                    updateWorkflow.mutate({
                      id: workflow.id,
                      data: { metadata: { autoRepair: { ...workflow.metadata?.autoRepair, timeoutSeconds: val } } },
                    });
                  }}
                  className="w-full text-xs bg-surface border border-border rounded-md px-2 py-1.5 text-text focus:outline-none focus:ring-1 focus:ring-accent"
                />
              </div>
            </div>
          </div>
        )}
      </Card>

      {workflowVariables.length > 0 && (
        <DefaultContextEditor
          workflowId={workflow.id}
          variables={workflowVariables}
          defaultContext={workflow.defaultContext ?? {}}
        />
      )}

      <Card>
        <CardHeader
          title={t.workflows.activatedSkills}
          action={
            <Button
              size="sm"
              variant="ghost"
              icon={<Plus className="w-3.5 h-3.5" />}
              onClick={onOpenAddSkill}
            >
              {t.workflows.activateSkill}
            </Button>
          }
        />
        {workflow.activatedSkillNames.length === 0 ? (
          <p className="text-sm text-text-tertiary">{t.workflows.noSkillsActivated}</p>
        ) : (
          <div className="space-y-2">
            {workflow.activatedSkillIds.map((skillId, idx) => (
              <div key={skillId} className="flex items-center justify-between group">
                <div className="flex items-center gap-2">
                  <Zap className="w-4 h-4 text-accent" />
                  <span className="text-sm text-text">{workflow.activatedSkillNames[idx]}</span>
                </div>
                <button
                  onClick={() => onDeactivateSkill(workflow.id, skillId)}
                  className="p-1 rounded-sm text-text-tertiary hover:text-danger sm:opacity-0 sm:group-hover:opacity-100 transition-all"
                  title={t.skills.deactivate}
                  aria-label={t.skills.deactivate}
                >
                  <Trash2 className="w-3.5 h-3.5" />
                </button>
              </div>
            ))}
          </div>
        )}
      </Card>

      <Card padding={false}>
        <div className="p-5 pb-0">
          <CardHeader title={`${t.dashboard.recentRuns} (${runs?.totalElements ?? 0})`} />
        </div>
        {!runs?.content?.length ? (
          <div className="px-5 py-8 text-center text-sm text-text-tertiary">{t.workflows.noRuns}</div>
        ) : (
          <>
            <div className="divide-y divide-border">
              {runs.content.map((run: WorkflowRun) => (
                <div
                  key={run.id}
                  className="px-5 py-3 hover:bg-surface transition-colors cursor-pointer"
                  onClick={() => onNavigateToRun(run.id)}
                >
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-2">
                      <StatusBadge status={run.status} />
                      <GateStatusBadge run={run} />
                    </div>
                    <div className="flex items-center gap-2 text-xs text-text-tertiary">
                      <Clock className="w-3 h-3" />
                      {formatDuration(run.durationMs)}
                    </div>
                  </div>
                  {(run.metadata?.notes as string) && (
                    <p className="text-xs text-text-tertiary mt-1 truncate">
                      <StickyNote className="w-3 h-3 text-accent inline mr-1 -mt-0.5" />
                      {(run.metadata.notes as string).slice(0, 80)}
                      {(run.metadata.notes as string).length > 80 ? '...' : ''}
                    </p>
                  )}
                </div>
              ))}
            </div>
            {runs.totalPages > 1 && (
              <div className="px-5 py-2">
                <Pagination
                  page={runPage}
                  totalPages={runs.totalPages}
                  totalElements={runs.totalElements}
                  label=""
                  setPage={setRunPage}
                />
              </div>
            )}
          </>
        )}
      </Card>

    </div>
  );
}

function DefaultContextEditor({
  workflowId,
  variables,
  defaultContext,
}: {
  workflowId: string;
  variables: string[];
  defaultContext: Record<string, string>;
}) {
  const t = useT();
  const updateWorkflow = useUpdateWorkflow();
  const [values, setValues] = useState<Record<string, string>>(() =>
    Object.fromEntries(variables.map((v) => [v, defaultContext[v] ?? '']))
  );

  const contextKey = JSON.stringify(defaultContext);
  const [prevContextKey, setPrevContextKey] = useState(contextKey);
  if (contextKey !== prevContextKey) {
    setPrevContextKey(contextKey);
    setValues(Object.fromEntries(variables.map((v) => [v, defaultContext[v] ?? ''])));
  }

  const hasChanges = variables.some((v) => (values[v] ?? '') !== (defaultContext[v] ?? ''));

  const handleSave = () => {
    const ctx: Record<string, string> = {};
    for (const v of variables) {
      if (values[v]?.trim()) {
        ctx[v] = values[v];
      }
    }
    updateWorkflow.mutate({ id: workflowId, data: { defaultContext: ctx } });
  };

  return (
    <Card>
      <CardHeader
        title={t.workflows.defaultValues}
        action={
          hasChanges ? (
            <Button
              size="sm"
              variant="secondary"
              onClick={handleSave}
              loading={updateWorkflow.isPending}
            >
              {t.common.save}
            </Button>
          ) : undefined
        }
      />
      <div className="space-y-3">
        <p className="text-xs text-text-tertiary">{t.workflows.defaultValuesHint}</p>
        {variables.map((variable) => (
          <Input
            key={variable}
            label={variable}
            value={values[variable] ?? ''}
            onChange={(e) => setValues({ ...values, [variable]: e.target.value })}
            placeholder={`${t.workflows.enterValue} ${variable}`}
          />
        ))}
      </div>
    </Card>
  );
}
