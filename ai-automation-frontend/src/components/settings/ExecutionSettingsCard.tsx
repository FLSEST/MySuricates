import { useState, useEffect } from 'react';
import { Card, CardHeader, Button } from '../ui';
import { useT } from '../../i18n';
import { useExecutionConfig, useUpdateExecutionConfig } from '../../hooks/useSystem';
import { Zap, Save, Loader2 } from 'lucide-react';
import { Spinner } from '../ui';

interface FieldConfig {
  key: string;
  label: string;
  hint: string;
  min: number;
  max: number;
  configKey: keyof FormState;
}

interface FormState {
  executionMaxConcurrent: number;
  claudeMaxConcurrentCalls: number;
  claudeTimeoutSeconds: number;
  claudeInterCallDelayMs: number;
  chainWorkflowTimeoutMinutes: number;
}

export function ExecutionSettingsCard() {
  const t = useT();
  const { data: config, isLoading } = useExecutionConfig();
  const updateConfig = useUpdateExecutionConfig();

  const [form, setForm] = useState<FormState>({
    executionMaxConcurrent: 10,
    claudeMaxConcurrentCalls: 3,
    claudeTimeoutSeconds: 300,
    claudeInterCallDelayMs: 0,
    chainWorkflowTimeoutMinutes: 180,
  });

  useEffect(() => {
    if (config) {
      setForm({
        executionMaxConcurrent: config.executionMaxConcurrent,
        claudeMaxConcurrentCalls: config.claudeMaxConcurrentCalls,
        claudeTimeoutSeconds: config.claudeTimeoutSeconds,
        claudeInterCallDelayMs: config.claudeInterCallDelayMs,
        chainWorkflowTimeoutMinutes: config.chainWorkflowTimeoutMinutes,
      });
    }
  }, [config]);

  const hasChanges = config && (
    form.executionMaxConcurrent !== config.executionMaxConcurrent ||
    form.claudeMaxConcurrentCalls !== config.claudeMaxConcurrentCalls ||
    form.claudeTimeoutSeconds !== config.claudeTimeoutSeconds ||
    form.claudeInterCallDelayMs !== config.claudeInterCallDelayMs ||
    form.chainWorkflowTimeoutMinutes !== config.chainWorkflowTimeoutMinutes
  );

  const fields: FieldConfig[] = [
    { key: 'maxRuns', label: t.settings.executionMaxConcurrentRuns, hint: t.settings.executionMaxConcurrentRunsHint, min: 1, max: 50, configKey: 'executionMaxConcurrent' },
    { key: 'maxCalls', label: t.settings.executionMaxConcurrentCalls, hint: t.settings.executionMaxConcurrentCallsHint, min: 1, max: 20, configKey: 'claudeMaxConcurrentCalls' },
    { key: 'timeout', label: t.settings.executionCliTimeout, hint: t.settings.executionCliTimeoutHint, min: 30, max: 3600, configKey: 'claudeTimeoutSeconds' },
    { key: 'delay', label: t.settings.executionInterCallDelay, hint: t.settings.executionInterCallDelayHint, min: 0, max: 30000, configKey: 'claudeInterCallDelayMs' },
    { key: 'chain', label: t.settings.executionChainTimeout, hint: t.settings.executionChainTimeoutHint, min: 5, max: 1440, configKey: 'chainWorkflowTimeoutMinutes' },
  ];

  const handleSave = () => {
    updateConfig.mutate({
      executionMaxConcurrent: form.executionMaxConcurrent,
      claudeMaxConcurrentCalls: form.claudeMaxConcurrentCalls,
      claudeTimeoutSeconds: form.claudeTimeoutSeconds,
      claudeInterCallDelayMs: form.claudeInterCallDelayMs,
      chainWorkflowTimeoutMinutes: form.chainWorkflowTimeoutMinutes,
    });
  };

  if (isLoading) {
    return (
      <Card>
        <CardHeader title={t.settings.execution} subtitle={t.settings.executionSubtitle} />
        <div className="flex justify-center py-8"><Spinner /></div>
      </Card>
    );
  }

  const activeRuns = config?.activeRuns ?? 0;
  const maxRuns = config?.executionMaxConcurrent ?? 10;
  const usagePercent = Math.min(100, Math.round((activeRuns / maxRuns) * 100));

  return (
    <Card>
      <CardHeader title={t.settings.execution} subtitle={t.settings.executionSubtitle} />
      <div className="space-y-5">
        {/* Active runs status */}
        <div className="flex items-center gap-3">
          <Zap className="w-5 h-5 text-text-secondary" />
          <div className="flex-1">
            <div className="flex items-center justify-between mb-1">
              <p className="text-sm font-medium text-text">{t.settings.executionActiveRuns}</p>
              <span className="text-sm font-mono text-text-secondary">{activeRuns} / {maxRuns}</span>
            </div>
            <div className="h-1.5 bg-surface-hover rounded-full overflow-hidden">
              <div
                className={`h-full rounded-full transition-all ${usagePercent > 80 ? 'bg-red-500' : usagePercent > 50 ? 'bg-yellow-500' : 'bg-accent'}`}
                style={{ width: `${usagePercent}%` }}
              />
            </div>
          </div>
        </div>

        {/* Settings grid */}
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {fields.map(({ key, label, hint, min, max, configKey }) => (
            <div key={key}>
              <label className="block text-sm font-medium text-text mb-1">{label}</label>
              <input
                type="number"
                min={min}
                max={max}
                value={form[configKey]}
                onChange={(e) => {
                  const val = parseInt(e.target.value, 10);
                  if (!isNaN(val)) {
                    setForm(prev => ({ ...prev, [configKey]: val }));
                  }
                }}
                className="w-full px-3 py-1.5 text-sm bg-surface-hover border border-border rounded-lg text-text focus:outline-none focus:ring-1 focus:ring-accent font-mono"
              />
              <p className="text-xs text-text-tertiary mt-1">{hint}</p>
            </div>
          ))}
        </div>

        {/* Save button */}
        <div className="flex justify-end pt-2">
          <Button
            size="sm"
            disabled={!hasChanges || updateConfig.isPending}
            onClick={handleSave}
          >
            {updateConfig.isPending ? (
              <Loader2 className="w-4 h-4 animate-spin" />
            ) : (
              <Save className="w-4 h-4 mr-1.5" />
            )}
            {t.settings.executionSave}
          </Button>
        </div>
      </div>
    </Card>
  );
}
