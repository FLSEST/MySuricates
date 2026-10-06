import { useSyncedState } from '../../hooks/useSyncedState';
import { Card, CardHeader, Button, Toggle } from '../ui';
import { useT } from '../../i18n';
import { useExecutionConfig, useUpdateExecutionConfig } from '../../hooks/useSystem';
import { Zap, Save, Loader2 } from 'lucide-react';
import { Spinner } from '../ui';
import { providerName } from '../shared/aiProviderStatus';

type NumericFormKeys = Exclude<keyof FormState, 'rateLimitEnabled' | 'schedulerCatchUpOnResume' | 'runAutoResumeEnabled' | 'dashboardReportAssistEnabled'>;

interface FieldConfig {
  key: string;
  label: string;
  hint: string;
  min: number;
  max: number;
  configKey: NumericFormKeys;
}

interface FormState {
  executionMaxConcurrent: number;
  claudeMaxConcurrentCalls: number;
  claudeReservedForInteractive: number;
  piMaxConcurrentCalls: number;
  piReservedForInteractive: number;
  codexMaxConcurrentCalls: number;
  codexReservedForInteractive: number;
  aiCliMaxConcurrentCalls: number;
  aiCliReservedForInteractive: number;
  claudeTimeoutSeconds: number;
  claudeInterCallDelayMs: number;
  chainWorkflowTimeoutMinutes: number;
  rateLimitEnabled: boolean;
  rateLimitRequestsPerMinute: number;
  assistantChatTimeoutSeconds: number;
  schedulerCatchUpOnResume: boolean;
  schedulerResumeGraceMinutes: number;
  runAutoResumeEnabled: boolean;
  runAutoResumeDelaySeconds: number;
  summarizationTimeoutSeconds: number;
  factoryTimeoutSeconds: number;
  runRetentionDays: number;
  runRetentionMaxPerNight: number;
  dashboardLayoutRetentionCount: number;
  dashboardReportAssistEnabled: boolean;
}

const DEFAULT_FORM: FormState = {
  executionMaxConcurrent: 10,
  claudeMaxConcurrentCalls: 5,
  claudeReservedForInteractive: 1,
  piMaxConcurrentCalls: 5,
  piReservedForInteractive: 1,
  codexMaxConcurrentCalls: 5,
  codexReservedForInteractive: 1,
  aiCliMaxConcurrentCalls: 8,
  aiCliReservedForInteractive: 1,
  claudeTimeoutSeconds: 300,
  claudeInterCallDelayMs: 0,
  chainWorkflowTimeoutMinutes: 180,
  rateLimitEnabled: true,
  rateLimitRequestsPerMinute: 120,
  assistantChatTimeoutSeconds: 240,
  schedulerCatchUpOnResume: true,
  schedulerResumeGraceMinutes: 5,
  runAutoResumeEnabled: true,
  runAutoResumeDelaySeconds: 30,
  summarizationTimeoutSeconds: 120,
  factoryTimeoutSeconds: 600,
  runRetentionDays: 90,
  runRetentionMaxPerNight: 200,
  dashboardLayoutRetentionCount: 100,
  dashboardReportAssistEnabled: true,
};

function toFormState(config: NonNullable<ReturnType<typeof useExecutionConfig>['data']>): FormState {
  return {
    executionMaxConcurrent: config.executionMaxConcurrent,
    claudeMaxConcurrentCalls: config.claudeMaxConcurrentCalls,
    claudeReservedForInteractive: config.claudeReservedForInteractive ?? 1,
    piMaxConcurrentCalls: config.piMaxConcurrentCalls ?? 5,
    piReservedForInteractive: config.piReservedForInteractive ?? 1,
    codexMaxConcurrentCalls: config.codexMaxConcurrentCalls ?? 5,
    codexReservedForInteractive: config.codexReservedForInteractive ?? 1,
    aiCliMaxConcurrentCalls: config.aiCliMaxConcurrentCalls ?? 8,
    aiCliReservedForInteractive: config.aiCliReservedForInteractive ?? 1,
    claudeTimeoutSeconds: config.claudeTimeoutSeconds,
    claudeInterCallDelayMs: config.claudeInterCallDelayMs,
    chainWorkflowTimeoutMinutes: config.chainWorkflowTimeoutMinutes,
    rateLimitEnabled: config.rateLimitEnabled === 1,
    rateLimitRequestsPerMinute: config.rateLimitRequestsPerMinute,
    assistantChatTimeoutSeconds: config.assistantChatTimeoutSeconds ?? 240,
    schedulerCatchUpOnResume: (config.schedulerCatchUpOnResume ?? 1) === 1,
    schedulerResumeGraceMinutes: config.schedulerResumeGraceMinutes ?? 5,
    runAutoResumeEnabled: (config.runAutoResumeEnabled ?? 1) === 1,
    runAutoResumeDelaySeconds: config.runAutoResumeDelaySeconds ?? 30,
    summarizationTimeoutSeconds: config.summarizationTimeoutSeconds ?? 120,
    factoryTimeoutSeconds: config.factoryTimeoutSeconds ?? 600,
    runRetentionDays: config.runRetentionDays ?? 90,
    runRetentionMaxPerNight: config.runRetentionMaxPerNight ?? 200,
    dashboardLayoutRetentionCount: config.dashboardLayoutRetentionCount ?? 100,
    dashboardReportAssistEnabled: (config.dashboardReportAssistEnabled ?? 1) === 1,
  };
}

export function ExecutionSettingsCard() {
  const t = useT();
  const { data: config, isLoading } = useExecutionConfig();
  const updateConfig = useUpdateExecutionConfig();

  // Form follows the server config; local edits survive until a new config object arrives.
  const [form, setForm] = useSyncedState<FormState, typeof config>(
    config, (c, prev) => (c ? toFormState(c) : prev ?? DEFAULT_FORM),
  );

  const hasChanges = config && (
    form.executionMaxConcurrent !== config.executionMaxConcurrent ||
    form.claudeMaxConcurrentCalls !== config.claudeMaxConcurrentCalls ||
    form.claudeReservedForInteractive !== (config.claudeReservedForInteractive ?? 1) ||
    form.piMaxConcurrentCalls !== (config.piMaxConcurrentCalls ?? 5) ||
    form.piReservedForInteractive !== (config.piReservedForInteractive ?? 1) ||
    form.codexMaxConcurrentCalls !== (config.codexMaxConcurrentCalls ?? 5) ||
    form.codexReservedForInteractive !== (config.codexReservedForInteractive ?? 1) ||
    form.aiCliMaxConcurrentCalls !== (config.aiCliMaxConcurrentCalls ?? 8) ||
    form.aiCliReservedForInteractive !== (config.aiCliReservedForInteractive ?? 1) ||
    form.claudeTimeoutSeconds !== config.claudeTimeoutSeconds ||
    form.claudeInterCallDelayMs !== config.claudeInterCallDelayMs ||
    form.chainWorkflowTimeoutMinutes !== config.chainWorkflowTimeoutMinutes ||
    form.rateLimitEnabled !== (config.rateLimitEnabled === 1) ||
    form.rateLimitRequestsPerMinute !== config.rateLimitRequestsPerMinute ||
    form.assistantChatTimeoutSeconds !== (config.assistantChatTimeoutSeconds ?? 240) ||
    form.schedulerCatchUpOnResume !== ((config.schedulerCatchUpOnResume ?? 1) === 1) ||
    form.schedulerResumeGraceMinutes !== (config.schedulerResumeGraceMinutes ?? 5) ||
    form.runAutoResumeEnabled !== ((config.runAutoResumeEnabled ?? 1) === 1) ||
    form.runAutoResumeDelaySeconds !== (config.runAutoResumeDelaySeconds ?? 30) ||
    form.summarizationTimeoutSeconds !== (config.summarizationTimeoutSeconds ?? 120) ||
    form.factoryTimeoutSeconds !== (config.factoryTimeoutSeconds ?? 600) ||
    form.runRetentionDays !== (config.runRetentionDays ?? 90) ||
    form.runRetentionMaxPerNight !== (config.runRetentionMaxPerNight ?? 200) ||
    form.dashboardLayoutRetentionCount !== (config.dashboardLayoutRetentionCount ?? 100) ||
    form.dashboardReportAssistEnabled !== ((config.dashboardReportAssistEnabled ?? 1) === 1)
  );

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

  const handleSave = () => {
    updateConfig.mutate({
      executionMaxConcurrent: form.executionMaxConcurrent,
      claudeMaxConcurrentCalls: form.claudeMaxConcurrentCalls,
      claudeReservedForInteractive: form.claudeReservedForInteractive,
      piMaxConcurrentCalls: form.piMaxConcurrentCalls,
      piReservedForInteractive: form.piReservedForInteractive,
      codexMaxConcurrentCalls: form.codexMaxConcurrentCalls,
      codexReservedForInteractive: form.codexReservedForInteractive,
      aiCliMaxConcurrentCalls: form.aiCliMaxConcurrentCalls,
      aiCliReservedForInteractive: form.aiCliReservedForInteractive,
      claudeTimeoutSeconds: form.claudeTimeoutSeconds,
      claudeInterCallDelayMs: form.claudeInterCallDelayMs,
      chainWorkflowTimeoutMinutes: form.chainWorkflowTimeoutMinutes,
      rateLimitEnabled: form.rateLimitEnabled ? 1 : 0,
      rateLimitRequestsPerMinute: form.rateLimitRequestsPerMinute,
      assistantChatTimeoutSeconds: form.assistantChatTimeoutSeconds,
      schedulerCatchUpOnResume: form.schedulerCatchUpOnResume ? 1 : 0,
      schedulerResumeGraceMinutes: form.schedulerResumeGraceMinutes,
      runAutoResumeEnabled: form.runAutoResumeEnabled ? 1 : 0,
      runAutoResumeDelaySeconds: form.runAutoResumeDelaySeconds,
      summarizationTimeoutSeconds: form.summarizationTimeoutSeconds,
      factoryTimeoutSeconds: form.factoryTimeoutSeconds,
      runRetentionDays: form.runRetentionDays,
      runRetentionMaxPerNight: form.runRetentionMaxPerNight,
      dashboardLayoutRetentionCount: form.dashboardLayoutRetentionCount,
      dashboardReportAssistEnabled: form.dashboardReportAssistEnabled ? 1 : 0,
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

        {/* Rate limiting toggle */}
        <div className="flex items-center justify-between py-2">
          <div>
            <label className="block text-sm font-medium text-text">{t.settings.executionRateLimitEnabled}</label>
            <p className="text-xs text-text-tertiary mt-0.5">{t.settings.executionRateLimitEnabledHint}</p>
          </div>
          <Toggle
            enabled={form.rateLimitEnabled}
            onChange={(v) => setForm(prev => ({ ...prev, rateLimitEnabled: v }))}
          />
        </div>

        {/* Catch-up on resume toggle */}
        <div className="flex items-center justify-between py-2">
          <div>
            <label className="block text-sm font-medium text-text">{t.settings.schedulerCatchUpOnResume}</label>
            <p className="text-xs text-text-tertiary mt-0.5">{t.settings.schedulerCatchUpOnResumeHint}</p>
          </div>
          <Toggle
            enabled={form.schedulerCatchUpOnResume}
            onChange={(v) => setForm(prev => ({ ...prev, schedulerCatchUpOnResume: v }))}
          />
        </div>

        {/* Automatic resume of interrupted runs (TODO 90d10ba1) */}
        <div className="flex items-center justify-between py-2">
          <div>
            <label className="block text-sm font-medium text-text">{t.settings.runAutoResumeEnabled}</label>
            <p className="text-xs text-text-tertiary mt-0.5">{t.settings.runAutoResumeEnabledHint}</p>
          </div>
          <Toggle
            enabled={form.runAutoResumeEnabled}
            onChange={(v) => setForm(prev => ({ ...prev, runAutoResumeEnabled: v }))}
            ariaLabel={t.settings.runAutoResumeEnabled}
          />
        </div>

        {/* Editor AI proposal for dashboard-report blocks */}
        <div className="flex items-center justify-between py-2">
          <div>
            <label className="block text-sm font-medium text-text">{t.settings.dashboardReportAssist}</label>
            <p className="text-xs text-text-tertiary mt-0.5">{t.settings.dashboardReportAssistHint}</p>
          </div>
          <Toggle
            enabled={form.dashboardReportAssistEnabled}
            onChange={(v) => setForm(prev => ({ ...prev, dashboardReportAssistEnabled: v }))}
          />
        </div>

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

type CliLimitKey = Extract<NumericFormKeys, `${string}MaxConcurrentCalls` | `${string}ReservedForInteractive`>;

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
function CliLimitsTable({ form, onChange }: { form: FormState; onChange: (key: CliLimitKey, value: number) => void }) {
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
