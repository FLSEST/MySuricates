import { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Card, CardHeader, Button, Input, Toggle, Select } from '../components/ui';
import { downloadFromResponse } from '../utils/downloadFile';
import { StatusBadge } from '../components/shared/StatusBadge';
import { useThemeStore } from '../stores/themeStore';
import { useSettingsStore } from '../stores/settingsStore';
import { useI18nStore, type Locale } from '../i18n';
import { useT } from '../i18n';
import { useSystemHealth, useSystemInfo, useRemoteAccessStatus, useEnableRemoteAccess, useDisableRemoteAccess, useBackupList, useUpdateSystemSetting } from '../hooks/useSystem';
import { useStagingStatus, useStagingStart, useStagingStop, useStagingPromote, useStagingCancel, useStagingSync, useStagingSyncPreview } from '../hooks/useStaging';
import { useTestingStatus, useTestingStart, useTestingStop, useTestingReset, useTestingSync, useTestingPullChanges, useTestingSyncPreview } from '../hooks/useTesting';
import { SyncSelectModal } from '../components/shared/SyncSelectModal';
import { Sun, Moon, Globe, Shield, Paintbrush, Wifi, Server, RotateCcw, Database, Download, HardDrive, Container, Play, Square, Upload, ExternalLink, X, RefreshCw, FlaskConical, Trash2, ArrowDownToLine, ListChecks } from 'lucide-react';
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
  const { apiKey, setApiKey, advancedMode, setAdvancedMode, autoTodoDefault, setAutoTodoDefault } = useSettingsStore();
  const updateSetting = useUpdateSystemSetting();
  const { locale, setLocale } = useI18nStore();
  const [backupLoading, setBackupLoading] = useState(false);

  const { data: health } = useSystemHealth();
  const { data: sysInfo } = useSystemInfo();
  const { data: remoteAccess } = useRemoteAccessStatus();
  const { data: backups } = useBackupList();
  const enableRemote = useEnableRemoteAccess();
  const disableRemote = useDisableRemoteAccess();
  const { data: staging } = useStagingStatus();
  const stagingStart = useStagingStart();
  const stagingStop = useStagingStop();
  const stagingPromote = useStagingPromote();
  const stagingCancel = useStagingCancel();
  const stagingSync = useStagingSync();
  const { data: testing } = useTestingStatus();
  const testingStart = useTestingStart();
  const testingStop = useTestingStop();
  const testingReset = useTestingReset();
  const testingSync = useTestingSync();
  const testingPullChanges = useTestingPullChanges();
  const queryClient = useQueryClient();

  const handleBackup = async () => {
    setBackupLoading(true);
    try {
      const headers: HeadersInit = {};
      const storedKey = useSettingsStore.getState().apiKey;
      if (storedKey) headers['X-API-Key'] = storedKey;
      const res = await fetch('/api/v1/system/backup', { headers });
      if (!res.ok) throw new Error('Backup failed');
      await downloadFromResponse(res, 'autopilot-backup.db');
      queryClient.invalidateQueries({ queryKey: ['system', 'backups'] });
    } catch {
      // silently fail — user sees no download
    } finally {
      setBackupLoading(false);
    }
  };

  const handleDownloadExisting = async (filename: string) => {
    const headers: HeadersInit = {};
    const storedKey = useSettingsStore.getState().apiKey;
    if (storedKey) headers['X-API-Key'] = storedKey;
    try {
      const res = await fetch(`/api/v1/system/backups/${encodeURIComponent(filename)}`, { headers });
      if (!res.ok) return;
      await downloadFromResponse(res, filename);
    } catch {
      // silently fail
    }
  };

  const formatBytes = (bytes: number) => {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  };

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

          {/* Auto-Todo Default */}
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm font-medium text-text">{t.settings.autoTodoDefault}</p>
              <p className="text-xs text-text-tertiary">{t.settings.autoTodoDefaultHint}</p>
            </div>
            <Toggle
              enabled={autoTodoDefault}
              onChange={(enabled) => {
                setAutoTodoDefault(enabled);
                updateSetting.mutate({ key: 'auto-todo-default', value: String(enabled) });
              }}
            />
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

      {/* Database Backup */}
      <Card>
        <CardHeader title={t.settings.backup} />
        <div className="space-y-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <Database className="w-5 h-5 text-text-secondary" />
              <div>
                <p className="text-sm font-medium text-text">{t.settings.backup}</p>
                <p className="text-xs text-text-tertiary">{t.settings.backupDescription}</p>
              </div>
            </div>
            <Button
              size="sm"
              icon={<Download className="w-4 h-4" />}
              onClick={handleBackup}
              loading={backupLoading}
            >
              {t.settings.backupButton}
            </Button>
          </div>

          {/* Backup status */}
          <div className="pl-8 space-y-2">
            <div className="flex items-center gap-2 text-xs">
              <HardDrive className="w-3.5 h-3.5 text-text-tertiary" />
              <span className="text-text-secondary">
                {backups && backups.length > 0
                  ? t.settings.backupCount.replace('{count}', String(backups.length))
                  : t.settings.backupNone}
              </span>
            </div>
            {backups != null && backups.length > 0 && (
              <div className="flex items-center gap-2 text-xs">
                <span className="text-text-tertiary">{t.settings.backupLastBackup}:</span>
                <span className="text-text font-mono">
                  {new Date(backups[0]!.createdAt).toLocaleString()}
                </span>
                <span className="text-text-tertiary">({formatBytes(backups[0]!.sizeBytes)})</span>
              </div>
            )}
          </div>

          {/* Existing backups list */}
          {backups && backups.length > 0 && (
            <div className="pl-8">
              <div className="space-y-1.5">
                {backups.map((b) => (
                  <div key={b.filename} className="flex items-center justify-between text-xs py-1 px-2 rounded-sm hover:bg-surface-raised transition-colors">
                    <div className="flex items-center gap-2 min-w-0">
                      <span className="text-text font-mono truncate">{b.filename}</span>
                      <span className="text-text-tertiary shrink-0">{formatBytes(b.sizeBytes)}</span>
                    </div>
                    <button
                      onClick={() => handleDownloadExisting(b.filename)}
                      className="text-accent hover:text-accent-hover shrink-0 ml-2"
                      title={t.settings.backupButton}
                    >
                      <Download className="w-3.5 h-3.5" />
                    </button>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      </Card>

      {/* Staging (Advanced Mode only) */}
      {advancedMode && (
        <StagingCard
          t={t}
          staging={staging}
          devVersion={sysInfo?.version}
          onStart={() => stagingStart.mutate()}
          onStop={() => stagingStop.mutate()}
          onPromote={() => stagingPromote.mutate()}
          onCancel={() => stagingCancel.mutate()}
          onSyncAll={() => stagingSync.mutateAsync(undefined)}
          onSyncSelective={(req) => stagingSync.mutateAsync(req)}
          syncResult={stagingSync.data}
          startLoading={stagingStart.isPending}
          stopLoading={stagingStop.isPending}
          promoteLoading={stagingPromote.isPending}
          syncLoading={stagingSync.isPending}
        />
      )}

      {/* Testing (Advanced Mode only) */}
      {advancedMode && (
        <TestingCard
          t={t}
          testing={testing}
          devVersion={sysInfo?.version}
          onStart={() => testingStart.mutate()}
          onStop={() => testingStop.mutate()}
          onReset={() => testingReset.mutateAsync()}
          onSyncAll={() => testingSync.mutateAsync(undefined)}
          onSyncSelective={(req) => testingSync.mutateAsync(req)}
          onPullChanges={() => testingPullChanges.mutateAsync()}
          syncResult={testingSync.data}
          pullResult={testingPullChanges.data}
          startLoading={testingStart.isPending}
          stopLoading={testingStop.isPending}
          resetLoading={testingReset.isPending}
          syncLoading={testingSync.isPending}
          pullLoading={testingPullChanges.isPending}
        />
      )}

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

// ── Staging Card ─────────────────────────────────────────

const PROMOTE_PHASES = ['STOPPING', 'BUILDING_BACKEND', 'BUILDING_FRONTEND', 'BUILDING_IMAGE', 'STARTING'] as const;

type PhaseKey = `stagingPhase${typeof PROMOTE_PHASES[number]}`;

function StagingCard({ t, staging, devVersion, onStart, onStop, onPromote, onCancel, onSyncAll, onSyncSelective, syncResult, startLoading, stopLoading, promoteLoading, syncLoading }: {
  t: ReturnType<typeof useT>;
  staging: import('../types').StagingStatus | undefined;
  devVersion: string | undefined;
  onStart: () => void;
  onStop: () => void;
  onPromote: () => void;
  onCancel: () => void;
  onSyncAll: () => Promise<import('../types').SyncResult>;
  onSyncSelective: (request: import('../types').SyncDefinitionsRequest) => Promise<import('../types').SyncResult>;
  syncResult: import('../types').SyncResult | undefined;
  startLoading: boolean;
  stopLoading: boolean;
  promoteLoading: boolean;
  syncLoading: boolean;
}) {
  const [syncConfirm, setSyncConfirm] = useState(false);
  const [syncModalOpen, setSyncModalOpen] = useState(false);
  const { data: syncPreview, isLoading: previewLoading } = useStagingSyncPreview(syncModalOpen);

  const isPromoting = staging?.promotePhase != null && staging.promotePhase !== 'IDLE' && staging.promotePhase !== 'ERROR';
  const promoteStep = staging?.promoteStep ?? 0;
  const totalSteps = staging?.promoteTotalSteps ?? 5;
  const progressPct = isPromoting ? Math.round((promoteStep / totalSteps) * 100) : 0;

  const phaseLabel = (phase: string): string => {
    const key = `stagingPhase${phase}` as PhaseKey;
    return (t.settings as Record<string, string>)[key] ?? phase;
  };

  const handleSync = () => {
    if (!syncConfirm) {
      setSyncConfirm(true);
      return;
    }
    setSyncConfirm(false);
    onSyncAll();
  };

  return (
    <Card>
      <CardHeader title={t.settings.staging} />
      <SyncSelectModal
        open={syncModalOpen}
        onClose={() => setSyncModalOpen(false)}
        preview={syncPreview}
        previewLoading={previewLoading}
        onSync={onSyncSelective}
        syncLoading={syncLoading}
        title={t.settings.syncSelectTitle}
        t={t}
      />
      <div className="space-y-4">
        {/* Status row */}
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <Container className="w-5 h-5 text-text-secondary" />
            <div>
              <div className="flex items-center gap-2">
                <p className="text-sm font-medium text-text">Stable</p>
                <a href="http://localhost:9091" target="_blank" rel="noopener noreferrer" className="text-xs text-accent hover:text-accent-hover font-mono hover:underline">localhost:9091</a>
              </div>
              <p className="text-xs text-text-tertiary">{t.settings.stagingHint}</p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <StatusBadge
              status={staging?.containerRunning ? (staging.healthy ? 'RUNNING' : 'STARTING') : 'STOPPED'}
              label={staging?.containerRunning ? t.settings.stagingRunning : t.settings.stagingStopped}
            />
          </div>
        </div>

        {/* Version comparison */}
        {(devVersion || staging?.stableVersion) && (
          <div className="pl-8 flex items-center gap-3 text-xs">
            <span className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-md bg-accent/10 text-accent font-medium">
              {t.settings.stagingDevVersion} v{devVersion ?? t.settings.stagingVersionUnknown}
            </span>
            <span className="text-text-tertiary">vs</span>
            <span className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded-md font-medium ${
              staging?.stableVersion && devVersion && staging.stableVersion !== devVersion
                ? 'bg-warning/10 text-warning'
                : 'bg-surface-raised text-text-secondary'
            }`}>
              {t.settings.stagingStableVersion} v{staging?.stableVersion ?? t.settings.stagingVersionUnknown}
            </span>
            {staging?.stableVersion && devVersion && staging.stableVersion !== devVersion && (
              <span className="text-warning text-[10px]">{t.settings.stagingPromoteAvailable}</span>
            )}
          </div>
        )}

        {/* Last snapshot */}
        <div className="pl-8 text-xs text-text-tertiary">
          {t.settings.stagingLastSnapshot}: {' '}
          <span className="font-mono text-text-secondary">
            {staging?.lastSnapshot ?? t.settings.stagingNoSnapshot}
          </span>
        </div>

        {/* Action buttons row */}
        {!isPromoting && (
          <div className="flex items-center gap-2 pl-8">
            {staging?.containerRunning && (
              <Button
                size="sm"
                variant="ghost"
                icon={<ExternalLink className="w-3.5 h-3.5" />}
                onClick={() => window.open('http://localhost:9091', '_blank')}
              >
                {t.settings.stagingOpenUi}
              </Button>
            )}
            {staging?.containerRunning ? (
              <Button
                size="sm"
                variant="danger"
                icon={<Square className="w-3.5 h-3.5" />}
                onClick={onStop}
                loading={stopLoading}
              >
                {t.settings.stagingStop}
              </Button>
            ) : (
              <Button
                size="sm"
                icon={<Play className="w-3.5 h-3.5" />}
                onClick={onStart}
                loading={startLoading}
              >
                {t.settings.stagingStart}
              </Button>
            )}
          </div>
        )}

        {/* Divider */}
        <div className="border-t border-border" />

        {/* Promote section */}
        {isPromoting ? (
          <div className="space-y-3 pl-8">
            <div className="flex items-center justify-between">
              <p className="text-sm font-medium text-text">{t.settings.stagingPromoting}</p>
              <Button
                size="sm"
                variant="ghost"
                icon={<X className="w-3.5 h-3.5" />}
                onClick={onCancel}
              >
                {t.settings.stagingCancel}
              </Button>
            </div>
            {/* Progress bar */}
            <div className="space-y-1.5">
              <div className="w-full h-2 bg-surface-raised rounded-full overflow-hidden">
                <div
                  className="h-full bg-accent rounded-full transition-all duration-500"
                  style={{ width: `${progressPct}%` }}
                />
              </div>
              <div className="flex items-center justify-between text-xs text-text-tertiary">
                <span>{phaseLabel(staging?.promotePhase ?? '')}</span>
                <span>{t.settings.stagingStatus}: {promoteStep}/{totalSteps}</span>
              </div>
            </div>
          </div>
        ) : (
          <div className="space-y-3 pl-8">
            {/* Error display */}
            {staging?.promotePhase === 'ERROR' && staging.error && (
              <div className="text-xs text-danger bg-danger/10 rounded-lg p-2">
                {t.settings.stagingPromoteFailed}: {staging.error}
              </div>
            )}

            {/* Promote button */}
            <div>
              <Button
                size="sm"
                icon={<Upload className="w-3.5 h-3.5" />}
                onClick={onPromote}
                loading={promoteLoading}
              >
                {t.settings.stagingPromote}
              </Button>
              <p className="text-xs text-text-tertiary mt-1">{t.settings.stagingPromoteHint}</p>
            </div>

            {/* Sync definitions */}
            <div>
              {syncConfirm ? (
                <div className="flex items-center gap-2">
                  <span className="text-xs text-warning">{t.settings.stagingSyncConfirm}</span>
                  <Button size="sm" variant="danger" onClick={handleSync} loading={syncLoading}>
                    {t.common.confirm}
                  </Button>
                  <Button size="sm" variant="ghost" onClick={() => setSyncConfirm(false)}>
                    {t.common.cancel}
                  </Button>
                </div>
              ) : (
                <div>
                  <div className="flex items-center gap-2">
                    <Button
                      size="sm"
                      variant="ghost"
                      icon={<RefreshCw className="w-3.5 h-3.5" />}
                      onClick={handleSync}
                      loading={syncLoading}
                      disabled={!staging?.healthy}
                    >
                      {t.settings.syncAll}
                    </Button>
                    <Button
                      size="sm"
                      variant="ghost"
                      icon={<ListChecks className="w-3.5 h-3.5" />}
                      onClick={() => setSyncModalOpen(true)}
                      disabled={!staging?.healthy || syncLoading}
                    >
                      {t.settings.syncSelective}
                    </Button>
                  </div>
                  <p className="text-xs text-text-tertiary mt-1">{t.settings.stagingSyncHint}</p>
                </div>
              )}

              {/* Sync result display */}
              {syncResult && (
                <div className="mt-2 space-y-1">
                  <p className="text-xs text-success">
                    {t.settings.stagingSyncResult
                      .replace('{workflows}', String(syncResult.workflowsSynced))
                      .replace('{projects}', String(syncResult.projectsSynced))
                      .replace('{skills}', String(syncResult.skillsSynced))
                      .replace('{skipped}', String(syncResult.skipped))}
                  </p>
                  {syncResult.errors.length > 0 && (
                    <div className="text-xs text-danger">
                      <p>{t.settings.stagingSyncErrors.replace('{count}', String(syncResult.errors.length))}</p>
                      {syncResult.errors.map((err, i) => (
                        <p key={i} className="pl-2 text-text-tertiary">{err}</p>
                      ))}
                    </div>
                  )}
                </div>
              )}
            </div>
          </div>
        )}
      </div>
    </Card>
  );
}

// ── Testing Card ──────────────────────────────────────────

function TestingCard({ t, testing, devVersion, onStart, onStop, onReset, onSyncAll, onSyncSelective, onPullChanges, syncResult, pullResult, startLoading, stopLoading, resetLoading, syncLoading, pullLoading }: {
  t: ReturnType<typeof useT>;
  testing: import('../types').TestingStatus | undefined;
  devVersion: string | undefined;
  onStart: () => void;
  onStop: () => void;
  onReset: () => Promise<unknown>;
  onSyncAll: () => Promise<import('../types').SyncResult>;
  onSyncSelective: (request: import('../types').SyncDefinitionsRequest) => Promise<import('../types').SyncResult>;
  onPullChanges: () => Promise<import('../types').SyncResult>;
  syncResult: import('../types').SyncResult | undefined;
  pullResult: import('../types').SyncResult | undefined;
  startLoading: boolean;
  stopLoading: boolean;
  resetLoading: boolean;
  syncLoading: boolean;
  pullLoading: boolean;
}) {
  const [syncConfirm, setSyncConfirm] = useState(false);
  const [resetConfirm, setResetConfirm] = useState(false);
  const [syncModalOpen, setSyncModalOpen] = useState(false);
  const { data: syncPreview, isLoading: previewLoading } = useTestingSyncPreview(syncModalOpen);

  const handleSync = () => {
    if (!syncConfirm) {
      setSyncConfirm(true);
      return;
    }
    setSyncConfirm(false);
    onSyncAll();
  };

  const handleReset = () => {
    if (!resetConfirm) {
      setResetConfirm(true);
      return;
    }
    setResetConfirm(false);
    onReset();
  };

  return (
    <Card>
      <CardHeader title={t.settings.testing} />
      <SyncSelectModal
        open={syncModalOpen}
        onClose={() => setSyncModalOpen(false)}
        preview={syncPreview}
        previewLoading={previewLoading}
        onSync={onSyncSelective}
        syncLoading={syncLoading}
        title={t.settings.syncSelectTitle}
        t={t}
      />
      <div className="space-y-4">
        {/* Status row */}
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <FlaskConical className="w-5 h-5 text-text-secondary" />
            <div>
              <div className="flex items-center gap-2">
                <p className="text-sm font-medium text-text">Test</p>
                <a href="http://localhost:8090" target="_blank" rel="noopener noreferrer" className="text-xs text-accent hover:text-accent-hover font-mono hover:underline">localhost:8090</a>
              </div>
              <p className="text-xs text-text-tertiary">{t.settings.testingHint}</p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <StatusBadge
              status={testing?.containerRunning ? (testing.healthy ? 'RUNNING' : 'STARTING') : 'STOPPED'}
              label={testing?.containerRunning ? t.settings.testingRunning : t.settings.testingStopped}
            />
          </div>
        </div>

        {/* Version comparison */}
        {(devVersion || testing?.testVersion) && (
          <div className="pl-8 flex items-center gap-3 text-xs">
            <span className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-md bg-accent/10 text-accent font-medium">
              {t.settings.stagingDevVersion} v{devVersion ?? t.settings.stagingVersionUnknown}
            </span>
            <span className="text-text-tertiary">vs</span>
            <span className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded-md font-medium ${
              testing?.testVersion && devVersion && testing.testVersion !== devVersion
                ? 'bg-warning/10 text-warning'
                : 'bg-surface-raised text-text-secondary'
            }`}>
              {t.settings.testingVersion} v{testing?.testVersion ?? t.settings.stagingVersionUnknown}
            </span>
          </div>
        )}

        {/* Action buttons row */}
        <div className="flex items-center gap-2 pl-8">
          {testing?.containerRunning && (
            <Button
              size="sm"
              variant="ghost"
              icon={<ExternalLink className="w-3.5 h-3.5" />}
              onClick={() => window.open('http://localhost:8090', '_blank')}
            >
              {t.settings.testingOpenUi}
            </Button>
          )}
          {testing?.containerRunning ? (
            <Button
              size="sm"
              variant="danger"
              icon={<Square className="w-3.5 h-3.5" />}
              onClick={onStop}
              loading={stopLoading}
            >
              {t.settings.testingStop}
            </Button>
          ) : (
            <Button
              size="sm"
              icon={<Play className="w-3.5 h-3.5" />}
              onClick={onStart}
              loading={startLoading}
            >
              {t.settings.testingStart}
            </Button>
          )}
        </div>

        {/* Divider */}
        <div className="border-t border-border" />

        {/* Reset section */}
        <div className="space-y-3 pl-8">
          {resetConfirm ? (
            <div className="flex items-center gap-2">
              <span className="text-xs text-warning">{t.settings.testingResetConfirm}</span>
              <Button size="sm" variant="danger" onClick={handleReset} loading={resetLoading}>
                {t.common.confirm}
              </Button>
              <Button size="sm" variant="ghost" onClick={() => setResetConfirm(false)}>
                {t.common.cancel}
              </Button>
            </div>
          ) : (
            <div>
              <Button
                size="sm"
                variant="danger"
                icon={<Trash2 className="w-3.5 h-3.5" />}
                onClick={handleReset}
                loading={resetLoading}
                disabled={!testing?.containerRunning}
              >
                {t.settings.testingReset}
              </Button>
              <p className="text-xs text-text-tertiary mt-1">{t.settings.testingResetHint}</p>
            </div>
          )}

          {/* Sync definitions */}
          {syncConfirm ? (
            <div className="flex items-center gap-2">
              <span className="text-xs text-warning">{t.settings.testingSyncConfirm}</span>
              <Button size="sm" variant="danger" onClick={handleSync} loading={syncLoading}>
                {t.common.confirm}
              </Button>
              <Button size="sm" variant="ghost" onClick={() => setSyncConfirm(false)}>
                {t.common.cancel}
              </Button>
            </div>
          ) : (
            <div>
              <div className="flex items-center gap-2">
                <Button
                  size="sm"
                  variant="ghost"
                  icon={<RefreshCw className="w-3.5 h-3.5" />}
                  onClick={handleSync}
                  loading={syncLoading}
                  disabled={!testing?.healthy}
                >
                  {t.settings.syncAll}
                </Button>
                <Button
                  size="sm"
                  variant="ghost"
                  icon={<ListChecks className="w-3.5 h-3.5" />}
                  onClick={() => setSyncModalOpen(true)}
                  disabled={!testing?.healthy || syncLoading}
                >
                  {t.settings.syncSelective}
                </Button>
              </div>
              <p className="text-xs text-text-tertiary mt-1">{t.settings.testingSyncHint}</p>
            </div>
          )}

          {/* Sync result display */}
          {syncResult && (
            <div className="space-y-1">
              <p className="text-xs text-success">
                {t.settings.testingSyncResult
                  .replace('{workflows}', String(syncResult.workflowsSynced))
                  .replace('{projects}', String(syncResult.projectsSynced))
                  .replace('{skills}', String(syncResult.skillsSynced))
                  .replace('{skipped}', String(syncResult.skipped))}
              </p>
              {syncResult.errors.length > 0 && (
                <div className="text-xs text-danger">
                  <p>{t.settings.testingSyncErrors.replace('{count}', String(syncResult.errors.length))}</p>
                  {syncResult.errors.map((err, i) => (
                    <p key={i} className="pl-2 text-text-tertiary">{err}</p>
                  ))}
                </div>
              )}
            </div>
          )}

          {/* Pull changes from Test */}
          <div>
            <Button
              size="sm"
              variant="ghost"
              icon={<ArrowDownToLine className="w-3.5 h-3.5" />}
              onClick={() => onPullChanges()}
              loading={pullLoading}
              disabled={!testing?.healthy}
            >
              {t.settings.testingPullChanges}
            </Button>
            <p className="text-xs text-text-tertiary mt-1">{t.settings.testingPullChangesHint}</p>
          </div>

          {/* Pull result display */}
          {pullResult && (
            <div className="space-y-1">
              <p className="text-xs text-success">
                {t.settings.testingPullResult.replace('{workflows}', String(pullResult.workflowsSynced))}
              </p>
              {pullResult.errors.length > 0 && (
                <div className="text-xs text-danger">
                  {pullResult.errors.map((err, i) => (
                    <p key={i} className="pl-2 text-text-tertiary">{err}</p>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </Card>
  );
}
