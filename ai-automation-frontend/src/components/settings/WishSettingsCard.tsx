import { useState, useEffect } from 'react';
import { Card, CardHeader, Button } from '../ui';
import { useT } from '../../i18n';
import { useExecutionConfig, useUpdateExecutionConfig } from '../../hooks/useSystem';
import { Save, Loader2 } from 'lucide-react';
import { Spinner } from '../ui';

interface FormState {
  wishTotalTimeoutMinutes: number;
  wishWorkflowWaitMinutes: number;
  wishDefaultShellTimeoutSeconds: number;
  wishDefaultAgenticTotalTimeoutSeconds: number;
}

interface FieldConfig {
  key: string;
  label: string;
  hint: string;
  min: number;
  max: number;
  field: keyof FormState;
}

export function WishSettingsCard() {
  const t = useT();
  const { data: config, isLoading } = useExecutionConfig();
  const updateConfig = useUpdateExecutionConfig();

  const [form, setForm] = useState<FormState>({
    wishTotalTimeoutMinutes: 120,
    wishWorkflowWaitMinutes: 30,
    wishDefaultShellTimeoutSeconds: 600,
    wishDefaultAgenticTotalTimeoutSeconds: 3600,
  });

  useEffect(() => {
    if (config) {
      setForm({
        wishTotalTimeoutMinutes: config.wishTotalTimeoutMinutes ?? 120,
        wishWorkflowWaitMinutes: config.wishWorkflowWaitMinutes ?? 30,
        wishDefaultShellTimeoutSeconds: config.wishDefaultShellTimeoutSeconds ?? 600,
        wishDefaultAgenticTotalTimeoutSeconds: config.wishDefaultAgenticTotalTimeoutSeconds ?? 3600,
      });
    }
  }, [config]);

  const hasChanges = config && (
    form.wishTotalTimeoutMinutes !== (config.wishTotalTimeoutMinutes ?? 120) ||
    form.wishWorkflowWaitMinutes !== (config.wishWorkflowWaitMinutes ?? 30) ||
    form.wishDefaultShellTimeoutSeconds !== (config.wishDefaultShellTimeoutSeconds ?? 600) ||
    form.wishDefaultAgenticTotalTimeoutSeconds !== (config.wishDefaultAgenticTotalTimeoutSeconds ?? 3600)
  );

  const fields: FieldConfig[] = [
    { key: 'total', label: t.settings.wishTotalTimeoutMinutes, hint: t.settings.wishTotalTimeoutMinutesHint, min: 5, max: 1440, field: 'wishTotalTimeoutMinutes' },
    { key: 'wait', label: t.settings.wishWorkflowWaitMinutes, hint: t.settings.wishWorkflowWaitMinutesHint, min: 1, max: 120, field: 'wishWorkflowWaitMinutes' },
    { key: 'shell', label: t.settings.wishDefaultShellTimeoutSeconds, hint: t.settings.wishDefaultShellTimeoutSecondsHint, min: 30, max: 3600, field: 'wishDefaultShellTimeoutSeconds' },
    { key: 'agentic', label: t.settings.wishDefaultAgenticTotalTimeoutSeconds, hint: t.settings.wishDefaultAgenticTotalTimeoutSecondsHint, min: 60, max: 86400, field: 'wishDefaultAgenticTotalTimeoutSeconds' },
  ];

  const handleSave = () => {
    updateConfig.mutate({
      wishTotalTimeoutMinutes: form.wishTotalTimeoutMinutes,
      wishWorkflowWaitMinutes: form.wishWorkflowWaitMinutes,
      wishDefaultShellTimeoutSeconds: form.wishDefaultShellTimeoutSeconds,
      wishDefaultAgenticTotalTimeoutSeconds: form.wishDefaultAgenticTotalTimeoutSeconds,
    });
  };

  if (isLoading) {
    return (
      <Card>
        <CardHeader title={t.settings.wishSettings} subtitle={t.settings.wishSettingsSubtitle} />
        <div className="flex justify-center py-8"><Spinner /></div>
      </Card>
    );
  }

  return (
    <Card>
      <CardHeader title={t.settings.wishSettings} subtitle={t.settings.wishSettingsSubtitle} />
      <div className="space-y-5">
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {fields.map(({ key, label, hint, min, max, field }) => (
            <div key={key}>
              <label className="block text-sm font-medium text-text mb-1">{label}</label>
              <input
                type="number"
                min={min}
                max={max}
                value={form[field]}
                onChange={(e) => {
                  const val = parseInt(e.target.value, 10);
                  if (!isNaN(val)) {
                    setForm(prev => ({ ...prev, [field]: val }));
                  }
                }}
                className="w-full px-3 py-1.5 text-sm bg-surface-hover border border-border rounded-lg text-text focus:outline-none focus:ring-1 focus:ring-accent font-mono"
              />
              <p className="text-xs text-text-tertiary mt-1">{hint}</p>
            </div>
          ))}
        </div>

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
