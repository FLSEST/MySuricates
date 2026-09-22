import { LayoutGrid, Plus } from 'lucide-react';
import { Button } from '../ui';
import { useT } from '../../i18n';

interface EmptyViewStateProps {
  editMode: boolean;
  disabled: boolean;
  /** Opens the widget catalog (and edit mode, when it is not on yet). */
  onAdd: () => void;
}

/** Stands in for the widget grid while the shown view has no visible widget — a new view starts like this. */
export function EmptyViewState({ editMode, disabled, onAdd }: EmptyViewStateProps) {
  const t = useT();
  return (
    <div className="flex flex-col items-center gap-2 rounded-xl border border-dashed border-border px-6 py-12 text-center">
      <LayoutGrid className="h-8 w-8 text-text-tertiary" aria-hidden="true" />
      <p className="text-sm font-medium text-text">{t.dashboard.emptyView.title}</p>
      <p className="max-w-md text-sm text-text-secondary">
        {editMode ? t.dashboard.emptyView.hintEdit : t.dashboard.emptyView.hint}
      </p>
      <Button variant="secondary" size="sm" icon={<Plus className="h-3.5 w-3.5" />} onClick={onAdd} disabled={disabled} className="mt-2">
        {t.dashboard.catalog.open}
      </Button>
    </div>
  );
}
