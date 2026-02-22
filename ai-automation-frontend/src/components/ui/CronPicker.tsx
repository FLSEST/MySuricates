import { useState, useEffect } from 'react';
import { Input } from './Input';
import { useT } from '../../i18n';

type Frequency = 'daily' | 'weekly' | 'monthly' | 'advanced';

interface CronPickerProps {
  value: string;
  onChange: (cron: string) => void;
  label?: string;
}

interface CronState {
  frequency: Frequency;
  hour: string;
  minute: string;
  weekdays: number[];
  dayOfMonth: string;
}

const WEEKDAY_KEYS = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'] as const;
const WEEKDAY_CRON_VALUES = [1, 2, 3, 4, 5, 6, 0]; // cron: 0=Sun, 1=Mon, ...

function parseCronToState(cron: string): CronState {
  const parts = cron.trim().split(/\s+/);
  // Spring cron uses 6 fields: second minute hour dayOfMonth month weekday
  // Also accept 5-field (legacy): minute hour dayOfMonth month weekday
  if (parts.length !== 6 && parts.length !== 5) {
    return { frequency: 'advanced', hour: '0', minute: '0', weekdays: [], dayOfMonth: '1' };
  }

  const offset = parts.length === 6 ? 1 : 0; // skip seconds field if 6-field
  const minute = parts[offset]!;
  const hour = parts[offset + 1]!;
  const dayOfMonth = parts[offset + 2]!;
  const month = parts[offset + 3]!;
  const weekday = parts[offset + 4]!;

  // Check if it's a simple daily pattern: MM HH * * *
  if (dayOfMonth === '*' && month === '*' && weekday === '*') {
    return { frequency: 'daily', hour, minute, weekdays: [], dayOfMonth: '1' };
  }

  // Check if it's a weekly pattern: MM HH * * 1,3,5
  if (dayOfMonth === '*' && month === '*' && weekday !== '*') {
    const days = weekday.split(',').map(Number).filter((n) => !isNaN(n));
    return { frequency: 'weekly', hour, minute, weekdays: days, dayOfMonth: '1' };
  }

  // Check if it's a monthly pattern: MM HH DD * *
  if (dayOfMonth !== '*' && month === '*' && weekday === '*') {
    return { frequency: 'monthly', hour, minute, weekdays: [], dayOfMonth };
  }

  return { frequency: 'advanced', hour: '0', minute: '0', weekdays: [], dayOfMonth: '1' };
}

function stateToCron(state: CronState): string {
  // Spring cron: 6 fields — second minute hour dayOfMonth month weekday
  switch (state.frequency) {
    case 'daily':
      return `0 ${state.minute} ${state.hour} * * *`;
    case 'weekly': {
      const days = state.weekdays.length > 0 ? state.weekdays.sort((a, b) => a - b).join(',') : '*';
      return `0 ${state.minute} ${state.hour} * * ${days}`;
    }
    case 'monthly':
      return `0 ${state.minute} ${state.hour} ${state.dayOfMonth} * *`;
    default:
      return '';
  }
}

export function CronPicker({ value, onChange, label }: CronPickerProps) {
  const t = useT();
  const [state, setState] = useState<CronState>(() => parseCronToState(value));
  const [rawValue, setRawValue] = useState(value);

  // Sync from external value changes
  useEffect(() => {
    const parsed = parseCronToState(value);
    setState(parsed);
    setRawValue(value);
  }, [value]);

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

  const frequencies: { key: Frequency; label: string }[] = [
    { key: 'daily', label: t.schedules.daily },
    { key: 'weekly', label: t.schedules.weekly },
    { key: 'monthly', label: t.schedules.monthly },
    { key: 'advanced', label: t.schedules.advanced },
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
                ? 'bg-accent text-white'
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
                          ? 'bg-accent text-white'
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
          <div className="flex items-center gap-2 p-2.5 rounded-lg bg-surface text-xs">
            <span className="text-text-tertiary">Cron:</span>
            <code className="font-mono text-text">{stateToCron(state)}</code>
          </div>
        </div>
      )}
    </div>
  );
}
