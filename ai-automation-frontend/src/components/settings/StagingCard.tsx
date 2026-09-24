import { useState } from 'react';
import { Card, CardHeader, Button } from '../ui';
import { StatusBadge } from '../shared/StatusBadge';
import { SyncSelectModal } from './SyncSelectModal';
import { useT } from '../../i18n';
import { useStagingSyncPreview } from '../../hooks/useStaging';
import type { StagingStatus, SyncResult, SyncDefinitionsRequest } from '../../types';
import { Container, Play, Square, Upload, ExternalLink, X, RefreshCw, ListChecks } from 'lucide-react';

type PromotePhase = 'STOPPING' | 'BUILDING_BACKEND' | 'BUILDING_FRONTEND' | 'BUILDING_IMAGE' | 'STARTING';

type PhaseKey = `stagingPhase${PromotePhase}`;

interface StagingCardProps {
  staging: StagingStatus | undefined;
  devVersion: string | undefined;
  onStart: () => void;
  onStop: () => void;
  onPromote: () => void;
  onCancel: () => void;
  onSyncAll: () => Promise<SyncResult>;
  onSyncSelective: (request: SyncDefinitionsRequest) => Promise<SyncResult>;
  syncResult: SyncResult | undefined;
  startLoading: boolean;
  stopLoading: boolean;
  promoteLoading: boolean;
  syncLoading: boolean;
}

export function StagingCard({ staging, devVersion, onStart, onStop, onPromote, onCancel, onSyncAll, onSyncSelective, syncResult, startLoading, stopLoading, promoteLoading, syncLoading }: StagingCardProps) {
  const t = useT();
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

        <div className="pl-8 text-xs text-text-tertiary">
          {t.settings.stagingLastSnapshot}: {' '}
          <span className="font-mono text-text-secondary">
            {staging?.lastSnapshot ?? t.settings.stagingNoSnapshot}
          </span>
        </div>

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

        <div className="border-t border-border" />

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
            {staging?.promotePhase === 'ERROR' && staging.error && (
              <div className="text-xs text-danger bg-danger/10 rounded-lg p-2">
                {t.settings.stagingPromoteFailed}: {staging.error}
              </div>
            )}

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

              {syncResult && (
                <div className="mt-2 space-y-1">
                  <p className="text-xs text-success">
                    {t.settings.stagingSyncResult
                      .replace('{workflows}', String(syncResult.workflowsSynced))
                      .replace('{projects}', String(syncResult.projectsSynced))
                      .replace('{skills}', String(syncResult.skillsSynced))
                      .replace('{skipped}', String(syncResult.skipped))}
                  </p>
                  {syncResult.preSyncBackup && (
                    <p className="text-xs text-text-tertiary">
                      {t.settings.syncPreBackupHint.replace('{filename}', syncResult.preSyncBackup)}
                    </p>
                  )}
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
