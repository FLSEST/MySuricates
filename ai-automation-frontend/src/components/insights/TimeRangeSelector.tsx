import { useT } from '../../i18n';

const OPTIONS = [7, 14, 30, 90] as const;

interface TimeRangeSelectorProps {
  value: number;
  onChange: (days: number) => void;
}

export function TimeRangeSelector({ value, onChange }: TimeRangeSelectorProps) {
  const t = useT();
  const labels: Record<number, string> = { 7: t.insights.days7, 14: t.insights.days14, 30: t.insights.days30, 90: t.insights.days90 };

  return (
    <div className="flex gap-1 bg-surface rounded-lg p-0.5">
      {OPTIONS.map((d) => (
        <button
          key={d}
          onClick={() => onChange(d)}
          className={`px-3 py-1 text-xs font-medium rounded-md transition-colors ${
            value === d
              ? 'bg-accent text-text-inverse'
              : 'text-text-secondary hover:text-text hover:bg-surface-raised'
          }`}
        >
          {labels[d]}
        </button>
      ))}
    </div>
  );
}
