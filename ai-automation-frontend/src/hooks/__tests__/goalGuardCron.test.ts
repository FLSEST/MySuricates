import { describe, it, expect } from 'vitest';
import { cronWithTime, dailyCron, isDailyCron, timeOfCron } from '../useGoalGuard';

// ff6f08fd: the time of the guard changes at the goal page – only the time of its schedule
describe('the CRON of the goal guard', () => {
  it('turns a time of day into a daily Spring CRON and rejects impossible times', () => {
    expect(dailyCron('07:30')).toBe('0 30 7 * * *');
    expect(dailyCron('24:00')).toBeNull();
    expect(dailyCron('7')).toBeNull();
  });

  it('reads the fixed time of a CRON, and nothing from one that runs every few minutes', () => {
    expect(timeOfCron('0 30 7 * * *')).toBe('07:30');
    expect(timeOfCron('0 5 18 * * MON-FRI')).toBe('18:05');
    expect(timeOfCron('0 */15 * * * *')).toBeNull();
    expect(timeOfCron('30 7 * * *')).toBeNull();
  });

  it('tells a daily CRON from one limited to some days', () => {
    expect(isDailyCron('0 30 7 * * *')).toBe(true);
    expect(isDailyCron('0 30 7 ? * *')).toBe(true);
    expect(isDailyCron('0 30 7 * * MON-FRI')).toBe(false);
    expect(isDailyCron('0 */15 * * * *')).toBe(false);
  });

  it('sets only the time and keeps days, months and weekdays', () => {
    expect(cronWithTime('0 0 8 * * *', '06:45')).toBe('0 45 6 * * *');
    expect(cronWithTime('0 30 7 1 * MON-FRI', '09:05')).toBe('0 5 9 1 * MON-FRI');
    expect(cronWithTime('0 */15 * * * *', '09:05')).toBeNull();
    expect(cronWithTime('0 0 8 * * *', '25:00')).toBeNull();
  });
});
