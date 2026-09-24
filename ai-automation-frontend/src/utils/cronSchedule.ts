/**
 * Spring cron (six fields: second minute hour dayOfMonth month weekday) ⇄ the picker's form state.
 * Five-field (legacy) expressions without the seconds field are read too.
 */

export type CronFrequency = 'daily' | 'weekly' | 'monthly' | 'advanced';

export interface CronState {
  frequency: CronFrequency;
  hour: string;
  minute: string;
  weekdays: number[];
  dayOfMonth: string;
}

export function parseCronToState(cron: string): CronState {
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

export function stateToCron(state: CronState): string {
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
