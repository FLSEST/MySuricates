import { LayoutGrid } from 'lucide-react';
import { Button } from '../ui';
import { useT } from '../../i18n';

interface DashboardEditBarProps {
  hiddenCount: number;
  /** Locks the catalog (e.g. while a server version is applied). */
  disabled: boolean;
  onOpenCatalog: () => void;
}

/** Hint strip of the edit mode with the way into the widget catalog. */
export function DashboardEditBar({ hiddenCount, disabled, onOpenCatalog }: DashboardEditBarProps) {
  const t = useT();
  return (
    <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-dashed border-accent/40 bg-accent/5 px-4 py-3">
      <p className="min-w-0 text-sm text-text-secondary">
        {t.dashboard.editHint}
        {hiddenCount > 0 && (
          <span className="text-text-tertiary"> {t.dashboard.catalog.hiddenCount.replace('{count}', () => String(hiddenCount))}</span>
        )}
      </p>
      <Button variant="secondary" size="sm" icon={<LayoutGrid className="w-3.5 h-3.5" />} disabled={disabled} onClick={onOpenCatalog}>
        {t.dashboard.catalog.open}
      </Button>
    </div>
  );
}
