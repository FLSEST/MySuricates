import { Bot } from 'lucide-react';
import { Card, CardHeader, Select } from '../ui';
import { ModelField } from '../shared/ModelField';
import { useUpdateWorkflow } from '../../hooks/useWorkflows';
import { useAiProviders } from '../../hooks/useSystem';
import { useT } from '../../i18n';
import type { Workflow } from '../../types';

/**
 * AI cards of the detail sidebar: provider, model (suggestions from the provider's catalog) and
 * effort. Rendered as siblings, inside the sidebar's fieldset that locks sealed definitions.
 */
export function WorkflowAiSettingsCards({ workflow }: { workflow: Workflow }) {
  const t = useT();
  const updateWorkflow = useUpdateWorkflow();
  const { data: aiProviders } = useAiProviders();
  const activeProvider = workflow.provider || aiProviders?.default || '';
  // Show the effort control unless the active provider explicitly declares no support
  // (Codex). Missing capabilities (older backend) fall back to showing it.
  const supportsEffort = aiProviders?.capabilities?.[activeProvider]?.supportsEffort !== false;

  return (
    <>
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
        <ModelField
          providerId={activeProvider || 'CLAUDE'}
          value={workflow.model || ''}
          onChange={(model) => updateWorkflow.mutate({ id: workflow.id, data: { model } })}
          showPiHints={activeProvider === 'PI'}
        />
        <p className="text-xs text-text-tertiary mt-2 flex items-center gap-1.5">
          <Bot className="w-3.5 h-3.5" />
          {t.workflows.modelHint}
        </p>
      </Card>

      {supportsEffort && (
        <Card>
          <CardHeader title={t.workflows.effort} />
          <Select
            value={workflow.effort || ''}
            onChange={(e) => {
              updateWorkflow.mutate({ id: workflow.id, data: { effort: e.target.value } });
            }}
            options={[
              { value: '', label: t.workflows.effortDefault },
              { value: 'low', label: t.workflows.effortLow },
              { value: 'medium', label: t.workflows.effortMedium },
              { value: 'high', label: t.workflows.effortHigh },
              { value: 'xhigh', label: t.workflows.effortXhigh },
            ]}
          />
          <p className="text-xs text-text-tertiary mt-2 flex items-center gap-1.5">
            <Bot className="w-3.5 h-3.5" />
            {t.workflows.effortHint}
          </p>
        </Card>
      )}
    </>
  );
}
