import { Download, Upload } from 'lucide-react';
import { Button } from '../ui';
import { useI18nStore, useT } from '../../i18n';
import { formatDateTime } from '../../utils/formatTime';
import type { DashboardLayoutVersionSummary } from '../../types';

interface LayoutSyncConflictBannerProps {
  remote: DashboardLayoutVersionSummary;
  busy: boolean;
  onKeepRemote: () => void;
  onKeepLocal: () => void;
}

/** Another device saved a newer layout while this browser has unsaved changes — the user decides. */
export function LayoutSyncConflictBanner({ remote, busy, onKeepRemote, onKeepLocal }: LayoutSyncConflictBannerProps) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const message = t.dashboard.sync.conflict
    .replace('{device}', remote.deviceLabel)
    .replace('{time}', formatDateTime(remote.createdAt, locale));

  return (
    <div
      role="alert"
      data-testid="layout-sync-conflict"
      className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-warning/40 bg-warning-subtle px-4 py-3"
    >
      <p className="min-w-0 text-sm text-text">{message}</p>
      <div className="flex flex-wrap gap-2">
        <Button variant="secondary" size="sm" icon={<Download className="w-3.5 h-3.5" />} onClick={onKeepRemote} disabled={busy}>
          {t.dashboard.sync.keepRemote}
        </Button>
        <Button variant="secondary" size="sm" icon={<Upload className="w-3.5 h-3.5" />} onClick={onKeepLocal} disabled={busy}>
          {t.dashboard.sync.keepLocal}
        </Button>
      </div>
    </div>
  );
}
