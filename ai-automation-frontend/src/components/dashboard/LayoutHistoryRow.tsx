import { useState } from 'react';
import { Layers, Pencil, RotateCcw } from 'lucide-react';
import { Badge, Button, Input, Spinner } from '../ui';
import { useI18nStore, useT } from '../../i18n';
import { formatDateTime, formatRelativeTime } from '../../utils/formatTime';
import { useDashboardLayoutVersion, useRenameDashboardLayoutVersion } from '../../hooks/useDashboardLayoutVersions';
import { layoutFromVersion } from '../../hooks/useDashboardLayoutSync';
import type { DashboardLayoutVersionSummary } from '../../types';

interface LayoutHistoryRowProps {
  version: DashboardLayoutVersionSummary;
  isCurrent: boolean;
  busy: boolean;
  onRestore: (versionId: string) => void;
  onAdoptView: (versionId: string, viewId: string) => void;
}

export function LayoutHistoryRow({ version, isCurrent, busy, onRestore, onAdoptView }: LayoutHistoryRowProps) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  const [renaming, setRenaming] = useState(false);
  const [nameDraft, setNameDraft] = useState(version.name ?? '');
  const [showViews, setShowViews] = useState(false);
  const rename = useRenameDashboardLayoutVersion();
  const { data: full } = useDashboardLayoutVersion(version.id, showViews);
  const views = full ? layoutFromVersion(full)?.views ?? [] : [];
  const created = formatDateTime(version.createdAt, locale);
  const details = [
    version.name ? created : null,
    formatRelativeTime(version.createdAt, locale),
    version.deviceLabel,
    t.dashboard.history.viewCount.replace('{count}', String(version.viewCount)),
  ].filter(Boolean).join(' · ');

  return (
    <li data-version-id={version.id} className="space-y-2 px-4 py-3">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="flex flex-wrap items-center gap-2 text-sm font-medium text-text">
            <span className="truncate">{version.name || created}</span>
            {isCurrent && <Badge variant="success">{t.dashboard.history.current}</Badge>}
          </p>
          <p className="text-xs text-text-tertiary">{details}</p>
        </div>
        <div className="flex flex-wrap gap-1">
          <Button variant="ghost" size="sm" icon={<Pencil className="w-3.5 h-3.5" />} onClick={() => setRenaming((v) => !v)}>
            {t.dashboard.history.rename}
          </Button>
          <Button variant="ghost" size="sm" icon={<Layers className="w-3.5 h-3.5" />} onClick={() => setShowViews((v) => !v)}>
            {t.dashboard.history.adoptView}
          </Button>
          <Button
            variant="secondary"
            size="sm"
            icon={<RotateCcw className="w-3.5 h-3.5" />}
            disabled={busy}
            onClick={() => {
              if (window.confirm(t.dashboard.history.restoreConfirm)) onRestore(version.id);
            }}
          >
            {t.dashboard.history.restore}
          </Button>
        </div>
      </div>

      {renaming && (
        <form
          className="flex flex-wrap items-end gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            rename.mutate({ id: version.id, name: nameDraft.trim() || null }, { onSuccess: () => setRenaming(false) });
          }}
        >
          <div className="min-w-0 flex-1">
            <Input
              aria-label={t.dashboard.history.nameLabel}
              placeholder={t.dashboard.history.namePlaceholder}
              value={nameDraft}
              maxLength={80}
              onChange={(e) => setNameDraft(e.target.value)}
            />
          </div>
          <Button type="submit" size="sm" loading={rename.isPending}>
            {t.common.save}
          </Button>
        </form>
      )}

      {showViews && (
        <div className="flex flex-wrap items-center gap-2">
          {!full ? (
            <Spinner size="sm" />
          ) : views.length === 0 ? (
            <span className="text-xs text-text-tertiary">{t.dashboard.history.noViews}</span>
          ) : (
            views.map((view) => (
              <Button key={view.id} variant="secondary" size="sm" disabled={busy} onClick={() => onAdoptView(version.id, view.id)}>
                {t.dashboard.history.adoptViewNamed.replace('{name}', view.name || t.dashboard.history.defaultViewName)}
              </Button>
            ))
          )}
        </div>
      )}
    </li>
  );
}
