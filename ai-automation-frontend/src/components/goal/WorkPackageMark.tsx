import type { WorkPackageState } from '../../types';

/** Colour by state as with the diamonds: completed green, overdue red, ends soon yellow, open neutral. */
const TONE: Record<WorkPackageState, string> = {
  OPEN: 'border-text-secondary bg-surface-raised',
  DUE_SOON: 'border-warning bg-warning/40',
  OVERDUE: 'border-danger bg-danger/40',
  REACHED: 'border-success bg-success',
};

/** The mark of a work package - a small bar, as on the timeline (c60bf914). */
export function WorkPackageMark({ state }: { state: WorkPackageState }) {
  return <span aria-hidden="true" className={`inline-block h-2 w-3.5 shrink-0 rounded-[2px] border-2 ${TONE[state]}`} />;
}
