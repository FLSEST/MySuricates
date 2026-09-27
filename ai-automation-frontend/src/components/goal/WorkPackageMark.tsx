import type { WorkPackageState } from '../../types';

/** Farbe nach Zustand wie bei den Rauten: abgeschlossen grün, überfällig rot, endet bald gelb, offen neutral. */
const TONE: Record<WorkPackageState, string> = {
  OPEN: 'border-text-secondary bg-surface-raised',
  DUE_SOON: 'border-warning bg-warning/40',
  OVERDUE: 'border-danger bg-danger/40',
  REACHED: 'border-success bg-success',
};

/** Das Zeichen eines Arbeitspakets – ein kleiner Balken, wie auf dem Zeitstrahl (c60bf914). */
export function WorkPackageMark({ state }: { state: WorkPackageState }) {
  return <span aria-hidden="true" className={`inline-block h-2 w-3.5 shrink-0 rounded-[2px] border-2 ${TONE[state]}`} />;
}
