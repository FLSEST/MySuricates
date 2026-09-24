import { ArrowDown, ArrowUp, Minus } from 'lucide-react';
import { useT } from '../../i18n';
import type { WorkflowResult, WorkflowResultTrend } from './workflowWidgetContract';

const TREND_ICON: Record<WorkflowResultTrend, typeof ArrowUp> = {
  UP: ArrowUp,
  DOWN: ArrowDown,
  FLAT: Minus,
};

/**
 * The body of a validated workflow result (spec E3): KPIs, points and summary. Shared by the Pro
 * dashboard widget and the Simple run page. Every value is rendered as a React text node — no
 * markup, no markdown, no links, no images; trend is an enum that only picks an icon.
 */
export function WorkflowResultContent({ result }: { result: WorkflowResult }) {
  const texts = useT().dashboard.workflowWidgets;
  const trendLabels: Record<WorkflowResultTrend, string> = {
    UP: texts.trendUp, DOWN: texts.trendDown, FLAT: texts.trendFlat,
  };
  return (
    <>
      {result.kpis.length > 0 && (
        <dl className="grid grid-cols-[1fr_auto] gap-x-3 gap-y-1 text-xs">
          {result.kpis.map((kpi, index) => {
            const TrendIcon = kpi.trend ? TREND_ICON[kpi.trend] : null;
            return (
              <div key={`${kpi.label}-${index}`} className="contents">
                <dt className="min-w-0 break-words text-text-secondary">{kpi.label}</dt>
                <dd className="flex items-center justify-end gap-1 break-words font-medium text-text">
                  {kpi.value}
                  {TrendIcon && kpi.trend && (
                    <TrendIcon className="h-3 w-3 shrink-0 text-text-tertiary" aria-label={trendLabels[kpi.trend]} />
                  )}
                </dd>
              </div>
            );
          })}
        </dl>
      )}

      {result.items.length > 0 && (
        // A CSS list marker, not a literal "• " text node: the latter would merge with the item text
        // into one DOM text node and break exact text matching (both in tests and for assistive tech).
        <ul className="list-disc space-y-1 pl-4 text-xs text-text-secondary">
          {result.items.map((item, index) => (
            <li key={`${index}-${item}`} className="break-words">{item}</li>
          ))}
        </ul>
      )}

      {result.summary && <p className="text-xs text-text-secondary break-words">{result.summary}</p>}
    </>
  );
}
