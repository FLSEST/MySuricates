import { CheckCircle2, HelpCircle } from 'lucide-react';
import { useT } from '../../i18n';

/**
 * „n offen · m entschieden“ (68a5e24e): offene Fragen in `text-human`, Entscheidungen in
 * `text-success`. `icons` ist die Kurzform für enge Zeilen (❓ n ✔ m), `words` steht im Fortschritt.
 * Vorgelesen wird immer der ganze Satz; was null ist, fehlt.
 */
export function DecisionCounts({ openQuestions, decided, variant = 'icons', className = '' }: {
  openQuestions: number;
  decided: number;
  variant?: 'icons' | 'words';
  className?: string;
}) {
  const td = useT().goals.decision;
  if (openQuestions === 0 && decided === 0) return null;
  const long = [
    openQuestions > 0 ? td.openLong.replace('{n}', String(openQuestions)) : '',
    decided > 0 ? td.decidedCount.replace('{n}', String(decided)) : '',
  ].filter(Boolean).join(', ');
  const icon = variant === 'words' ? 'h-3.5 w-3.5' : 'h-3 w-3';
  return (
    <span className={`inline-flex shrink-0 items-center gap-x-2 tabular-nums ${className}`} data-testid="decision-counts" title={long}>
      <span className="sr-only">{long}</span>
      {openQuestions > 0 && (
        <span className="inline-flex items-center gap-0.5 text-human" aria-hidden="true">
          <HelpCircle className={icon} />
          {variant === 'words' ? td.openCount.replace('{n}', String(openQuestions)) : openQuestions}
        </span>
      )}
      {openQuestions > 0 && decided > 0 && variant === 'words' && <span aria-hidden="true" className="text-text-tertiary">·</span>}
      {decided > 0 && (
        <span className="inline-flex items-center gap-0.5 text-success" aria-hidden="true">
          <CheckCircle2 className={icon} />
          {variant === 'words' ? td.decidedCount.replace('{n}', String(decided)) : decided}
        </span>
      )}
    </span>
  );
}
