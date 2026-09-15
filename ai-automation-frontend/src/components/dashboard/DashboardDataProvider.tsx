import type { ReactNode } from 'react';
import { useDashboard } from '../../hooks/useLearning';
import type { DashboardViewFilter } from './dashboardLayout';
import { DASHBOARD_DEFAULT_DAYS, DashboardDataContext } from './dashboardDataContext';

interface DashboardDataProviderProps {
  /** Filter of the rendered view; undefined = all projects, default time range. */
  filter: DashboardViewFilter | undefined;
  children: ReactNode;
}

/**
 * Subscribes to /learning/dashboard once for the rendered view and hands the result to every
 * widget: one refetch timer per dashboard instead of one per widget (spec E4).
 */
export function DashboardDataProvider({ filter, children }: DashboardDataProviderProps) {
  const days = filter?.days ?? DASHBOARD_DEFAULT_DAYS;
  const { data, isLoading, error, refetch } = useDashboard(days, filter?.projectId);
  const value = { data, isLoading, error, refetch: () => void refetch(), days };
  return <DashboardDataContext.Provider value={value}>{children}</DashboardDataContext.Provider>;
}
