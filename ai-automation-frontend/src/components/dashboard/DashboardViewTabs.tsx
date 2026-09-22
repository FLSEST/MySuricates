import { useState } from 'react';
import { Copy, LayoutTemplate, Pencil, Plus, Trash2 } from 'lucide-react';
import { Button } from '../ui';
import { useT } from '../../i18n';
import { selectActiveView, useDashboardLayoutStore } from '../../stores/dashboardLayoutStore';
import { LAYOUT_LIMITS, type DashboardView } from './dashboardLayout';
import { templateWidgets } from './dashboardWidgets';
import { DASHBOARD_TEMPLATES } from './dashboardTemplates';
import { nextViewName, viewLabels } from './dashboardViews';
import { DashboardMenu } from './DashboardMenu';
import { DashboardViewEditStrip } from './DashboardViewEditStrip';
import { DashboardViewTabList } from './DashboardViewTabList';
import { DASHBOARD_WIDGETS } from './widgetRegistry';

interface DashboardViewTabsProps {
  editMode: boolean;
  /** Locks every change to the views (e.g. while a server version is applied); switching stays possible. */
  disabled: boolean;
}

/**
 * The views above the widget grid, one per view (spec E4): a tab list for switching, and in edit
 * mode a sortable list with rename, duplicate, delete, "add view" and "add view from template".
 * Names are user text and only ever rendered as React text.
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
  const numbered = (n: number) => t.dashboard.views.numbered.replace('{n}', () => String(n));
  const labels = viewLabels(views, t.dashboard.views.defaultName, numbered);

  // One view outside edit mode: no tab strip, the dashboard looks as it did before views existed.
  if (!editMode && views.length < 2) return null;

  const labelOf = (view: DashboardView) => labels.get(view.id) ?? (view.name || t.dashboard.views.defaultName);

  if (!editMode) {
    return <DashboardViewTabList views={views} activeViewId={activeView?.id} labelOf={labelOf} onSelect={setActiveView} />;
  }

  const atLimit = views.length >= LAYOUT_LIMITS.views;
  const limitHint = t.dashboard.views.limitReached.replace('{max}', () => String(LAYOUT_LIMITS.views));
  const addFromTemplate = (templateId: string) => {
    const template = DASHBOARD_TEMPLATES.find((candidate) => candidate.id === templateId);
    if (template) addView(template.name(t), templateWidgets(template.widgetIds, DASHBOARD_WIDGETS));
  };
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
          <DashboardMenu
            variant="button"
            label={t.dashboard.templates.add}
            icon={<LayoutTemplate className="h-3.5 w-3.5" />}
            items={DASHBOARD_TEMPLATES.map((template) => ({ id: template.id, label: template.name(t) }))}
            disabled={disabled || atLimit}
            onSelect={addFromTemplate}
          />
          <Button
            variant="secondary"
            size="sm"
            icon={<Plus className="h-3.5 w-3.5" />}
            disabled={disabled || atLimit}
            onClick={() => {
              // A new view starts empty (every standard widget hidden, the catalog shows them again)
              // and under a name no other view shows — before, it read "Overview" twice (565adc37).
              const id = addView(nextViewName(views, t.dashboard.views.defaultName, numbered), templateWidgets([], DASHBOARD_WIDGETS));
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
