import { describe, it, expect } from 'vitest';
import { formatDate, formatNumber, formatRelativeUnit, formatTimeOfDay, formatTimestamp, intlLocale } from '../localeFormat';

// Local time, so the expectations hold in every time zone. ICU may put a narrow no-break space
// before AM/PM; the tests compare with plain spaces.
const THURSDAY = new Date(2026, 8, 24, 14, 5, 33);
const plain = (text: string) => text.replace(/\s/g, ' ');

describe('localeFormat', () => {
  it('maps the app language to its region', () => {
    expect(intlLocale('en')).toBe('en-US');
    expect(intlLocale('de')).toBe('de-DE');
  });

  // The test machine's own locale (de-DE here, en-US on CI) must not show through (e86f6544).
  it('writes dates in the app language, not the browser language', () => {
    const long = { weekday: 'long', day: '2-digit', month: '2-digit', year: 'numeric' } as const;
    expect(formatDate(THURSDAY, 'en', long)).toBe('Thursday, 09/24/2026');
    expect(formatDate(THURSDAY, 'de', long)).toBe('Donnerstag, 24.09.2026');
    expect(formatDate(THURSDAY, 'en')).toBe('9/24/2026');
    expect(formatDate(THURSDAY, 'de')).toBe('24.9.2026');
  });

  it('writes times and timestamps in the app language', () => {
    expect(plain(formatTimeOfDay(THURSDAY, 'en'))).toBe('2:05:33 PM');
    expect(formatTimeOfDay(THURSDAY, 'de')).toBe('14:05:33');
    expect(plain(formatTimestamp(THURSDAY, 'en'))).toBe('9/24/2026, 2:05:33 PM');
    expect(formatTimestamp(THURSDAY, 'de')).toBe('24.9.2026, 14:05:33');
    expect(formatTimestamp(THURSDAY, 'de', { day: '2-digit', month: '2-digit' })).toBe('24.09.');
  });

  it('takes ISO strings and epoch milliseconds as well as dates', () => {
    expect(formatDate(THURSDAY.toISOString(), 'de')).toBe('24.9.2026');
    expect(formatDate(THURSDAY.getTime(), 'en')).toBe('9/24/2026');
  });

  it('writes numbers with the separators of the app language', () => {
    expect(formatNumber(1234.5, 'en')).toBe('1,234.5');
    expect(formatNumber(1234.5, 'de')).toBe('1.234,5');
  });

  it('reads relative time in the app language', () => {
    expect(formatRelativeUnit(-5, 'minute', 'en')).toBe('5 minutes ago');
    expect(formatRelativeUnit(-5, 'minute', 'de')).toBe('vor 5 Minuten');
    expect(formatRelativeUnit(3, 'hour', 'en', { style: 'narrow', numeric: 'always' })).toBe('in 3h');
  });
});
