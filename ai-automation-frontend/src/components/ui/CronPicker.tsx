import { useState, useEffect } from 'react';
import { useSyncedState } from '../../hooks/useSyncedState';
import { Input } from './Input';
import { useI18nStore, useT } from '../../i18n';
import { formatTimestamp } from '../../utils/localeFormat';
import { useCronPreview } from '../../hooks/useSchedules';
import { parseCronToState, stateToCron, type CronFrequency, type CronState } from '../../utils/cronSchedule';

interface CronPickerProps {
  value: string;
  onChange: (cron: string) => void;
  label?: string;
  /** Simple mode: no "Advanced" tab and no raw cron line — only the frequency, the time and the next runs. */
  simple?: boolean;
}

const WEEKDAY_KEYS = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'] as const;
const WEEKDAY_CRON_VALUES = [1, 2, 3, 4, 5, 6, 0]; // cron: 0=Sun, 1=Mon, ...

export function CronPicker({ value, onChange, label, simple = false }: CronPickerProps) {
  const t = useT();
  const locale = useI18nStore((s) => s.locale);
  // Both follow the external value: re-derived whenever `value` changes, editable in between
  const [state, setState] = useSyncedState(value, parseCronToState);
  const [rawValue, setRawValue] = useSyncedState(value, (v) => v);
  const [debouncedCron, setDebouncedCron] = useState(value);

  // Debounce cron for preview API call
  useEffect(() => {
    const timer = setTimeout(() => setDebouncedCron(rawValue), 400);
    return () => clearTimeout(timer);
  }, [rawValue]);

  const { data: previewData } = useCronPreview(debouncedCron);

  const updateState = (update: Partial<CronState>) => {
    const newState = { ...state, ...update };
    setState(newState);
    if (newState.frequency !== 'advanced') {
      const cron = stateToCron(newState);
      setRawValue(cron);
      onChange(cron);
    }
  };

  const toggleWeekday = (day: number) => {
    const newDays = state.weekdays.includes(day)
      ? state.weekdays.filter((d) => d !== day)
      : [...state.weekdays, day];
    updateState({ weekdays: newDays });
  };

  const frequencies: { key: CronFrequency; label: string }[] = [
    { key: 'daily', label: t.schedules.daily },
    { key: 'weekly', label: t.schedules.weekly },
    { key: 'monthly', label: t.schedules.monthly },
    ...(simple ? [] : [{ key: 'advanced' as const, label: t.schedules.advanced }]),
  ];

  return (
    <div className="space-y-3">
      {label && <label className="block text-sm font-medium text-text-secondary">{label}</label>}

      {/* Frequency tabs */}
      <div className="flex rounded-lg border border-border overflow-hidden">
        {frequencies.map(({ key, label: freqLabel }) => (
          <button
            key={key}
            type="button"
            onClick={() => updateState({ frequency: key })}
            className={`flex-1 px-3 py-2 text-xs font-medium transition-colors ${
              state.frequency === key
                ? 'bg-accent text-text-inverse'
                : 'bg-surface text-text-secondary hover:bg-surface-raised'
            }`}
          >
            {freqLabel}
          </button>
        ))}
      </div>

      {state.frequency === 'advanced' ? (
        <Input
          label={t.schedules.cronExpression}
          value={rawValue}
          onChange={(e) => {
            setRawValue(e.target.value);
            onChange(e.target.value);
          }}
          placeholder="0 0 0 * * *"
          hint={t.schedules.cronHint}
        />
      ) : (
        <div className="space-y-3">
          {/* Time picker */}
          <div className="flex items-center gap-2">
            <div className="flex-1">
              <Input
                label={t.schedules.time}
                type="time"
                value={`${state.hour.padStart(2, '0')}:${state.minute.padStart(2, '0')}`}
                onChange={(e) => {
                  const timeParts = e.target.value.split(':');
                  updateState({ hour: String(parseInt(timeParts[0] ?? '0', 10)), minute: String(parseInt(timeParts[1] ?? '0', 10)) });
                }}
              />
            </div>
          </div>

          {/* Weekly: weekday checkboxes */}
          {state.frequency === 'weekly' && (
            <div>
              <label className="block text-sm font-medium text-text-secondary mb-1.5">
                {t.schedules.frequency}
              </label>
              <div className="flex gap-1.5">
                {WEEKDAY_KEYS.map((dayKey, idx) => {
                  const cronValue = WEEKDAY_CRON_VALUES[idx]!;
                  const isSelected = state.weekdays.includes(cronValue);
                  return (
                    <button
                      key={dayKey}
                      type="button"
                      onClick={() => toggleWeekday(cronValue)}
                      className={`w-10 h-10 rounded-lg text-xs font-medium transition-colors ${
                        isSelected
                          ? 'bg-accent text-text-inverse'
                          : 'bg-surface border border-border text-text-secondary hover:bg-surface-raised'
                      }`}
                    >
                      {t.schedules.weekdays[dayKey]}
                    </button>
                  );
                })}
              </div>
            </div>
          )}

          {/* Monthly: day of month */}
          {state.frequency === 'monthly' && (
            <Input
              label={t.schedules.dayOfMonth}
              type="number"
              min={1}
              max={31}
              value={state.dayOfMonth}
              onChange={(e) => updateState({ dayOfMonth: e.target.value })}
            />
          )}

          {/* Preview */}
          {!simple && (
            <div className="flex items-center gap-2 p-2.5 rounded-lg bg-surface text-xs">
              <span className="text-text-tertiary">Cron:</span>
              <code className="font-mono text-text">{stateToCron(state)}</code>
            </div>
          )}
        </div>
      )}

      {/* Next runs preview */}
      {previewData?.runs && previewData.runs.length > 0 && (
        <div className="p-2.5 rounded-lg bg-surface-secondary border border-border text-xs space-y-1">
          <span className="text-text-tertiary font-medium">
            {previewData.pausedUntil
              ? t.schedules.nextRunsAfterPause.replace('{until}', () => formatTimestamp(previewData.pausedUntil!, locale))
              : t.schedules.nextRunsPreview}:
          </span>
          {previewData.runs.map((run) => (
            <div key={run} className="text-text-secondary pl-2">
              {formatTimestamp(run, locale, { weekday: 'short', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
