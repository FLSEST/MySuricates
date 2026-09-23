import { useState, useEffect } from 'react';
import { Check, History, Pencil, RotateCcw } from 'lucide-react';
import { Button } from '../components/ui';
import { DashboardGrid } from '../components/dashboard/DashboardGrid';
import { DashboardEditBar } from '../components/dashboard/DashboardEditBar';
import { WidgetCatalogDialog } from '../components/dashboard/WidgetCatalogDialog';
import { DashboardDataProvider } from '../components/dashboard/DashboardDataProvider';
import { DashboardDataErrorBanner } from '../components/dashboard/DashboardDataErrorBanner';
import { FirstStepsCard } from '../components/dashboard/FirstStepsCard';
import { AiProviderStartCard } from '../components/dashboard/AiProviderStartCard';
import { Hint } from '../components/hints/Hint';
import { WorkflowWidgetProvider } from '../components/dashboard/WorkflowWidgetProvider';
import { useDashboardData } from '../components/dashboard/dashboardDataContext';
import { DASHBOARD_VIEW_PANEL_ID, viewTabId } from '../components/dashboard/viewTabA11y';
import { DashboardViewFilterBar } from '../components/dashboard/DashboardViewFilterBar';
import { DashboardViewTabs } from '../components/dashboard/DashboardViewTabs';
import { EmptyViewState } from '../components/dashboard/EmptyViewState';
import { LayoutHistoryDialog } from '../components/dashboard/LayoutHistoryDialog';
import { LayoutSyncConflictBanner } from '../components/dashboard/LayoutSyncConflictBanner';
import { selectActiveView, useDashboardLayoutStore } from '../stores/dashboardLayoutStore';
import { useDashboardLayout } from '../hooks/useDashboardLayout';
import { hasLocalLayoutChanges, useDashboardLayoutSync } from '../hooks/useDashboardLayoutSync';
import { useDashboardViewParam } from '../hooks/useDashboardViewParam';
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

/** The active view's filter decides what the page's one learning-dashboard query loads (spec E4). */
export function Dashboard() {
  const filter = useDashboardLayoutStore((s) => selectActiveView(s)?.filter);
  return (
    <DashboardDataProvider filter={filter}>
      <DashboardPage />
    </DashboardDataProvider>
  );
}

function DashboardPage() {
  const t = useT();
  const layout = useDashboardLayout();
  const [editMode, setEditMode] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [catalogOpen, setCatalogOpen] = useState(false);
  const sync = useDashboardLayoutSync();
  const { isPlaceholderData } = useDashboardData();
  const activeViewId = useDashboardLayoutStore((s) => selectActiveView(s)?.id);
  const viewCount = useDashboardLayoutStore((s) => s.views.length);
  useDashboardViewParam();
  // While a version is restored, adopted or saved (`busy`), the layout can change underneath: no edits.
  const locked = sync.busy;
  // Outside edit mode with several views the tabs switch views, and the widget area is their panel.
  const tabPanel = !editMode && viewCount > 1 && activeViewId !== undefined;

  const toggleEditMode = () => {
    // Leaving edit mode is the save point of the version history (spec E1).
    if (editMode && hasLocalLayoutChanges()) void sync.saveSnapshot();
    setCatalogOpen(false);
    setEditMode((v) => !v);
  };

  return (
    <div className="space-y-6 animate-fade-in">
      {/* Page header — outside edit mode the view tabs sit in it instead of taking a row of their
          own (565adc37); on narrow screens they wrap below the title. */}
      <div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2 sm:flex-nowrap">
        {/* basis-0: on narrow screens the title shrinks next to the buttons instead of pushing them down */}
        <div className="min-w-0 flex-1 basis-0 sm:flex-none sm:basis-auto">
          <h1 className="text-2xl font-bold text-text">{t.dashboard.title}</h1>
          <p className="text-sm text-text-secondary mt-1 truncate">{t.dashboard.subtitle}</p>
        </div>
        {!editMode && (
          <div className="order-last flex w-full min-w-0 empty:hidden sm:order-none sm:w-auto sm:flex-1 sm:pt-0.5">
            <DashboardViewTabs editMode={false} disabled={locked} />
          </div>
        )}
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
                disabled={locked}
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
              disabled={locked}
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

      <DashboardDataErrorBanner />

      <FirstStepsCard />

      <AiProviderStartCard />

      <Hint id="dashboard-views" />

      {/* Collapses when there is no filter to show (and, in edit mode, carries the view list). */}
      <div className="space-y-3 empty:hidden">
        {editMode && <DashboardViewTabs editMode disabled={locked} />}
        <DashboardViewFilterBar editMode={editMode} disabled={locked} />
      </div>

      {editMode && (
        <DashboardEditBar hiddenCount={layout.hidden.length} disabled={locked} onOpenCatalog={() => setCatalogOpen(true)} />
      )}

      {/* Dimmed while the widgets still show the previous view's or filter's data. */}
      <div
        data-testid="dashboard-widgets"
        id={tabPanel ? DASHBOARD_VIEW_PANEL_ID : undefined}
        role={tabPanel ? 'tabpanel' : undefined}
        aria-labelledby={tabPanel ? viewTabId(activeViewId) : undefined}
        aria-busy={isPlaceholderData || undefined}
        className={`transition-opacity ${isPlaceholderData ? 'opacity-60' : ''}`}
      >
        {layout.visible.length === 0 ? (
          <EmptyViewState
            editMode={editMode}
            disabled={locked}
            onAdd={() => {
              setEditMode(true);
              setCatalogOpen(true);
            }}
          />
        ) : (
          /* Workflow widgets of this view share one request and one run-completed subscription. */
          <WorkflowWidgetProvider widgets={layout.visible}>
            <DashboardGrid
              widgets={layout.visible}
              editMode={editMode}
              disabled={locked}
              onMove={layout.move}
              onHide={(instanceId) => layout.setHidden(instanceId, true)}
              onResize={layout.setSize}
              onRemove={layout.remove}
            />
          </WorkflowWidgetProvider>
        )}
      </div>

      <WidgetCatalogDialog
        open={editMode && catalogOpen}
        onClose={() => setCatalogOpen(false)}
        available={layout.available}
        visible={layout.visible}
        hidden={layout.hidden}
        canAppend={layout.canAppend}
        disabled={locked}
        onAdd={layout.add}
        onShow={(instanceId) => layout.setHidden(instanceId, false)}
      />

      <LayoutHistoryDialog
        open={historyOpen}
        onClose={() => setHistoryOpen(false)}
        busy={sync.busy}
        onRestore={(versionId) => void sync.restoreVersion(versionId)}
        onAdoptView={(versionId, viewId) => void sync.adoptView(versionId, viewId)}
      />
    </div>
  );
}
