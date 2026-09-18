import type { KeyboardEvent } from 'react';
import { useT } from '../../i18n';
import type { DashboardView } from './dashboardLayout';
import { DASHBOARD_VIEW_PANEL_ID, useScrollIntoViewWhenActive, viewTabId } from './viewTabA11y';

interface DashboardViewTabListProps {
  views: DashboardView[];
  activeViewId: string | undefined;
  labelOf: (view: DashboardView) => string;
  onSelect: (viewId: string) => void;
}

function ViewTab({ view, label, active, onSelect }: { view: DashboardView; label: string; active: boolean; onSelect: (viewId: string) => void }) {
  const ref = useScrollIntoViewWhenActive<HTMLButtonElement>(active);
  return (
    <button
      ref={ref}
      type="button"
      role="tab"
      id={viewTabId(view.id)}
      aria-selected={active}
      aria-controls={DASHBOARD_VIEW_PANEL_ID}
      // Roving tab stop: Tab enters the strip at the shown view, arrow keys move between views.
      tabIndex={active ? 0 : -1}
      data-view-id={view.id}
      onClick={() => onSelect(view.id)}
      className={`max-w-48 shrink-0 truncate rounded-lg px-3 py-1.5 text-sm font-medium transition-colors ${
        active ? 'bg-accent text-text-inverse' : 'text-text-secondary hover:bg-surface-raised hover:text-text'
      }`}
    >
      {label}
    </button>
  );
}

/**
 * The view switcher outside edit mode, as an ARIA tab list (spec E4): arrow keys, Home and End
 * select and focus a view (switching is cheap, so activation follows focus). The tabs control the
 * widget area, which Dashboard marks as the tab panel.
 */
export function DashboardViewTabList({ views, activeViewId, labelOf, onSelect }: DashboardViewTabListProps) {
  const t = useT();

  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const current = views.findIndex((v) => v.id === activeViewId);
    const targets: Record<string, number> = { ArrowRight: current + 1, ArrowLeft: current - 1, Home: 0, End: views.length - 1 };
    const target = targets[event.key];
    if (target === undefined) return;
    event.preventDefault();
    const view = views[(target + views.length) % views.length];
    if (!view) return;
    onSelect(view.id);
    document.getElementById(viewTabId(view.id))?.focus();
  };

  return (
    <div
      role="tablist"
      aria-label={t.dashboard.views.label}
      onKeyDown={handleKeyDown}
      // Scrolls sideways inside itself, so many or long tabs never widen the page.
      className="flex min-w-0 flex-1 items-center gap-1 overflow-x-auto py-1"
    >
      {views.map((view) => (
        <ViewTab key={view.id} view={view} label={labelOf(view)} active={view.id === activeViewId} onSelect={onSelect} />
      ))}
    </div>
  );
}
