import { useMemo } from 'react';
import { useSystemModules } from './useModules';
import { useDashboardLayoutStore } from '../stores/dashboardLayoutStore';
import {
  moveWidget, normalizeWidgets, setWidgetHidden, type WidgetInstance,
} from '../components/dashboard/dashboardLayout';
import { DASHBOARD_WIDGETS, type DashboardWidgetDef } from '../components/dashboard/widgetRegistry';

export interface ResolvedWidget {
  def: DashboardWidgetDef;
  instance: WidgetInstance;
}

const WIDGETS_BY_ID = new Map(DASHBOARD_WIDGETS.map((w) => [w.id, w]));

/** The active dashboard view resolved against the widget registry and the enabled modules. */
export function useDashboardLayout() {
  const { data: modules } = useSystemModules();
  const views = useDashboardLayoutStore((s) => s.views);
  const activeViewId = useDashboardLayoutStore((s) => s.activeViewId);
  const setActiveViewWidgets = useDashboardLayoutStore((s) => s.setActiveViewWidgets);
  const resetActiveView = useDashboardLayoutStore((s) => s.resetActiveView);

  const storedWidgets = (views.find((v) => v.id === activeViewId) ?? views[0])?.widgets;

  // Full list incl. hidden and module-disabled widgets, so edits keep their positions.
  const widgets = useMemo(
    () => normalizeWidgets(storedWidgets ?? [], DASHBOARD_WIDGETS),
    [storedWidgets],
  );

  const { visible, hidden } = useMemo(() => {
    // Optimistic while module status is unknown, like useModuleEnabled.
    const moduleEnabled = (def: DashboardWidgetDef) =>
      !def.module || (modules?.modules.find((m) => m.name === def.module)?.enabled ?? true);
    const available = widgets
      .map((instance) => ({ def: WIDGETS_BY_ID.get(instance.type)!, instance }))
      .filter((w) => moduleEnabled(w.def));
    return {
      visible: available.filter((w) => !w.instance.hidden),
      hidden: available.filter((w) => w.instance.hidden),
    };
  }, [widgets, modules]);

  return {
    visible,
    hidden,
    isCustomized: (storedWidgets?.length ?? 0) > 0,
    move: (activeInstanceId: string, overInstanceId: string) =>
      setActiveViewWidgets(moveWidget(widgets, activeInstanceId, overInstanceId)),
    setHidden: (instanceId: string, isHidden: boolean) =>
      setActiveViewWidgets(setWidgetHidden(widgets, instanceId, isHidden)),
    reset: resetActiveView,
  };
}
