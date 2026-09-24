import { describe, it, expect } from 'vitest';
import { de } from '../i18n/de';
import { en } from '../i18n/en';
import { cronRunsOnDay, describeCron, describeSchedule, parseCronToState, stateToCron } from './cronSchedule';

describe('parseCronToState / stateToCron', () => {
  it.each([
    '0 0 9 * * *',
    '0 30 8 * * 1,3',
    '0 0 7 1 * *',
  ])('round-trips %s with six fields', (cron) => {
    const back = stateToCron(parseCronToState(cron));
    expect(back).toBe(cron);
    expect(back.split(' ')).toHaveLength(6);
  });

  it('reads a five-field legacy expression and writes six fields', () => {
    expect(stateToCron(parseCronToState('15 6 * * *'))).toBe('0 15 6 * * *');
  });

  it('sorts weekdays when writing', () => {
    expect(stateToCron({ frequency: 'weekly', hour: '8', minute: '0', weekdays: [5, 1, 3], dayOfMonth: '1' }))
      .toBe('0 0 8 * * 1,3,5');
  });

  it('falls back to advanced for anything else', () => {
    expect(parseCronToState('0 0 12 1 6 *').frequency).toBe('advanced');
    expect(parseCronToState('nonsense').frequency).toBe('advanced');
    expect(stateToCron(parseCronToState('0 0 12 1 6 *'))).toBe('');
  });
});

describe('describeCron', () => {
  it('describes the simple shapes in German', () => {
    expect(describeCron('0 0 9 * * *', de)).toBe('Täglich um 09:00');
    expect(describeCron('0 30 8 * * 1,3', de)).toBe('Mo, Mi um 08:30');
    expect(describeCron('0 0 7 1 * *', de)).toBe('Monatlich am 1. um 07:00');
  });

  it('describes the simple shapes in English', () => {
    expect(describeCron('0 0 9 * * *', en)).toBe('Daily at 09:00');
    expect(describeCron('0 30 8 * * 1,3', en)).toBe('Mon, Wed at 08:30');
    expect(describeCron('0 0 7 1 * *', en)).toBe('Monthly on day 1 at 07:00');
  });

  it('reads weekday ranges, names and Sunday as 0 or 7, Monday first', () => {
    expect(describeCron('0 0 9 * * MON-FRI', de)).toBe('Mo, Di, Mi, Do, Fr um 09:00');
    expect(describeCron('0 0 9 * * 1-5', en)).toBe('Mon, Tue, Wed, Thu, Fri at 09:00');
    expect(describeCron('0 0 10 * * 0,6', en)).toBe('Sat, Sun at 10:00');
    expect(describeCron('0 0 10 * * 7', de)).toBe('So um 10:00');
    expect(describeCron('0 0 9 * * 0-6', en)).toBe('Daily at 09:00');
  });

  it('describes hourly and every-N shapes', () => {
    expect(describeCron('0 15 * * * *', en)).toBe('Hourly at minute 15');
    expect(describeCron('0 */15 * * * *', de)).toBe('Alle 15 Minuten');
    expect(describeCron('0 0 */2 * * *', en)).toBe('Every 2 hours');
  });

  it('accepts five-field legacy expressions', () => {
    expect(describeCron('30 6 * * *', en)).toBe('Daily at 06:30');
  });

  it('returns null for anything it cannot say plainly', () => {
    expect(describeCron('0 0 12 1 6 *', en)).toBeNull(); // a fixed month
    expect(describeCron('30 0 9 * * *', en)).toBeNull(); // seconds
    expect(describeCron('0 0 9-17 * * *', en)).toBeNull(); // hour range
    expect(describeCron('0 0 9 1 * 1', en)).toBeNull(); // day and weekday
    expect(describeCron('0 0 9 L * *', en)).toBeNull(); // last day of month
    expect(describeCron('0 0 9 * * 1#2', en)).toBeNull(); // second Monday
    expect(describeCron('0 0 25 * * *', en)).toBeNull(); // no such hour
    expect(describeCron('@daily', en)).toBeNull();
    expect(describeCron('', en)).toBeNull();
    expect(describeCron(null, en)).toBeNull();
  });
});

describe('cronRunsOnDay', () => {
  // Friday, 2026-09-25
  const friday = new Date(2026, 8, 25, 12, 0, 0);

  it('reads daily, weekday and day-of-month shapes', () => {
    expect(cronRunsOnDay('0 0 9 * * *', friday)).toBe(true);
    expect(cronRunsOnDay('0 0 9 * * MON-FRI', friday)).toBe(true);
    expect(cronRunsOnDay('0 0 9 * * 1,3', friday)).toBe(false);
    expect(cronRunsOnDay('0 0 9 25 * *', friday)).toBe(true);
    expect(cronRunsOnDay('0 0 9 1,15 * *', friday)).toBe(false);
    expect(cronRunsOnDay('*/15 * * * *', friday)).toBe(true);
  });

  it('gives up on shapes it cannot read', () => {
    expect(cronRunsOnDay('0 0 9 * 1 *', friday)).toBeNull();
    expect(cronRunsOnDay('0 0 9 L * *', friday)).toBeNull();
    expect(cronRunsOnDay('nonsense', friday)).toBeNull();
    expect(cronRunsOnDay(null, friday)).toBeNull();
  });
});

describe('describeSchedule', () => {
  it('names one-off and delayed schedules, else describes the cron', () => {
    expect(describeSchedule({ scheduleType: 'ONCE', cronExpression: '' }, en)).toBe(en.schedules.once);
    expect(describeSchedule({ scheduleType: 'DELAYED', cronExpression: '' }, en)).toBe(en.schedules.delayed);
    expect(describeSchedule({ scheduleType: 'CRON', cronExpression: '0 0 9 * * *' }, en)).toBe(describeCron('0 0 9 * * *', en));
  });
});
