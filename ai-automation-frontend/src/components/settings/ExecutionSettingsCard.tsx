import { useState, useEffect } from 'react';
import { Card, CardHeader, Button, Toggle } from '../ui';
import { useT } from '../../i18n';
import { useExecutionConfig, useUpdateExecutionConfig } from '../../hooks/useSystem';
import { Zap, Save, Loader2 } from 'lucide-react';
import { Spinner } from '../ui';

type NumericFormKeys = Exclude<keyof FormState, 'rateLimitEnabled' | 'schedulerCatchUpOnResume'>;

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
  claudeTimeoutSeconds: number;
  claudeInterCallDelayMs: number;
  chainWorkflowTimeoutMinutes: number;
  rateLimitEnabled: boolean;
  rateLimitRequestsPerMinute: number;
  assistantChatTimeoutSeconds: number;
  schedulerCatchUpOnResume: boolean;
  schedulerResumeGraceMinutes: number;
  summarizationTimeoutSeconds: number;
  factoryTimeoutSeconds: number;
  runRetentionDays: number;
  runRetentionMaxPerNight: number;
}

export function ExecutionSettingsCard() {
  const t = useT();
  const { data: config, isLoading } = useExecutionConfig();
  const updateConfig = useUpdateExecutionConfig();

  const [form, setForm] = useState<FormState>({
    executionMaxConcurrent: 10,
    claudeMaxConcurrentCalls: 5,
    claudeReservedForInteractive: 1,
    claudeTimeoutSeconds: 300,
    claudeInterCallDelayMs: 0,
    chainWorkflowTimeoutMinutes: 180,
    rateLimitEnabled: true,
    rateLimitRequestsPerMinute: 120,
    assistantChatTimeoutSeconds: 240,
    schedulerCatchUpOnResume: true,
    schedulerResumeGraceMinutes: 5,
    summarizationTimeoutSeconds: 120,
    factoryTimeoutSeconds: 600,
    runRetentionDays: 90,
    runRetentionMaxPerNight: 200,
  });

  useEffect(() => {
    if (config) {
      setForm({
        executionMaxConcurrent: config.executionMaxConcurrent,
        claudeMaxConcurrentCalls: config.claudeMaxConcurrentCalls,
        claudeReservedForInteractive: config.claudeReservedForInteractive ?? 1,
        claudeTimeoutSeconds: config.claudeTimeoutSeconds,
        claudeInterCallDelayMs: config.claudeInterCallDelayMs,
        chainWorkflowTimeoutMinutes: config.chainWorkflowTimeoutMinutes,
        rateLimitEnabled: config.rateLimitEnabled === 1,
        rateLimitRequestsPerMinute: config.rateLimitRequestsPerMinute,
        assistantChatTimeoutSeconds: config.assistantChatTimeoutSeconds ?? 240,
        schedulerCatchUpOnResume: (config.schedulerCatchUpOnResume ?? 1) === 1,
        schedulerResumeGraceMinutes: config.schedulerResumeGraceMinutes ?? 5,
        summarizationTimeoutSeconds: config.summarizationTimeoutSeconds ?? 120,
        factoryTimeoutSeconds: config.factoryTimeoutSeconds ?? 600,
        runRetentionDays: config.runRetentionDays ?? 90,
        runRetentionMaxPerNight: config.runRetentionMaxPerNight ?? 200,
      });
    }
  }, [config]);

  const hasChanges = config && (
    form.executionMaxConcurrent !== config.executionMaxConcurrent ||
    form.claudeMaxConcurrentCalls !== config.claudeMaxConcurrentCalls ||
    form.claudeReservedForInteractive !== (config.claudeReservedForInteractive ?? 1) ||
    form.claudeTimeoutSeconds !== config.claudeTimeoutSeconds ||
    form.claudeInterCallDelayMs !== config.claudeInterCallDelayMs ||
    form.chainWorkflowTimeoutMinutes !== config.chainWorkflowTimeoutMinutes ||
    form.rateLimitEnabled !== (config.rateLimitEnabled === 1) ||
    form.rateLimitRequestsPerMinute !== config.rateLimitRequestsPerMinute ||
    form.assistantChatTimeoutSeconds !== (config.assistantChatTimeoutSeconds ?? 240) ||
    form.schedulerCatchUpOnResume !== ((config.schedulerCatchUpOnResume ?? 1) === 1) ||
    form.schedulerResumeGraceMinutes !== (config.schedulerResumeGraceMinutes ?? 5) ||
    form.summarizationTimeoutSeconds !== (config.summarizationTimeoutSeconds ?? 120) ||
    form.factoryTimeoutSeconds !== (config.factoryTimeoutSeconds ?? 600) ||
    form.runRetentionDays !== (config.runRetentionDays ?? 90) ||
    form.runRetentionMaxPerNight !== (config.runRetentionMaxPerNight ?? 200)
  );

  const fields: FieldConfig[] = [
    { key: 'maxRuns', label: t.settings.executionMaxConcurrentRuns, hint: t.settings.executionMaxConcurrentRunsHint, min: 1, max: 50, configKey: 'executionMaxConcurrent' },
    { key: 'maxCalls', label: t.settings.executionMaxConcurrentCalls, hint: t.settings.executionMaxConcurrentCallsHint, min: 1, max: 20, configKey: 'claudeMaxConcurrentCalls' },
    { key: 'reserved', label: t.settings.executionReservedForInteractive, hint: t.settings.executionReservedForInteractiveHint, min: 0, max: 10, configKey: 'claudeReservedForInteractive' },
    { key: 'timeout', label: t.settings.executionCliTimeout, hint: t.settings.executionCliTimeoutHint, min: 30, max: 3600, configKey: 'claudeTimeoutSeconds' },
    { key: 'delay', label: t.settings.executionInterCallDelay, hint: t.settings.executionInterCallDelayHint, min: 0, max: 30000, configKey: 'claudeInterCallDelayMs' },
    { key: 'chain', label: t.settings.executionChainTimeout, hint: t.settings.executionChainTimeoutHint, min: 5, max: 1440, configKey: 'chainWorkflowTimeoutMinutes' },
    { key: 'rateRpm', label: t.settings.executionRateLimitRpm, hint: t.settings.executionRateLimitRpmHint, min: 10, max: 10000, configKey: 'rateLimitRequestsPerMinute' },
    { key: 'assistantTimeout', label: t.settings.executionAssistantChatTimeout, hint: t.settings.executionAssistantChatTimeoutHint, min: 30, max: 900, configKey: 'assistantChatTimeoutSeconds' },
    { key: 'resumeGrace', label: t.settings.schedulerResumeGraceMinutes, hint: t.settings.schedulerResumeGraceMinutesHint, min: 1, max: 60, configKey: 'schedulerResumeGraceMinutes' },
    { key: 'summarizationTimeout', label: t.settings.summarizationTimeoutSeconds, hint: t.settings.summarizationTimeoutSecondsHint, min: 30, max: 600, configKey: 'summarizationTimeoutSeconds' },
    { key: 'factoryTimeout', label: t.settings.factoryTimeoutSeconds, hint: t.settings.factoryTimeoutSecondsHint, min: 60, max: 3600, configKey: 'factoryTimeoutSeconds' },
    { key: 'runRetentionDays', label: t.settings.runRetentionDays, hint: t.settings.runRetentionDaysHint, min: 0, max: 730, configKey: 'runRetentionDays' },
    { key: 'runRetentionMaxPerNight', label: t.settings.runRetentionMaxPerNight, hint: t.settings.runRetentionMaxPerNightHint, min: 10, max: 5000, configKey: 'runRetentionMaxPerNight' },
  ];

  const handleSave = () => {
    updateConfig.mutate({
      executionMaxConcurrent: form.executionMaxConcurrent,
      claudeMaxConcurrentCalls: form.claudeMaxConcurrentCalls,
      claudeReservedForInteractive: form.claudeReservedForInteractive,
      claudeTimeoutSeconds: form.claudeTimeoutSeconds,
      claudeInterCallDelayMs: form.claudeInterCallDelayMs,
      chainWorkflowTimeoutMinutes: form.chainWorkflowTimeoutMinutes,
      rateLimitEnabled: form.rateLimitEnabled ? 1 : 0,
      rateLimitRequestsPerMinute: form.rateLimitRequestsPerMinute,
      assistantChatTimeoutSeconds: form.assistantChatTimeoutSeconds,
      schedulerCatchUpOnResume: form.schedulerCatchUpOnResume ? 1 : 0,
      schedulerResumeGraceMinutes: form.schedulerResumeGraceMinutes,
      summarizationTimeoutSeconds: form.summarizationTimeoutSeconds,
      factoryTimeoutSeconds: form.factoryTimeoutSeconds,
      runRetentionDays: form.runRetentionDays,
      runRetentionMaxPerNight: form.runRetentionMaxPerNight,
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
                className={`h-full rounded-full transition-all ${usagePercent > 80 ? 'bg-red-500' : usagePercent > 50 ? 'bg-yellow-500' : 'bg-accent'}`}
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
