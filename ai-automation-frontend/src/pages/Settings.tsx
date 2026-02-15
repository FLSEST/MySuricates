import { Card, CardHeader, Button, Input, Toggle, Select } from '../components/ui';
import { StatusBadge } from '../components/shared/StatusBadge';
import { useThemeStore } from '../stores/themeStore';
import { useSettingsStore } from '../stores/settingsStore';
import { useI18nStore, type Locale } from '../i18n';
import { useT } from '../i18n';
import { useSystemHealth, useSystemInfo, useRemoteAccessStatus, useEnableRemoteAccess, useDisableRemoteAccess } from '../hooks/useSystem';
import { Sun, Moon, Globe, Shield, Paintbrush, Wifi, Server, RotateCcw } from 'lucide-react';
import { lightTheme, darkTheme, type ThemeTokens } from '../theme/tokens';

const COLOR_KEYS: Array<{ key: keyof ThemeTokens; label: string }> = [
  { key: '--color-accent', label: 'Accent' },
  { key: '--color-accent-hover', label: 'Accent Hover' },
  { key: '--color-success', label: 'Success' },
  { key: '--color-warning', label: 'Warning' },
  { key: '--color-danger', label: 'Danger' },
  { key: '--color-surface', label: 'Surface' },
  { key: '--color-surface-raised', label: 'Surface Raised' },
  { key: '--color-border', label: 'Border' },
  { key: '--color-text', label: 'Text' },
  { key: '--color-text-secondary', label: 'Text Secondary' },
];

export function Settings() {
  const t = useT();
  const { mode, setMode, overrides, setOverride, resetOverrides } = useThemeStore();
  const { apiKey, setApiKey, advancedMode, setAdvancedMode } = useSettingsStore();
  const { locale, setLocale } = useI18nStore();

  const { data: health } = useSystemHealth();
  const { data: sysInfo } = useSystemInfo();
  const { data: remoteAccess } = useRemoteAccessStatus();
  const enableRemote = useEnableRemoteAccess();
  const disableRemote = useDisableRemoteAccess();

  return (
    <div className="space-y-6 animate-fade-in max-w-3xl">
      {/* Header */}
      <div>
        <h1 className="text-2xl font-bold text-text">{t.settings.title}</h1>
        <p className="text-sm text-text-secondary mt-1">{t.settings.subtitle}</p>
      </div>

      {/* General */}
      <Card>
        <CardHeader title={t.settings.general} />
        <div className="space-y-4">
          {/* Language */}
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <Globe className="w-5 h-5 text-text-secondary" />
              <div>
                <p className="text-sm font-medium text-text">{t.settings.language}</p>
              </div>
            </div>
            <Select
              value={locale}
              onChange={(e) => setLocale(e.target.value as Locale)}
              options={[
                { value: 'en', label: 'English' },
                { value: 'de', label: 'Deutsch' },
              ]}
            />
          </div>

          {/* Advanced mode */}
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm font-medium text-text">{t.settings.advanced}</p>
              <p className="text-xs text-text-tertiary">{t.settings.advancedHint}</p>
            </div>
            <Toggle enabled={advancedMode} onChange={setAdvancedMode} />
          </div>
        </div>
      </Card>

      {/* Appearance */}
      <Card>
        <CardHeader title={t.settings.appearance} />
        <div className="space-y-5">
          {/* Theme toggle */}
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              {mode === 'dark' ? (
                <Moon className="w-5 h-5 text-text-secondary" />
              ) : (
                <Sun className="w-5 h-5 text-text-secondary" />
              )}
              <div>
                <p className="text-sm font-medium text-text">{t.settings.theme}</p>
              </div>
            </div>
            <div className="flex items-center rounded-lg border border-border overflow-hidden">
              <button
                onClick={() => setMode('light')}
                className={`px-3 py-1.5 text-xs font-medium transition-colors ${
                  mode === 'light' ? 'bg-accent text-text-inverse' : 'text-text-secondary hover:text-text'
                }`}
              >
                {t.settings.themeLight}
              </button>
              <button
                onClick={() => setMode('dark')}
                className={`px-3 py-1.5 text-xs font-medium transition-colors ${
                  mode === 'dark' ? 'bg-accent text-text-inverse' : 'text-text-secondary hover:text-text'
                }`}
              >
                {t.settings.themeDark}
              </button>
            </div>
          </div>

          {/* Custom colors */}
          <div>
            <div className="flex items-center justify-between mb-3">
              <div className="flex items-center gap-3">
                <Paintbrush className="w-5 h-5 text-text-secondary" />
                <p className="text-sm font-medium text-text">{t.settings.colors}</p>
              </div>
              {Object.keys(overrides).length > 0 && (
                <Button
                  variant="ghost"
                  size="sm"
                  icon={<RotateCcw className="w-3.5 h-3.5" />}
                  onClick={resetOverrides}
                >
                  {t.common.reset}
                </Button>
              )}
            </div>
            <div className="grid grid-cols-2 gap-3">
              {COLOR_KEYS.map(({ key, label }) => (
                <div key={key} className="flex items-center gap-2">
                  <input
                    type="color"
                    value={overrides[key] ?? (mode === 'light' ? lightTheme[key] : darkTheme[key])}
                    onChange={(e) => setOverride(key, e.target.value)}
                    className="w-8 h-8 rounded-lg border border-border cursor-pointer bg-transparent"
                  />
                  <span className="text-xs text-text-secondary">{label}</span>
                </div>
              ))}
            </div>
          </div>
        </div>
      </Card>

      {/* Security */}
      <Card>
        <CardHeader title={t.settings.security} />
        <div className="space-y-4">
          <div className="flex items-center gap-3">
            <Shield className="w-5 h-5 text-text-secondary" />
            <div className="flex-1">
              <Input
                label={t.settings.apiKey}
                hint={t.settings.apiKeyHint}
                type="password"
                value={apiKey}
                onChange={(e) => setApiKey(e.target.value)}
                placeholder="sk-..."
              />
            </div>
          </div>
        </div>
      </Card>

      {/* Remote Access */}
      <Card>
        <CardHeader title={t.settings.remoteAccess} />
        <div className="space-y-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <Wifi className="w-5 h-5 text-text-secondary" />
              <div>
                <p className="text-sm font-medium text-text">{t.settings.tailscale}</p>
                <p className="text-xs text-text-tertiary">
                  {remoteAccess?.enabled ? `IP: ${remoteAccess.ip}` : t.settings.tailscaleNotConnected}
                </p>
              </div>
            </div>
            <div className="flex items-center gap-3">
              <StatusBadge status={remoteAccess?.enabled ? 'RUNNING' : 'STOPPED'} />
              {remoteAccess?.enabled ? (
                <Button
                  variant="danger"
                  size="sm"
                  onClick={() => disableRemote.mutate()}
                  loading={disableRemote.isPending}
                >
                  {t.settings.tailscaleDisable}
                </Button>
              ) : (
                <Button
                  size="sm"
                  onClick={() => enableRemote.mutate()}
                  loading={enableRemote.isPending}
                >
                  {t.settings.tailscaleEnable}
                </Button>
              )}
            </div>
          </div>
          {remoteAccess?.hostname && (
            <p className="text-xs text-text-tertiary font-mono pl-8">
              Hostname: {remoteAccess.hostname}
            </p>
          )}
        </div>
      </Card>

      {/* System Info */}
      <Card>
        <CardHeader title={t.settings.system} />
        <div className="space-y-3">
          <div className="flex items-center gap-3 mb-4">
            <Server className="w-5 h-5 text-text-secondary" />
            {health && <StatusBadge status={health.status === 'UP' ? 'RUNNING' : 'ERROR'} label={health.status} />}
          </div>
          {sysInfo && (
            <div className="space-y-2 text-sm">
              {Object.entries(sysInfo).map(([key, value]) => (
                <div key={key} className="flex justify-between">
                  <span className="text-text-secondary capitalize">{key}</span>
                  <span className="text-text font-mono">{value}</span>
                </div>
              ))}
            </div>
          )}
          {health && (
            <div className="space-y-2 text-sm pt-3 border-t border-border mt-3">
              <div className="flex justify-between">
                <span className="text-text-secondary">Docker</span>
                <StatusBadge status={health.docker === 'CONNECTED' ? 'RUNNING' : 'ERROR'} label={health.docker} />
              </div>
              <div className="flex justify-between">
                <span className="text-text-secondary">Last Check</span>
                <span className="text-text-tertiary text-xs">{new Date(health.timestamp).toLocaleTimeString()}</span>
              </div>
            </div>
          )}
        </div>
      </Card>
    </div>
  );
}
