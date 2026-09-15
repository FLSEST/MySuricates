import { useState, useEffect } from 'react';
import { Check, History, Pencil, RotateCcw } from 'lucide-react';
import { Button } from '../components/ui';
import { DashboardGrid, HiddenWidgetsBar } from '../components/dashboard/DashboardGrid';
import { useDashboard } from '../hooks/useLearning';
import { useDashboardLayout } from '../hooks/useDashboardLayout';
import { hasLocalLayoutChanges, useDashboardLayoutSync } from '../hooks/useDashboardLayoutSync';
import { LayoutHistoryDialog } from '../components/dashboard/LayoutHistoryDialog';
import { LayoutSyncConflictBanner } from '../components/dashboard/LayoutSyncConflictBanner';
import { useT } from '../i18n';

function useLiveClock() {
  const [now, setNow] = useState(new Date());
  useEffect(() => {
    const id = setInterval(() => setNow(new Date()), 1000);
    return () => clearInterval(id);
  }, []);
  return now;
}

function LiveClock() {
  const now = useLiveClock();
  return (
    <div className="text-right shrink-0">
      <p className="text-2xl font-bold text-text font-mono tabular-nums">
        {now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })}
      </p>
      <p className="text-sm text-text-secondary">
        {now.toLocaleDateString([], { weekday: 'long', day: '2-digit', month: '2-digit', year: 'numeric' })}
      </p>
    </div>
  );
}

export function Dashboard() {
  const t = useT();
  // Widgets load their own data and show skeletons; the page only reports a failed dashboard query.
  const { error, refetch } = useDashboard();
  const layout = useDashboardLayout();
  const [editMode, setEditMode] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const sync = useDashboardLayoutSync();

  const toggleEditMode = () => {
    // Leaving edit mode is the save point of the version history (spec E1).
    if (editMode && hasLocalLayoutChanges()) void sync.saveSnapshot();
    setEditMode((v) => !v);
  };

  return (
    <div className="space-y-6 animate-fade-in">
      {/* Page header */}
      <div className="flex items-start justify-between gap-4">
        <div className="min-w-0">
          <h1 className="text-2xl font-bold text-text">{t.dashboard.title}</h1>
          <p className="text-sm text-text-secondary mt-1 truncate">{t.dashboard.subtitle}</p>
        </div>
        <div className="flex items-start gap-3 shrink-0">
          <div className="flex items-center gap-2 pt-1">
            {editMode && (
              <Button
                variant="ghost"
                size="sm"
                icon={<History className="w-3.5 h-3.5" />}
                onClick={() => setHistoryOpen(true)}
                aria-label={t.dashboard.history.open}
                title={t.dashboard.history.open}
              >
                <span className="hidden sm:inline">{t.dashboard.history.open}</span>
              </Button>
            )}
            {editMode && layout.isCustomized && (
              <Button
                variant="ghost"
                size="sm"
                icon={<RotateCcw className="w-3.5 h-3.5" />}
                onClick={layout.reset}
                aria-label={t.dashboard.resetLayout}
                title={t.dashboard.resetLayout}
              >
                <span className="hidden sm:inline">{t.dashboard.resetLayout}</span>
              </Button>
            )}
            <Button
              variant={editMode ? 'primary' : 'secondary'}
              size="sm"
              icon={editMode ? <Check className="w-3.5 h-3.5" /> : <Pencil className="w-3.5 h-3.5" />}
              onClick={toggleEditMode}
              aria-label={editMode ? t.dashboard.doneEditing : t.dashboard.editDashboard}
              aria-pressed={editMode}
            >
              <span className="hidden sm:inline">{editMode ? t.dashboard.doneEditing : t.dashboard.editDashboard}</span>
            </Button>
          </div>
          <LiveClock />
        </div>
      </div>

      {sync.conflict && (
        <LayoutSyncConflictBanner
          remote={sync.conflict}
          busy={sync.busy}
          onKeepRemote={() => void sync.keepRemote()}
          onKeepLocal={() => void sync.keepLocal()}
        />
      )}

      {error && (
        <div
          role="alert"
          className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-danger/40 bg-danger-subtle px-4 py-3"
        >
          <p className="min-w-0 text-sm text-text">
            {t.dashboard.dataUnavailable} <span className="text-text-secondary">{error.message}</span>
          </p>
          <Button
            variant="secondary"
            size="sm"
            icon={<RotateCcw className="w-3.5 h-3.5" />}
            onClick={() => refetch()}
          >
            {t.common.retry}
          </Button>
        </div>
      )}

      {editMode && (
        <HiddenWidgetsBar widgets={layout.hidden} onShow={(id) => layout.setHidden(id, false)} />
      )}

      <DashboardGrid
        widgets={layout.visible}
        editMode={editMode}
        onMove={layout.move}
        onHide={(id) => layout.setHidden(id, true)}
      />

      <LayoutHistoryDialog
        open={historyOpen}
        onClose={() => setHistoryOpen(false)}
        busy={sync.busy}
        onRestore={(id) => void sync.restoreVersion(id)}
        onAdoptView={(versionId, viewId) => void sync.adoptView(versionId, viewId)}
      />
    </div>
  );
}
