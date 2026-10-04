import { useState } from 'react';
import { Card, CardHeader, Button } from '../ui';
import { StatusBadge } from '../shared/StatusBadge';
import { SyncSelectModal } from './SyncSelectModal';
import { SyncNotes } from './SyncNotes';
import { useT } from '../../i18n';
import { apiErrorMessage } from '../../api/client';
import { useToastStore } from '../../stores/toastStore';
import { useStagedSync, useStagedSyncPreview } from '../../hooks/useStagedSync';
import type { SyncTarget, SyncResult, SyncDefinitionsRequest } from '../../types';
import { FlaskConical, ShieldCheck, ArrowUpFromLine } from 'lucide-react';

const SYNC_KEYS_COMMAND = 'scripts/instance.sh sync-keys --name dev';

/** "test" → "Test": the instance names are the same words in both languages. */
const label = (name: string) => name.charAt(0).toUpperCase() + name.slice(1);

/**
 * Staged setup (84a78bb7): Dev transfers its definitions to Test and Prod — the targets the
 * installer wrote into the startup configuration. Prod takes only sealed definitions; the preview
 * already shows only those.
 */
export function StagedTargetsCard({ targets }: { targets: SyncTarget[] }) {
  const t = useT();
  const addToast = useToastStore((s) => s.addToast);
  const [open, setOpen] = useState<string | null>(null);
  const { data: preview, isLoading: previewLoading } = useStagedSyncPreview(open);
  const sync = useStagedSync();
  const [results, setResults] = useState<Record<string, SyncResult>>({});

  const handleSync = async (target: string, request: SyncDefinitionsRequest): Promise<SyncResult> => {
    let result: SyncResult;
    try {
      result = await sync.mutateAsync({ target, request });
    } catch (err) {
      const message = apiErrorMessage(err, t.settings.stagedSyncFailed.replace('{target}', label(target)));
      addToast('error', message);
      result = { projectsSynced: 0, workflowsSynced: 0, skillsSynced: 0, skipped: 0, errors: [message], preSyncBackup: null };
    }
    setResults((prev) => ({ ...prev, [target]: result }));
    return result;
  };

  return (
    <Card data-testid="staged-targets-card">
      <CardHeader title={t.settings.stagedSyncTitle} />
      <SyncSelectModal
        open={open !== null}
        onClose={() => setOpen(null)}
        preview={preview}
        previewLoading={previewLoading}
        onSync={(request) => handleSync(open ?? '', request)}
        syncLoading={sync.isPending}
        title={t.settings.stagedSyncTo.replace('{target}', label(open ?? ''))}
        t={t}
      />
      <p className="text-xs text-text-tertiary mb-4">{t.settings.stagedSyncHint}</p>
      <div className="space-y-4">
        {targets.map((target) => {
          const Icon = target.sealedOnly ? ShieldCheck : FlaskConical;
          const result = results[target.name];
          const status = !target.keyPresent ? 'STARTING' : target.reachable ? 'RUNNING' : 'STOPPED';
          const statusLabel = !target.keyPresent
            ? t.settings.stagedKeyMissing
            : target.reachable ? t.settings.stagedReachable : t.settings.stagedUnreachable;
          return (
            <div key={target.name} className="space-y-2" data-testid={`staged-target-${target.name}`}>
              <div className="flex items-center justify-between gap-3">
                <div className="flex items-center gap-3 min-w-0">
                  <Icon className="w-5 h-5 text-text-secondary shrink-0" />
                  <div className="min-w-0">
                    <p className="text-sm font-medium text-text">
                      {label(target.name)}
                      {target.version && <span className="ml-2 text-xs text-text-tertiary">v{target.version}</span>}
                    </p>
                    <p className="text-xs text-text-tertiary font-mono truncate">{target.url}</p>
                  </div>
                </div>
                <StatusBadge status={status} label={statusLabel} />
              </div>
              <div className="pl-8 space-y-1">
                <Button
                  size="sm"
                  variant="ghost"
                  icon={<ArrowUpFromLine className="w-3.5 h-3.5" />}
                  onClick={() => setOpen(target.name)}
                  disabled={!target.keyPresent || !target.reachable || sync.isPending}
                >
                  {t.settings.stagedSyncTo.replace('{target}', label(target.name))}
                </Button>
                {target.sealedOnly && <p className="text-xs text-text-tertiary">{t.settings.stagedSealedOnly}</p>}
                {!target.keyPresent && (
                  <p className="text-xs text-text-tertiary">
                    {t.settings.stagedKeyMissingHint} <code className="font-mono text-text-secondary">{SYNC_KEYS_COMMAND}</code>
                  </p>
                )}
                {result && (
                  <div className="space-y-1">
                    <p className="text-xs text-success">
                      {t.settings.testingSyncResult
                        .replace('{workflows}', String(result.workflowsSynced))
                        .replace('{projects}', String(result.projectsSynced))
                        .replace('{skills}', String(result.skillsSynced))
                        .replace('{skipped}', String(result.skipped))}
                    </p>
                    {result.preSyncBackup && (
                      <p className="text-xs text-text-tertiary">
                        {t.settings.syncPreBackupHint.replace('{filename}', result.preSyncBackup)}
                      </p>
                    )}
                    <SyncNotes notes={result.notes} title={t.settings.syncNotesTitle} />
                    {result.errors.length > 0 && (
                      <div className="text-xs text-danger">
                        <p>{t.settings.testingSyncErrors.replace('{count}', String(result.errors.length))}</p>
                        {result.errors.map((err, i) => (
                          <p key={i} className="pl-2 text-text-tertiary">{err}</p>
                        ))}
                      </div>
                    )}
                  </div>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </Card>
  );
}
