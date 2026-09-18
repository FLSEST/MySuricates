import { useMemo } from 'react';
import { useSystemModules } from './useModules';
import { selectActiveView, useDashboardLayoutStore } from '../stores/dashboardLayoutStore';
import {
  LAYOUT_LIMITS, type WidgetConfig, type WidgetInstance, type WidgetSize,
} from '../components/dashboard/dashboardLayout';
import {
  addWidgetToView, isRemovableWidget, moveWidget, normalizeWidgets, removeWidgetInstance, setWidgetHidden, setWidgetSize,
} from '../components/dashboard/dashboardWidgets';
import { DASHBOARD_WIDGETS, type DashboardWidgetDef } from '../components/dashboard/widgetRegistry';

export interface ResolvedWidget {
  def: DashboardWidgetDef;
  instance: WidgetInstance;
}

const WIDGETS_BY_ID = new Map(DASHBOARD_WIDGETS.map((w) => [w.id, w]));

/** The active dashboard view resolved against the widget registry and the enabled modules. */
export function useDashboardLayout() {
  const { data: modules } = useSystemModules();
  const storedWidgets = useDashboardLayoutStore((s) => selectActiveView(s)?.widgets);
  const setActiveViewWidgets = useDashboardLayoutStore((s) => s.setActiveViewWidgets);
  const resetActiveView = useDashboardLayoutStore((s) => s.resetActiveView);

  // Full list incl. hidden and module-disabled widgets, so edits keep their positions.
  const widgets = useMemo(
    () => normalizeWidgets(storedWidgets ?? [], DASHBOARD_WIDGETS),
    [storedWidgets],
  );

  const { available, visible, hidden } = useMemo(() => {
    // Optimistic while module status is unknown, like useModuleEnabled.
    const moduleEnabled = (def: DashboardWidgetDef) =>
      !def.module || (modules?.modules.find((m) => m.name === def.module)?.enabled ?? true);
    const resolved = widgets
      .map((instance) => ({ def: WIDGETS_BY_ID.get(instance.type)!, instance }))
      .filter((w) => moduleEnabled(w.def));
    return {
      available: DASHBOARD_WIDGETS.filter(moduleEnabled),
      visible: resolved.filter((w) => !w.instance.hidden),
      hidden: resolved.filter((w) => w.instance.hidden),
    };
  }, [widgets, modules]);

  // Only real edits reach the store, so a no-op never counts as a local change.
  const apply = (next: WidgetInstance[]) => {
    if (next !== widgets) setActiveViewWidgets(next);
  };

  return {
    visible,
    hidden,
    /** Widgets of enabled modules in registry order — what the catalog offers. */
    available,
    isCustomized: (storedWidgets?.length ?? 0) > 0,
    /** False at LAYOUT_LIMITS.widgetsPerView: no further widget can be appended (hidden ones can still be shown). */
    canAppend: widgets.length < LAYOUT_LIMITS.widgetsPerView,
    move: (activeInstanceId: string, overInstanceId: string) =>
      apply(moveWidget(widgets, activeInstanceId, overInstanceId)),
    setHidden: (instanceId: string, isHidden: boolean) =>
      apply(setWidgetHidden(widgets, instanceId, isHidden)),
    /** Choosing the widget's default size removes the override. */
    setSize: (instanceId: string, size: WidgetSize | undefined) => {
      const def = WIDGETS_BY_ID.get(widgets.find((w) => w.instanceId === instanceId)?.type ?? '');
      apply(setWidgetSize(widgets, instanceId, size === def?.size ? undefined : size));
    },
    /** Removes catalog singletons and multi instances; standard singletons can only be hidden. */
    remove: (instanceId: string) => {
      const def = WIDGETS_BY_ID.get(widgets.find((w) => w.instanceId === instanceId)?.type ?? '');
      if (def && isRemovableWidget(def)) apply(removeWidgetInstance(widgets, instanceId));
    },
    /** Shows a hidden singleton, adds a missing one, or adds a multi instance with this config. */
    add: (type: string, config?: WidgetConfig) => apply(addWidgetToView(widgets, DASHBOARD_WIDGETS, type, config)),
    reset: resetActiveView,
  };
}
