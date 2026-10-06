import { Bot, UserRound } from 'lucide-react';
import { useT } from '../../i18n';
import type { RatedBy, ReasoningEffortLevel, TodoComplexity } from '../../types';

/** Complexity S–XL; neutral colour, since it says nothing about human or AI. */
export function ComplexityChip({ value }: { value: TodoComplexity | null }) {
  const t = useT();
  if (!value) return null;
  return (
    <span
      title={`${t.goals.complexity}: ${value}`}
      className="px-1.5 py-0.5 rounded text-[11px] font-semibold border border-border-strong text-text-secondary"
    >
      {value}
    </span>
  );
}

/** Recommended effort — in the AI colour, because it describes how a model should work. */
export function EffortChip({ value }: { value: ReasoningEffortLevel | null }) {
  const t = useT();
  if (!value) return null;
  return (
    <span title={`${t.goals.effort}: ${value}`} className="px-1.5 py-0.5 rounded text-[11px] font-medium bg-ai-subtle text-ai">
      {value}
    </span>
  );
}

/** Who estimated last: AI or human. */
export function RatedByMark({ ratedBy }: { ratedBy: RatedBy | null }) {
  const t = useT();
  if (ratedBy === 'AI') {
    return <Bot className="w-3.5 h-3.5 text-ai shrink-0" aria-label={t.goals.ratedByAi} role="img" />;
  }
  if (ratedBy === 'USER') {
    return <UserRound className="w-3.5 h-3.5 text-human shrink-0" aria-label={t.goals.ratedByUser} role="img" />;
  }
  return null;
}
