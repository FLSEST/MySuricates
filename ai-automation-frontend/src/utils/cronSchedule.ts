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

/** The strings `describeCron` needs — a slice of the translations, so tests can pass `de`/`en` directly. */
interface CronDescribeTexts {
  schedules: {
    weekdays: Record<'mon' | 'tue' | 'wed' | 'thu' | 'fri' | 'sat' | 'sun', string>;
    describe: {
      daily: string;
      weekly: string;
      monthly: string;
      hourly: string;
      everyMinutes: string;
      everyHours: string;
    };
  };
}

// Cron weekday numbers → translation keys (0 and 7 are both Sunday).
const WEEKDAY_KEY_BY_NUMBER = ['sun', 'mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'] as const;
const WEEKDAY_NUMBER_BY_NAME: Record<string, number> = { SUN: 0, MON: 1, TUE: 2, WED: 3, THU: 4, FRI: 5, SAT: 6 };

function toInt(field: string, min: number, max: number): number | null {
  if (!/^\d{1,2}$/.test(field)) return null;
  const n = Number(field);
  return n >= min && n <= max ? n : null;
}

function weekdayNumber(token: string): number | null {
  const byName = WEEKDAY_NUMBER_BY_NAME[token.toUpperCase()];
  if (byName !== undefined) return byName;
  const n = toInt(token, 0, 7);
  return n === null ? null : n % 7;
}

/** "1,3,5", "1-5", "MON-FRI" → sorted weekday numbers (Mon first, Sun last); null for anything else. */
function parseWeekdays(field: string): number[] | null {
  const days = new Set<number>();
  for (const part of field.split(',')) {
    const range = part.split('-');
    if (range.length === 1) {
      const d = weekdayNumber(range[0]!);
      if (d === null) return null;
      days.add(d);
    } else if (range.length === 2) {
      const from = weekdayNumber(range[0]!);
      const to = weekdayNumber(range[1]!);
      if (from === null || to === null || from > to) return null;
      for (let d = from; d <= to; d++) days.add(d);
    } else {
      return null;
    }
  }
  // Monday first, Sunday last — the order people read a week in
  return [...days].sort((a, b) => ((a + 6) % 7) - ((b + 6) % 7));
}

const pad = (n: number) => String(n).padStart(2, '0');

/**
 * A plain-language line for a Spring cron expression — "Daily at 09:00", "Mon, Wed at 08:30",
 * "Monthly on day 1 at 07:00". Returns null when the expression is not one of the simple shapes;
 * callers then keep the raw expression for Pro.
 */
export function describeCron(cron: string | null | undefined, t: CronDescribeTexts): string | null {
  if (!cron) return null;
  const parts = cron.trim().split(/\s+/);
  if (parts.length !== 6 && parts.length !== 5) return null;
  const fields = parts.length === 6 ? parts : ['0', ...parts];
  const [second, minuteField, hourField, dom, month, dow] = fields as [string, string, string, string, string, string];
  if (second !== '0' || month !== '*') return null;
  const d = t.schedules.describe;

  const minute = toInt(minuteField, 0, 59);
  const anyDay = dom === '*' && (dow === '*' || dow === '?');

  // Every N minutes / hours, and hourly at a fixed minute
  if (anyDay && hourField === '*') {
    const step = /^(?:\*|0)\/(\d{1,2})$/.exec(minuteField);
    if (step) {
      const n = toInt(step[1]!, 2, 59);
      return n === null ? null : d.everyMinutes.replace('{n}', String(n));
    }
    return minute === null ? null : d.hourly.replace('{minute}', String(minute));
  }
  const hourStep = /^(?:\*|0)\/(\d{1,2})$/.exec(hourField);
  if (anyDay && hourStep && minute === 0) {
    const n = toInt(hourStep[1]!, 2, 23);
    return n === null ? null : d.everyHours.replace('{n}', String(n));
  }

  const hour = toInt(hourField, 0, 23);
  if (minute === null || hour === null) return null;
  const time = `${pad(hour)}:${pad(minute)}`;

  if (anyDay) return d.daily.replace('{time}', time);

  if (dom === '*' || dom === '?') {
    const days = parseWeekdays(dow);
    if (!days || days.length === 0) return null;
    if (days.length === 7) return d.daily.replace('{time}', time);
    const names = days.map((n) => t.schedules.weekdays[WEEKDAY_KEY_BY_NUMBER[n]!]).join(', ');
    return d.weekly.replace('{days}', names).replace('{time}', time);
  }

  if (dow === '*' || dow === '?') {
    const day = toInt(dom, 1, 31);
    if (day === null) return null;
    return d.monthly.replace('{day}', String(day)).replace('{time}', time);
  }

  return null;
}
