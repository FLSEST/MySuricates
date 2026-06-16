import { describe, it, expect } from 'vitest';
import { levelColor, relativeTime } from './activityLevel';

describe('levelColor', () => {
  it('maps known levels', () => {
    expect(levelColor('ERROR')).toBe('#fb7185');
    expect(levelColor('WARN')).toBe('#fbbf24');
    expect(levelColor('INFO')).toBe('#38bdf8');
    expect(levelColor('DEBUG')).toBe('#5b6678');
  });
  it('falls back for unknown levels', () => {
    expect(levelColor('WHATEVER')).toBe('#5b6678');
  });
});

describe('relativeTime', () => {
  const now = new Date('2026-06-16T12:00:00Z');
  it('under a minute', () => expect(relativeTime('2026-06-16T11:59:30Z', now)).toBe('<1m'));
  it('minutes', () => expect(relativeTime('2026-06-16T11:45:00Z', now)).toBe('15m'));
  it('hours', () => expect(relativeTime('2026-06-16T09:00:00Z', now)).toBe('3h'));
  it('days', () => expect(relativeTime('2026-06-14T12:00:00Z', now)).toBe('2d'));
  it('future clamps to <1m', () => expect(relativeTime('2026-06-16T12:05:00Z', now)).toBe('<1m'));
});
