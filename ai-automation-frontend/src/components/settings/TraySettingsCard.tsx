import { useEffect, useState } from 'react';
import { ExternalLink, PawPrint } from 'lucide-react';
import { useT } from '../../i18n';
import { useTraySettings, useUpdateTraySettings, type TraySettings } from '../../hooks/useTraySettings';
import { Button, Card, CardHeader, Input, Spinner, Toggle } from '../ui';

const DEFAULTS: TraySettings = {
  popupsEnabled: true,
  notifyApprovals: true,
  notifyFailures: true,
  notifySuccess: false,
  publicUrl: '',
};

export function TraySettingsCard() {
  const t = useT();
  const { data, isLoading } = useTraySettings();
  const updateSettings = useUpdateTraySettings();
  const [form, setForm] = useState<TraySettings>(DEFAULTS);

  useEffect(() => {
    if (data) setForm(data);
  }, [data]);

  const hasChanges = !!data && JSON.stringify(form) !== JSON.stringify(data);
  const currentOrigin = typeof window === 'undefined' ? '' : window.location.origin;

  if (isLoading) {
    return (
      <Card>
        <CardHeader title={t.settings.trayTitle} subtitle={t.settings.traySubtitle} />
        <div className="flex justify-center py-6"><Spinner /></div>
      </Card>
    );
  }

  return (
    <Card>
      <CardHeader title={t.settings.trayTitle} subtitle={t.settings.traySubtitle} />
      <div className="space-y-5">
        <div className="flex items-center gap-4 rounded-xl border border-border bg-surface-raised/40 p-4">
          <div className="relative flex h-14 w-14 shrink-0 items-center justify-center rounded-xl bg-[#211a14]">
            <img src="/icons/tray-meerkat.svg" alt="" className="h-12 w-12" />
            <span className="absolute bottom-1 right-1 h-3.5 w-3.5 rounded-full border-2 border-[#211a14] bg-emerald-400" />
          </div>
          <div>
            <div className="flex items-center gap-2 text-sm font-medium text-text">
              <PawPrint className="h-4 w-4 text-accent" />
              {t.settings.trayIconStyle}
            </div>
            <p className="mt-1 text-xs text-text-tertiary">{t.settings.trayIconStyleHint}</p>
          </div>
        </div>

        <SettingToggle
          label={t.settings.trayPopups}
          hint={t.settings.trayPopupsHint}
          enabled={form.popupsEnabled}
          onChange={(popupsEnabled) => setForm((value) => ({ ...value, popupsEnabled }))}
        />

        <div className={`space-y-3 border-l-2 border-border pl-4 ${form.popupsEnabled ? '' : 'opacity-50'}`}>
          <SettingToggle
            label={t.settings.trayNotifyApprovals}
            enabled={form.notifyApprovals}
            disabled={!form.popupsEnabled}
            onChange={(notifyApprovals) => setForm((value) => ({ ...value, notifyApprovals }))}
          />
          <SettingToggle
            label={t.settings.trayNotifyFailures}
            enabled={form.notifyFailures}
            disabled={!form.popupsEnabled}
            onChange={(notifyFailures) => setForm((value) => ({ ...value, notifyFailures }))}
          />
          <SettingToggle
            label={t.settings.trayNotifySuccess}
            hint={t.settings.trayNotifySuccessHint}
            enabled={form.notifySuccess}
            disabled={!form.popupsEnabled}
            onChange={(notifySuccess) => setForm((value) => ({ ...value, notifySuccess }))}
          />
        </div>

        <div className="border-t border-border pt-4">
          <label className="block text-sm font-medium text-text">{t.settings.trayPublicUrl}</label>
          <p className="mb-2 mt-0.5 text-xs text-text-tertiary">{t.settings.trayPublicUrlHint}</p>
          <div className="flex flex-col gap-2 sm:flex-row">
            <div className="min-w-0 flex-1">
              <Input
                value={form.publicUrl}
                onChange={(event) => setForm((value) => ({ ...value, publicUrl: event.target.value }))}
                placeholder="https://mysuricates.example.com"
              />
            </div>
            <Button
              size="sm"
              variant="secondary"
              icon={<ExternalLink className="h-4 w-4" />}
              onClick={() => setForm((value) => ({ ...value, publicUrl: currentOrigin }))}
            >
              {t.settings.trayUseCurrentUrl}
            </Button>
          </div>
        </div>

        <div className="flex justify-end">
          <Button
            size="sm"
            disabled={!hasChanges || updateSettings.isPending}
            loading={updateSettings.isPending}
            onClick={() => updateSettings.mutate({ ...form, publicUrl: form.publicUrl.trim() })}
          >
            {t.common.save}
          </Button>
        </div>
      </div>
    </Card>
  );
}

function SettingToggle({
  label,
  hint,
  enabled,
  disabled,
  onChange,
}: {
  label: string;
  hint?: string;
  enabled: boolean;
  disabled?: boolean;
  onChange: (enabled: boolean) => void;
}) {
  return (
    <div className="flex items-center justify-between gap-4">
      <div>
        <p className="text-sm font-medium text-text">{label}</p>
        {hint && <p className="mt-0.5 text-xs text-text-tertiary">{hint}</p>}
      </div>
      <Toggle enabled={enabled} disabled={disabled} ariaLabel={label} onChange={onChange} />
    </div>
  );
}
