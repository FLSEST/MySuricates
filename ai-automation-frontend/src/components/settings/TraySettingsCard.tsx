import { useState } from 'react';
import { ExternalLink, PawPrint } from 'lucide-react';
import { useT } from '../../i18n';
import { useTraySettings, useUpdateTraySettings, type TraySettings } from '../../hooks/useTraySettings';
import { Button, Card, CardHeader, Input, Spinner, Toggle } from '../ui';

const DEFAULTS: TraySettings = {
  popupsEnabled: false,
  notifyApprovals: true,
  notifyFailures: true,
  notifySuccess: false,
  publicUrl: '',
};

export function TraySettingsCard() {
  const t = useT();
  const { data, isLoading } = useTraySettings();
  const updateSettings = useUpdateTraySettings();
  const settings = data ?? DEFAULTS;
  // The field shows the stored URL until the user edits it; the draft only lives while dirty
  const [urlDraft, setUrlDraft] = useState('');
  const [urlDirty, setUrlDirty] = useState(false);
  const publicUrl = urlDirty ? urlDraft : (data?.publicUrl ?? '');

  const hasUrlChanges = !!data && publicUrl.trim() !== data.publicUrl;
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
            <span className="absolute bottom-1 left-1/2 flex -translate-x-1/2 gap-0.5 rounded-full bg-[#211a14] p-0.5">
              {/* The real tray icon's fixed colours (TrayIconRenderer), independent of the app theme */}
              <span className="h-2.5 w-2.5 rounded-full bg-[#36c98f]" />
              <span className="h-2.5 w-2.5 rounded-full bg-[#f3b63f]" />
              <span className="h-2.5 w-2.5 rounded-full bg-[#ef5a5a]" />
            </span>
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
          enabled={settings.popupsEnabled}
          disabled={updateSettings.isPending}
          onChange={(popupsEnabled) => updateSettings.mutate({ popupsEnabled })}
        />

        <div className={`space-y-3 border-l-2 border-border pl-4 ${settings.popupsEnabled ? '' : 'opacity-50'}`}>
          <SettingToggle
            label={t.settings.trayNotifyApprovals}
            enabled={settings.notifyApprovals}
            disabled={!settings.popupsEnabled || updateSettings.isPending}
            onChange={(notifyApprovals) => updateSettings.mutate({ notifyApprovals })}
          />
          <SettingToggle
            label={t.settings.trayNotifyFailures}
            enabled={settings.notifyFailures}
            disabled={!settings.popupsEnabled || updateSettings.isPending}
            onChange={(notifyFailures) => updateSettings.mutate({ notifyFailures })}
          />
          <SettingToggle
            label={t.settings.trayNotifySuccess}
            hint={t.settings.trayNotifySuccessHint}
            enabled={settings.notifySuccess}
            disabled={!settings.popupsEnabled || updateSettings.isPending}
            onChange={(notifySuccess) => updateSettings.mutate({ notifySuccess })}
          />
        </div>

        <div className="border-t border-border pt-4">
          <label className="block text-sm font-medium text-text">{t.settings.trayPublicUrl}</label>
          <p className="mb-2 mt-0.5 text-xs text-text-tertiary">{t.settings.trayPublicUrlHint}</p>
          <div className="flex flex-col gap-2 sm:flex-row">
            <div className="min-w-0 flex-1">
              <Input
                value={publicUrl}
                onChange={(event) => {
                  setUrlDraft(event.target.value);
                  setUrlDirty(true);
                }}
                placeholder="https://mysuricates.example.com"
              />
            </div>
            <Button
              size="sm"
              variant="secondary"
              icon={<ExternalLink className="h-4 w-4" />}
              onClick={() => {
                setUrlDraft(currentOrigin);
                setUrlDirty(true);
              }}
            >
              {t.settings.trayUseCurrentUrl}
            </Button>
          </div>
        </div>

        <div className="flex justify-end">
          <Button
            size="sm"
            disabled={!hasUrlChanges || updateSettings.isPending}
            loading={updateSettings.isPending}
            onClick={() => updateSettings.mutate(
              { publicUrl: publicUrl.trim() },
              { onSuccess: () => setUrlDirty(false) },
            )}
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
