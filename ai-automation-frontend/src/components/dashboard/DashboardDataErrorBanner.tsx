import { RotateCcw } from 'lucide-react';
import { Button } from '../ui';
import { useT } from '../../i18n';
import { useDashboardData } from './dashboardDataContext';

/** Page-level notice when the shared learning-dashboard query fails; the other widgets stay usable. */
export function DashboardDataErrorBanner() {
  const t = useT();
  const { error, refetch } = useDashboardData();
  if (!error) return null;

  return (
    <div
      role="alert"
      data-testid="dashboard-data-error"
      className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-danger/40 bg-danger-subtle px-4 py-3"
    >
      <p className="min-w-0 text-sm text-text">
        {t.dashboard.dataUnavailable} <span className="text-text-secondary">{error.message}</span>
      </p>
      <Button variant="secondary" size="sm" icon={<RotateCcw className="w-3.5 h-3.5" />} onClick={refetch}>
        {t.common.retry}
      </Button>
    </div>
  );
}
