import { useState, type ReactNode } from 'react';
import { useDashboard } from '../../hooks/useLearning';
import type { DashboardViewFilter } from './dashboardLayout';
import { DASHBOARD_DEFAULT_DAYS, DashboardDataContext, type DashboardDataValue } from './dashboardDataContext';

interface DashboardDataProviderProps {
  /** Filter of the rendered view; undefined = all projects, default time range. */
  filter: DashboardViewFilter | undefined;
  children: ReactNode;
}

const NO_FILTER: DashboardViewFilter = Object.freeze({});

/**
 * Subscribes to /learning/dashboard once for the rendered view and hands the result to every
 * widget: one refetch timer per dashboard instead of one per widget (spec E4). On a view or filter
 * switch the previous result stays visible until the new one arrives (no skeleton flash); widgets
 * that name the time range use `dataDays`, so old numbers never carry the new range's label.
 */
export function DashboardDataProvider({ filter, children }: DashboardDataProviderProps) {
  const days = filter?.days ?? DASHBOARD_DEFAULT_DAYS;
  const { data, isLoading, isPlaceholderData, error, refetch } = useDashboard(days, filter?.projectId, { keepPreviousData: true });
  // Range of the last result that was not a placeholder — adjusted during render, not in an effect.
  const [settledDays, setSettledDays] = useState(days);
  if (!isPlaceholderData && settledDays !== days) setSettledDays(days);

  const value: DashboardDataValue = {
    data,
    isLoading,
    error,
    refetch: () => void refetch(),
    filter: filter ?? NO_FILTER,
    days,
    isPlaceholderData,
    dataDays: isPlaceholderData ? settledDays : days,
  };
  return <DashboardDataContext.Provider value={value}>{children}</DashboardDataContext.Provider>;
}
