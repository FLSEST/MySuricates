import { createContext, useContext } from 'react';
import type { DashboardData } from '../../types';
import type { DashboardViewFilter } from './dashboardLayout';

/** Time range of the learning dashboard when a view has no filter (same default as useDashboard()). */
export const DASHBOARD_DEFAULT_DAYS = 30;

export interface DashboardDataValue {
  data: DashboardData | undefined;
  isLoading: boolean;
  error: Error | null;
  refetch: () => void;
  /** Filter of the rendered view (empty = none), for widgets that load from their own endpoints. */
  filter: DashboardViewFilter;
  /** Time range the page asks for: the filter's days or the default. */
  days: number;
  /** True while `data` still belongs to the previous filter — right after switching views or filters. */
  isPlaceholderData: boolean;
  /** Time range the shown `data` covers; differs from `days` only while isPlaceholderData. */
  dataDays: number;
}

export const DashboardDataContext = createContext<DashboardDataValue | null>(null);

/** Learning-dashboard data of the rendered view — one query per dashboard, shared by all widgets. */
export function useDashboardData(): DashboardDataValue {
  const value = useContext(DashboardDataContext);
  if (!value) throw new Error('useDashboardData must be used inside a DashboardDataProvider');
  return value;
}
