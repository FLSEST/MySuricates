import { useState } from 'react';
import { Copy, Pencil, Plus, Trash2 } from 'lucide-react';
import { Button } from '../ui';
import { useT } from '../../i18n';
import { selectActiveView, useDashboardLayoutStore } from '../../stores/dashboardLayoutStore';
import { LAYOUT_LIMITS, type DashboardView } from './dashboardLayout';
import { DashboardViewEditStrip } from './DashboardViewEditStrip';
import { DashboardViewTabList } from './DashboardViewTabList';

interface DashboardViewTabsProps {
  editMode: boolean;
  /** Locks every change to the views (e.g. while a server version is applied); switching stays possible. */
  disabled: boolean;
}

/**
 * The views above the widget grid, one per view (spec E4): a tab list for switching, and in edit
 * mode a sortable list with rename, duplicate, delete and "add view". Names are user text and only
 * ever rendered as React text.
 */
export function DashboardViewTabs({ editMode, disabled }: DashboardViewTabsProps) {
  const t = useT();
  const views = useDashboardLayoutStore((s) => s.views);
  const activeView = useDashboardLayoutStore(selectActiveView);
  const setActiveView = useDashboardLayoutStore((s) => s.setActiveView);
  const addView = useDashboardLayoutStore((s) => s.addView);
  const renameView = useDashboardLayoutStore((s) => s.renameView);
  const duplicateView = useDashboardLayoutStore((s) => s.duplicateView);
  const removeView = useDashboardLayoutStore((s) => s.removeView);
  const moveView = useDashboardLayoutStore((s) => s.moveView);
  const [renamingId, setRenamingId] = useState<string | null>(null);

  // One view outside edit mode: no tab strip, the dashboard looks as it did before views existed.
  if (!editMode && views.length < 2) return null;

  const labelOf = (view: DashboardView) => view.name || t.dashboard.views.defaultName;

  if (!editMode) {
    return <DashboardViewTabList views={views} activeViewId={activeView?.id} labelOf={labelOf} onSelect={setActiveView} />;
  }

  const atLimit = views.length >= LAYOUT_LIMITS.views;
  const limitHint = t.dashboard.views.limitReached.replace('{max}', () => String(LAYOUT_LIMITS.views));
  const confirmRemove = (view: DashboardView) => {
    if (window.confirm(t.dashboard.views.removeConfirm.replace('{name}', () => labelOf(view)))) removeView(view.id);
  };

  return (
    <div className="flex flex-wrap items-center gap-2">
      <DashboardViewEditStrip
        views={views}
        activeViewId={activeView?.id}
        disabled={disabled}
        renamingId={renamingId}
        labelOf={labelOf}
        onSelect={setActiveView}
        onMove={moveView}
        onRename={(viewId, name) => {
          renameView(viewId, name);
          setRenamingId(null);
        }}
        onCancelRename={() => setRenamingId(null)}
      />
      {activeView && (
        <div className="flex shrink-0 items-center gap-1">
          <Button
            variant="ghost"
            size="sm"
            icon={<Pencil className="h-3.5 w-3.5" />}
            disabled={disabled}
            onClick={() => setRenamingId(activeView.id)}
            aria-label={t.dashboard.views.rename}
            title={t.dashboard.views.rename}
          />
          <Button
            variant="ghost"
            size="sm"
            icon={<Copy className="h-3.5 w-3.5" />}
            disabled={disabled || atLimit}
            onClick={() => duplicateView(activeView.id, t.dashboard.views.copyName.replace('{name}', () => labelOf(activeView)))}
            aria-label={t.dashboard.views.duplicate}
            title={atLimit ? limitHint : t.dashboard.views.duplicate}
          />
          <Button
            variant="ghost"
            size="sm"
            icon={<Trash2 className="h-3.5 w-3.5" />}
            disabled={disabled || views.length < 2}
            onClick={() => confirmRemove(activeView)}
            aria-label={t.dashboard.views.remove}
            title={views.length < 2 ? t.dashboard.views.lastView : t.dashboard.views.remove}
          />
          <Button
            variant="secondary"
            size="sm"
            icon={<Plus className="h-3.5 w-3.5" />}
            disabled={disabled || atLimit}
            onClick={() => {
              const id = addView();
              if (id) setRenamingId(id);
            }}
            aria-label={t.dashboard.views.add}
            title={atLimit ? limitHint : t.dashboard.views.add}
          />
        </div>
      )}
    </div>
  );
}
