import { useSyncedState } from '../../hooks/useSyncedState';
import { Card, CardHeader, Button, Toggle } from '../ui';
import { useT } from '../../i18n';
import { useExecutionConfig, useUpdateExecutionConfig } from '../../hooks/useSystem';
import { Zap, Save, Loader2 } from 'lucide-react';
import { Spinner } from '../ui';
import { providerName } from '../shared/aiProviderStatus';
import {
  DEFAULT_FORM, hasChanges as formHasChanges, toFormState, toPayload,
  type ExecutionSettingsForm, type FlagSettingKey, type NumericSettingKey,
} from './executionSettingsForm';

interface FieldConfig {
  key: string;
  label: string;
  hint: string;
  min: number;
  max: number;
  configKey: NumericSettingKey;
}

interface ToggleConfig {
  configKey: FlagSettingKey;
  label: string;
  hint: string;
}

export function ExecutionSettingsCard() {
  const t = useT();
  const { data: config, isLoading } = useExecutionConfig();
  const updateConfig = useUpdateExecutionConfig();

  // Form follows the server config; local edits survive until a new config object arrives.
  const [form, setForm] = useSyncedState<ExecutionSettingsForm, typeof config>(
    config, (c, prev) => (c ? toFormState(c) : prev ?? DEFAULT_FORM),
  );

  const hasChanges = formHasChanges(form, config);

  const fields: FieldConfig[] = [
    { key: 'maxRuns', label: t.settings.executionMaxConcurrentRuns, hint: t.settings.executionMaxConcurrentRunsHint, min: 1, max: 50, configKey: 'executionMaxConcurrent' },
    { key: 'timeout', label: t.settings.executionCliTimeout, hint: t.settings.executionCliTimeoutHint, min: 30, max: 3600, configKey: 'claudeTimeoutSeconds' },
    { key: 'delay', label: t.settings.executionInterCallDelay, hint: t.settings.executionInterCallDelayHint, min: 0, max: 30000, configKey: 'claudeInterCallDelayMs' },
    { key: 'chain', label: t.settings.executionChainTimeout, hint: t.settings.executionChainTimeoutHint, min: 5, max: 1440, configKey: 'chainWorkflowTimeoutMinutes' },
    { key: 'rateRpm', label: t.settings.executionRateLimitRpm, hint: t.settings.executionRateLimitRpmHint, min: 10, max: 10000, configKey: 'rateLimitRequestsPerMinute' },
    { key: 'assistantTimeout', label: t.settings.executionAssistantChatTimeout, hint: t.settings.executionAssistantChatTimeoutHint, min: 30, max: 900, configKey: 'assistantChatTimeoutSeconds' },
    { key: 'resumeGrace', label: t.settings.schedulerResumeGraceMinutes, hint: t.settings.schedulerResumeGraceMinutesHint, min: 1, max: 60, configKey: 'schedulerResumeGraceMinutes' },
    { key: 'autoResumeDelay', label: t.settings.runAutoResumeDelaySeconds, hint: t.settings.runAutoResumeDelaySecondsHint, min: 0, max: 3600, configKey: 'runAutoResumeDelaySeconds' },
    { key: 'summarizationTimeout', label: t.settings.summarizationTimeoutSeconds, hint: t.settings.summarizationTimeoutSecondsHint, min: 30, max: 600, configKey: 'summarizationTimeoutSeconds' },
    { key: 'factoryTimeout', label: t.settings.factoryTimeoutSeconds, hint: t.settings.factoryTimeoutSecondsHint, min: 60, max: 3600, configKey: 'factoryTimeoutSeconds' },
    { key: 'runRetentionDays', label: t.settings.runRetentionDays, hint: t.settings.runRetentionDaysHint, min: 0, max: 730, configKey: 'runRetentionDays' },
    { key: 'runRetentionMaxPerNight', label: t.settings.runRetentionMaxPerNight, hint: t.settings.runRetentionMaxPerNightHint, min: 10, max: 5000, configKey: 'runRetentionMaxPerNight' },
    { key: 'dashboardLayoutRetentionCount', label: t.settings.dashboardLayoutRetentionCount, hint: t.settings.dashboardLayoutRetentionCountHint, min: 1, max: 1000, configKey: 'dashboardLayoutRetentionCount' },
  ];

  const toggles: ToggleConfig[] = [
    { configKey: 'rateLimitEnabled', label: t.settings.executionRateLimitEnabled, hint: t.settings.executionRateLimitEnabledHint },
    { configKey: 'schedulerCatchUpOnResume', label: t.settings.schedulerCatchUpOnResume, hint: t.settings.schedulerCatchUpOnResumeHint },
    // Automatic resume of interrupted runs (TODO 90d10ba1)
    { configKey: 'runAutoResumeEnabled', label: t.settings.runAutoResumeEnabled, hint: t.settings.runAutoResumeEnabledHint },
    // Editor AI proposal for dashboard-report blocks
    { configKey: 'dashboardReportAssistEnabled', label: t.settings.dashboardReportAssist, hint: t.settings.dashboardReportAssistHint },
    // Keep finished runs' live logs; workflows can override it (TODO bbbfcbf8)
    { configKey: 'liveLogPersistDefault', label: t.settings.liveLogPersistDefault, hint: t.settings.liveLogPersistDefaultHint },
  ];

  const handleSave = () => updateConfig.mutate(toPayload(form));

  if (isLoading) {
    return (
      <Card>
        <CardHeader title={t.settings.execution} subtitle={t.settings.executionSubtitle} />
        <div className="flex justify-center py-8"><Spinner /></div>
      </Card>
    );
  }

  // activeRuns counts running + queued; show what actually occupies a slot and the queue separately
  const queuedRuns = config?.queuedRuns ?? 0;
  const runningRuns = Math.max(0, (config?.activeRuns ?? 0) - queuedRuns);
  const maxRuns = config?.executionMaxConcurrent ?? 10;
  const usagePercent = Math.min(100, Math.round((runningRuns / maxRuns) * 100));

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
              <span className="text-sm font-mono text-text-secondary">
                {runningRuns} / {maxRuns}
                {queuedRuns > 0 && (
                  <span className="text-text-tertiary"> (+{queuedRuns} {t.settings.executionQueuedRuns})</span>
                )}
              </span>
            </div>
            <div className="h-1.5 bg-surface-hover rounded-full overflow-hidden">
              <div
                className={`h-full rounded-full transition-all ${usagePercent > 80 ? 'bg-danger' : usagePercent > 50 ? 'bg-warning' : 'bg-accent'}`}
                style={{ width: `${usagePercent}%` }}
              />
            </div>
          </div>
        </div>

        {toggles.map(({ configKey, label, hint }) => (
          <div key={configKey} className="flex items-center justify-between py-2">
            <div>
              <label className="block text-sm font-medium text-text">{label}</label>
              <p className="text-xs text-text-tertiary mt-0.5">{hint}</p>
            </div>
            <Toggle
              enabled={form[configKey]}
              onChange={(v) => setForm(prev => ({ ...prev, [configKey]: v }))}
              ariaLabel={label}
            />
          </div>
        ))}

        {/* AI CLI limits per provider and across all of them (TODO bd5df8d7) */}
        <CliLimitsTable form={form} onChange={(key, value) => setForm(prev => ({ ...prev, [key]: value }))} />

        {/* Settings grid */}
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {fields.map(({ key, label, hint, min, max, configKey }) => {
            const isDisabled = key === 'rateRpm' && !form.rateLimitEnabled;
            return (
              <div key={key} className={isDisabled ? 'opacity-50' : ''}>
                <label className="block text-sm font-medium text-text mb-1">{label}</label>
                <input
                  type="number"
                  min={min}
                  max={max}
                  disabled={isDisabled}
                  value={form[configKey]}
                  onChange={(e) => {
                    const val = parseInt(e.target.value, 10);
                    if (!isNaN(val)) {
                      setForm(prev => ({ ...prev, [configKey]: val }));
                    }
                  }}
                  className="w-full px-3 py-1.5 text-sm bg-surface-hover border border-border rounded-lg text-text focus:outline-none focus:ring-1 focus:ring-accent font-mono disabled:cursor-not-allowed"
                />
                <p className="text-xs text-text-tertiary mt-1">{hint}</p>
              </div>
            );
          })}
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

type CliLimitKey = Extract<NumericSettingKey, `${string}MaxConcurrentCalls` | `${string}ReservedForInteractive`>;

/** One row per provider plus the overall limit; the bounds match ExecutionSettingKeys in the backend. */
const CLI_LIMIT_ROWS: { id: string | null; max: CliLimitKey; reserved: CliLimitKey; maxLimit: number }[] = [
  { id: 'CLAUDE', max: 'claudeMaxConcurrentCalls', reserved: 'claudeReservedForInteractive', maxLimit: 20 },
  { id: 'PI', max: 'piMaxConcurrentCalls', reserved: 'piReservedForInteractive', maxLimit: 20 },
  { id: 'CODEX', max: 'codexMaxConcurrentCalls', reserved: 'codexReservedForInteractive', maxLimit: 20 },
  { id: null, max: 'aiCliMaxConcurrentCalls', reserved: 'aiCliReservedForInteractive', maxLimit: 50 },
];

/**
 * How many AI CLI processes may run at once, per provider and across all providers, and how many of those
 * slots stay free for the chat. A call needs a slot with its provider and one in the overall limit.
 */
function CliLimitsTable({ form, onChange }: { form: ExecutionSettingsForm; onChange: (key: CliLimitKey, value: number) => void }) {
  const t = useT();
  const ts = t.settings;
  return (
    <div>
      <p className="text-sm font-medium text-text">{ts.aiCliLimits}</p>
      <p className="text-xs text-text-tertiary mt-0.5">{ts.aiCliLimitsHint}</p>
      <table className="mt-2 w-full text-sm" data-testid="ai-cli-limits">
        <thead>
          <tr className="text-xs text-text-secondary">
            <th scope="col" className="py-1 pr-3 text-left font-medium">{ts.aiCliColumnProvider}</th>
            <th scope="col" className="py-1 pr-3 text-left font-medium">{ts.executionMaxConcurrentCalls}</th>
            <th scope="col" className="py-1 text-left font-medium">{ts.executionReservedForInteractive}</th>
          </tr>
        </thead>
        <tbody>
          {CLI_LIMIT_ROWS.map((row) => {
            const label = row.id ? providerName(row.id) : ts.aiCliAll;
            return (
              <tr key={row.max} className={row.id ? '' : 'border-t border-border'}>
                <th scope="row" className="py-1 pr-3 text-left font-normal text-text">{label}</th>
                <td className="py-1 pr-3">
                  <LimitInput value={form[row.max]} min={1} max={row.maxLimit}
                    label={ts.aiCliConcurrentFor.replace('{provider}', label)} onChange={(v) => onChange(row.max, v)} />
                </td>
                <td className="py-1">
                  <LimitInput value={form[row.reserved]} min={0} max={10}
                    label={ts.aiCliReservedFor.replace('{provider}', label)} onChange={(v) => onChange(row.reserved, v)} />
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function LimitInput({ value, min, max, label, onChange }: {
  value: number; min: number; max: number; label: string; onChange: (value: number) => void;
}) {
  return (
    <input
      type="number"
      min={min}
      max={max}
      value={value}
      aria-label={label}
      onChange={(e) => {
        const val = parseInt(e.target.value, 10);
        if (!isNaN(val)) onChange(val);
      }}
      className="w-full max-w-28 px-3 py-1.5 text-sm bg-surface-hover border border-border rounded-lg text-text focus:outline-none focus:ring-1 focus:ring-accent font-mono"
    />
  );
}
