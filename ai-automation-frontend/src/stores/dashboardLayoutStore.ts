import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import {
  createDefaultLayout,
  sanitizeLayout,
  type DashboardLayout,
  type WidgetPlacement,
} from '../components/dashboard/dashboardLayout';

interface DashboardLayoutState extends DashboardLayout {
  /** Replaces the widget list of the active view. */
  setActiveViewWidgets: (widgets: WidgetPlacement[]) => void;
  /** Back to the default layout for the active view. */
  resetActiveView: () => void;
}

function updateActiveView(state: DashboardLayout, widgets: WidgetPlacement[]): Pick<DashboardLayout, 'views'> {
  return { views: state.views.map((v) => (v.id === state.activeViewId ? { ...v, widgets } : v)) };
}

export const useDashboardLayoutStore = create<DashboardLayoutState>()(
  persist(
    (set) => ({
      ...createDefaultLayout(),
      setActiveViewWidgets: (widgets) => set((state) => updateActiveView(state, widgets)),
      resetActiveView: () => set((state) => updateActiveView(state, [])),
    }),
    {
      name: 'dashboard-layout',
      // Bump together with a `migrate` when the stored format changes.
      version: 1,
      partialize: ({ activeViewId, views }) => ({ activeViewId, views }),
      // Per-browser data — never trust its shape.
      merge: (persisted, current) => ({ ...current, ...sanitizeLayout(persisted) }),
    },
  ),
);
