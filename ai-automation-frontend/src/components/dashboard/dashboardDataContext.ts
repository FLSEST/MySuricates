import { createContext, useContext } from 'react';
import type { DashboardData } from '../../types';

/** Time range of the learning dashboard when a view has no filter (same default as useDashboard()). */
export const DASHBOARD_DEFAULT_DAYS = 30;

export interface DashboardDataValue {
  data: DashboardData | undefined;
  isLoading: boolean;
  error: Error | null;
  refetch: () => void;
  /** Time range the data covers. */
  days: number;
}

export const DashboardDataContext = createContext<DashboardDataValue | null>(null);

/** Learning-dashboard data of the rendered view — one query per dashboard, shared by all widgets. */
export function useDashboardData(): DashboardDataValue {
  const value = useContext(DashboardDataContext);
  if (!value) throw new Error('useDashboardData must be used inside a DashboardDataProvider');
  return value;
}
