import { useState } from 'react';
import { Button, Input, Modal, Spinner } from '../ui';
import { useT } from '../../i18n';
import { useDashboardLayoutVersions } from '../../hooks/useDashboardLayoutVersions';
import { useDashboardSyncStore } from '../../stores/dashboardSyncStore';
import { LayoutHistoryRow } from './LayoutHistoryRow';

interface LayoutHistoryDialogProps {
  open: boolean;
  onClose: () => void;
  busy: boolean;
  onRestore: (versionId: string) => void;
  onAdoptView: (versionId: string, viewId: string) => void;
}

function DeviceLabelField() {
  const t = useT();
  const deviceLabel = useDashboardSyncStore((s) => s.deviceLabel);
  const setDeviceLabel = useDashboardSyncStore((s) => s.setDeviceLabel);
  const [draft, setDraft] = useState(deviceLabel);

  return (
    <Input
      label={t.dashboard.history.deviceLabel}
      hint={t.dashboard.history.deviceLabelHint}
      value={draft}
      maxLength={80}
      onChange={(e) => setDraft(e.target.value)}
      onBlur={() => {
        setDeviceLabel(draft);
        setDraft(useDashboardSyncStore.getState().deviceLabel);
      }}
    />
  );
}

export function LayoutHistoryDialog({ open, onClose, busy, onRestore, onAdoptView }: LayoutHistoryDialogProps) {
  const t = useT();
  const [page, setPage] = useState(0);
  const { data, isLoading, error } = useDashboardLayoutVersions(page, 20, open);
  const baseVersionId = useDashboardSyncStore((s) => s.baseVersionId);

  return (
    <Modal open={open} onClose={onClose} title={t.dashboard.history.title} size="lg">
      <div className="space-y-4">
        <DeviceLabelField />
        {isLoading ? (
          <div className="flex justify-center py-6"><Spinner /></div>
        ) : error ? (
          <p className="text-sm text-danger">{t.dashboard.history.loadFailed}</p>
        ) : !data?.content.length ? (
          <p className="text-sm text-text-tertiary">{t.dashboard.history.empty}</p>
        ) : (
          <ul className="divide-y divide-border rounded-xl border border-border">
            {data.content.map((version) => (
              <LayoutHistoryRow
                key={version.id}
                version={version}
                isCurrent={version.id === baseVersionId}
                busy={busy}
                onRestore={onRestore}
                onAdoptView={onAdoptView}
              />
            ))}
          </ul>
        )}
        {data && data.totalPages > 1 && (
          <div className="flex justify-between">
            <Button variant="ghost" size="sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
              {t.dashboard.history.newer}
            </Button>
            <Button variant="ghost" size="sm" disabled={page + 1 >= data.totalPages} onClick={() => setPage((p) => p + 1)}>
              {t.dashboard.history.older}
            </Button>
          </div>
        )}
      </div>
    </Modal>
  );
}
