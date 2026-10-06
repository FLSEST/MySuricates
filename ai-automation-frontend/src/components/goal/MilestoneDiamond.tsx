import type { MilestoneState } from '../../types';

/** Colour by state: reached green, overdue red, due soon yellow, open neutral. */
const TONE: Record<MilestoneState, string> = {
  OPEN: 'border-text-secondary bg-surface-raised',
  DUE_SOON: 'border-warning bg-warning',
  OVERDUE: 'border-danger bg-danger',
  REACHED: 'border-success bg-success',
};

const SIZE = { sm: 'h-2 w-2', md: 'h-2.5 w-2.5', lg: 'h-3 w-3' } as const;

/** A milestone's diamond — in the milestone row, the outline, on the goal card and in „Braucht dich“. */
export function MilestoneDiamond({ state, size = 'md' }: { state: MilestoneState; size?: keyof typeof SIZE }) {
  return <span aria-hidden="true" className={`inline-block shrink-0 rotate-45 rounded-[2px] border-2 ${SIZE[size]} ${TONE[state]}`} />;
}
