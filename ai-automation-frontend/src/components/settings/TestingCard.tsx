import { useState } from 'react';
import { Card, CardHeader, Button } from '../ui';
import { StatusBadge } from '../shared/StatusBadge';
import { SyncSelectModal } from '../shared/SyncSelectModal';
import { useT } from '../../i18n';
import { useTestingSyncPreview } from '../../hooks/useTesting';
import type { TestingStatus, SyncResult, SyncDefinitionsRequest } from '../../types';
import { FlaskConical, Play, Square, ExternalLink, Trash2, RefreshCw, ListChecks, ArrowDownToLine, Wrench } from 'lucide-react';

interface TestingCardProps {
  testing: TestingStatus | undefined;
  devVersion: string | undefined;
  onStart: () => void;
  onStop: () => void;
  onReset: () => Promise<unknown>;
  onSyncAll: () => Promise<SyncResult>;
  onSyncSelective: (request: SyncDefinitionsRequest) => Promise<SyncResult>;
  onPullChanges: () => Promise<SyncResult>;
  onRepairAll: () => void;
  syncResult: SyncResult | undefined;
  pullResult: SyncResult | undefined;
  startLoading: boolean;
  stopLoading: boolean;
  resetLoading: boolean;
  syncLoading: boolean;
  pullLoading: boolean;
  repairAllLoading: boolean;
}

export function TestingCard({ testing, devVersion, onStart, onStop, onReset, onSyncAll, onSyncSelective, onPullChanges, onRepairAll, syncResult, pullResult, startLoading, stopLoading, resetLoading, syncLoading, pullLoading, repairAllLoading }: TestingCardProps) {
  const t = useT();
  const [syncConfirm, setSyncConfirm] = useState(false);
  const [resetConfirm, setResetConfirm] = useState(false);
  const [repairConfirm, setRepairConfirm] = useState(false);
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

        <div className="border-t border-border" />

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

          <div className="border-t border-border" />

          {repairConfirm ? (
            <div className="flex items-center gap-2">
              <span className="text-xs text-warning">{t.settings.repairAllConfirm}</span>
              <Button size="sm" variant="danger" onClick={() => { setRepairConfirm(false); onRepairAll(); }} loading={repairAllLoading}>
                {t.common.confirm}
              </Button>
              <Button size="sm" variant="ghost" onClick={() => setRepairConfirm(false)}>
                {t.common.cancel}
              </Button>
            </div>
          ) : (
            <div>
              <Button
                size="sm"
                variant="ghost"
                icon={<Wrench className="w-3.5 h-3.5" />}
                onClick={() => setRepairConfirm(true)}
                loading={repairAllLoading}
                disabled={!testing?.healthy}
              >
                {t.settings.repairAll}
              </Button>
              <p className="text-xs text-text-tertiary mt-1">{t.settings.repairAllHint}</p>
            </div>
          )}
        </div>
      </div>
    </Card>
  );
}
