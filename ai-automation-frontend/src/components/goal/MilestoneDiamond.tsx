import type { MilestoneState } from '../../types';

/** Farbe nach Zustand: erreicht grün, überfällig rot, bald fällig gelb, offen neutral. */
const TONE: Record<MilestoneState, string> = {
  OPEN: 'border-text-secondary bg-surface-raised',
  DUE_SOON: 'border-warning bg-warning',
  OVERDUE: 'border-danger bg-danger',
  REACHED: 'border-success bg-success',
};

const SIZE = { sm: 'h-2 w-2', md: 'h-2.5 w-2.5', lg: 'h-3 w-3' } as const;

/** Die Raute eines Meilensteins – in der Meilenstein-Zeile, der Gliederung, auf der Zielkarte und in „Braucht dich“. */
export function MilestoneDiamond({ state, size = 'md' }: { state: MilestoneState; size?: keyof typeof SIZE }) {
  return <span aria-hidden="true" className={`inline-block shrink-0 rotate-45 rounded-[2px] border-2 ${SIZE[size]} ${TONE[state]}`} />;
}
