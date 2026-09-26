import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Card, CardHeader, Button } from '../ui';
import { downloadFromResponse } from '../../utils/downloadFile';
import { useSettingsStore } from '../../stores/settingsStore';
import { useI18nStore, useT } from '../../i18n';
import { formatDecimal, formatTimestamp } from '../../utils/localeFormat';
import { useBackupList, useUpdateSystemSetting } from '../../hooks/useSystem';
import { api } from '../../api/client';
import { Database, Download, HardDrive, Archive } from 'lucide-react';

const retainCountKey = ['system', 'settings', 'backup-retain-count'];

export function BackupCard() {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const { data: backups } = useBackupList();
  const queryClient = useQueryClient();
  const [backupLoading, setBackupLoading] = useState(false);

  const { data: retainCountSetting } = useQuery({
    queryKey: retainCountKey,
    queryFn: async () => {
      const r = await api.get<{ value: string }>('/system/settings/backup-retain-count');
      return r.value ? parseInt(r.value, 10) : 30;
    },
    staleTime: 5 * 60 * 1000,
  });
  // The field shows the stored value until the user types; the draft only lives while dirty
  const [retainDraft, setRetainDraft] = useState<number>(30);
  const [retainDirty, setRetainDirty] = useState(false);
  const retainCount = retainDirty ? retainDraft : (retainCountSetting ?? 30);
  const updateSetting = useUpdateSystemSetting();

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
    if (bytes < 1024 * 1024) return `${formatDecimal(bytes / 1024, locale)} KB`;
    return `${formatDecimal(bytes / (1024 * 1024), locale)} MB`;
  };

  return (
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
                {formatTimestamp(backups[0]!.createdAt, locale)}
              </span>
              <span className="text-text-tertiary">({formatBytes(backups[0]!.sizeBytes)})</span>
            </div>
          )}
        </div>

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

        <div className="flex items-center justify-between pt-2 border-t border-border">
          <div className="flex items-center gap-3">
            <Archive className="w-5 h-5 text-text-secondary" />
            <div>
              <p className="text-sm font-medium text-text">{t.settings.backupRetainCount}</p>
              <p className="text-xs text-text-tertiary">{t.settings.backupRetainCountDescription}</p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <input
              type="number"
              min={1}
              max={365}
              value={retainCount}
              onChange={(e) => {
                setRetainDraft(parseInt(e.target.value, 10) || 1);
                setRetainDirty(true);
              }}
              className="w-20 px-2 py-1 text-sm rounded border border-border bg-surface text-text text-right"
            />
            {retainDirty && (
              <Button
                size="sm"
                onClick={() => {
                  updateSetting.mutate(
                    { key: 'backup-retain-count', value: String(retainCount) },
                    {
                      onSuccess: () => {
                        // The saved value becomes the stored one, so the field keeps it after the draft ends
                        queryClient.setQueryData(retainCountKey, retainCount);
                        setRetainDirty(false);
                      },
                    }
                  );
                }}
                loading={updateSetting.isPending}
              >
                {t.common.save}
              </Button>
            )}
          </div>
        </div>
      </div>
    </Card>
  );
}
