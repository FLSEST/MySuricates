import { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Card, CardHeader, Button } from '../ui';
import { downloadFromResponse } from '../../utils/downloadFile';
import { useSettingsStore } from '../../stores/settingsStore';
import { useT } from '../../i18n';
import { useBackupList } from '../../hooks/useSystem';
import { Database, Download, HardDrive } from 'lucide-react';

export function BackupCard() {
  const t = useT();
  const { data: backups } = useBackupList();
  const queryClient = useQueryClient();
  const [backupLoading, setBackupLoading] = useState(false);

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
                {new Date(backups[0]!.createdAt).toLocaleString()}
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
      </div>
    </Card>
  );
}
