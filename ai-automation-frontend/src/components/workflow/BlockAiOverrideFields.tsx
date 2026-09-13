import { Bot } from 'lucide-react';
import { Select } from '../ui';
import { ModelField } from '../shared/ModelField';
import { useAiProviders } from '../../hooks/useSystem';
import { useT } from '../../i18n';
import { AI_BLOCK_TYPES, type BlockType } from '../../types';

interface BlockAiOverrideFieldsProps {
  blockType: BlockType;
  /** The block config as edited in the JSON editor — the fields read and write its `provider`/`model` keys. */
  configJson: string;
  onConfigChange: (configJson: string) => void;
  /** Workflow-level provider (null/empty = system default); drives the model suggestions when the block inherits it. */
  workflowProvider?: string | null;
  /** Keeps datalist ids unique when several editors are mounted (add + edit modal). */
  idSuffix?: string;
}

function parseConfig(configJson: string): Record<string, unknown> | null {
  try {
    const parsed: unknown = JSON.parse(configJson);
    return parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)
      ? (parsed as Record<string, unknown>)
      : null;
  } catch {
    return null;
  }
}

function stringValue(config: Record<string, unknown>, key: string): string {
  const v = config[key];
  return typeof v === 'string' ? v : '';
}

/**
 * Block-level AI override (config keys `provider` and `model`) for AI-capable blocks.
 * Renders nothing for block types without an AI call — `integration` for example uses
 * `provider` for its integration provider id and must not get these fields.
 * Precedence at runtime: block > workflow > system default.
 */
export function BlockAiOverrideFields({
  blockType,
  configJson,
  onConfigChange,
  workflowProvider,
  idSuffix = '',
}: BlockAiOverrideFieldsProps) {
  const t = useT();
  const { data: aiProviders } = useAiProviders();
  const isAiBlock = AI_BLOCK_TYPES.includes(blockType);
  const config = isAiBlock ? parseConfig(configJson) : null;

  if (!isAiBlock) return null;

  const blockProvider = config ? stringValue(config, 'provider') : '';
  const blockModel = config ? stringValue(config, 'model') : '';
  const effectiveProvider = blockProvider || workflowProvider || aiProviders?.default || 'CLAUDE';

  const write = (next: { provider?: string; model?: string }) => {
    if (!config) return;
    const updated: Record<string, unknown> = { ...config };
    const apply = (key: 'provider' | 'model', value: string | undefined) => {
      if (value === undefined) return;
      if (value.trim()) updated[key] = value.trim();
      else delete updated[key];
    };
    apply('provider', next.provider);
    apply('model', next.model);
    onConfigChange(JSON.stringify(updated, null, 2));
  };

  return (
    <div data-testid="block-ai-override" className="rounded-lg border border-border p-3 space-y-3">
      <div>
        <p className="text-sm font-medium text-text flex items-center gap-1.5">
          <Bot className="w-3.5 h-3.5 text-accent" />
          {t.workflows.blockAiOverride}
        </p>
        <p className="text-xs text-text-tertiary mt-0.5">{t.workflows.blockAiOverrideHint}</p>
      </div>
      {config ? (
        <div className="grid gap-3 sm:grid-cols-2">
          <Select
            data-testid="block-ai-provider"
            label={t.workflows.blockProvider}
            value={blockProvider}
            onChange={(e) => {
              const provider = e.target.value;
              // Model ids are provider-specific — switching the provider clears the block model.
              write({ provider, model: provider !== blockProvider ? '' : undefined });
            }}
            options={[
              { value: '', label: t.workflows.blockProviderInherit },
              ...(aiProviders?.providers ?? []).map((p) => ({ value: p, label: p })),
            ]}
          />
          <div>
            <label className="block text-sm font-medium text-text-secondary mb-1.5">{t.workflows.blockModel}</label>
            <ModelField
              providerId={effectiveProvider}
              value={blockModel}
              onChange={(model) => write({ model })}
              idSuffix={idSuffix}
            />
          </div>
        </div>
      ) : (
        <p className="text-xs text-warning">{t.workflows.blockAiOverrideInvalidJson}</p>
      )}
    </div>
  );
}
