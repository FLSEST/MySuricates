import { describe, it, expect } from 'vitest';
import {
  formatDate, formatDateRange, formatDecimal, formatNumber, formatRelativeUnit, formatTimeOfDay, formatTimestamp, formatUsd,
  formatTokenCount, usdAxisDigits, weekdayNames,
  intlLocale,
} from '../localeFormat';

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

  // 6bb7ed36: toFixed wrote "1.5" in the German UI too
  it('writes fixed decimals with the decimal separator of the app language', () => {
    expect(formatDecimal(1.5, 'en')).toBe('1.5');
    expect(formatDecimal(1.5, 'de')).toBe('1,5');
    expect(formatDecimal(2, 'de')).toBe('2,0');
    expect(formatDecimal(0.12345, 'de', 3)).toBe('0,123');
    expect(formatDecimal(1234.56, 'de')).toBe('1.234,6');
  });

  it('writes dollar amounts in the app language, fractions of a cent with four decimals', () => {
    expect(formatUsd(0.12, 'en')).toBe('$0.12');
    expect(plain(formatUsd(0.12, 'de'))).toBe('0,12 $');
    expect(formatUsd(0.0042, 'en')).toBe('$0.0042');
    expect(plain(formatUsd(0.0042, 'de'))).toBe('0,0042 $');
    expect(formatUsd(0, 'en')).toBe('$0.00');
    expect(plain(formatUsd(1234.5, 'de'))).toBe('1.234,50 $');
    expect(formatUsd(0.5, 'en', 4)).toBe('$0.5000');
  });

  // 17eb7994: token counts as `toFixed(0)` without separators, cost-axis ticks with mixed decimals
  // 17eb7994: the calendars kept their own weekday tables
  it('names the weekdays from Monday in the app language', () => {
    expect(weekdayNames('en')).toEqual(['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']);
    // ICU versions differ on the abbreviation dot („Mo“ / „Mo.“)
    expect(weekdayNames('de').map((d) => d.replace(/\.$/, ''))).toEqual(['Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa', 'So']);
  });

  it('shortens token counts with the separators of the app language', () => {
    expect(formatTokenCount(950, 'en')).toBe('950');
    expect(formatTokenCount(1234, 'en')).toBe('1.2k');
    expect(formatTokenCount(1234, 'de')).toBe('1,2k');
    expect(formatTokenCount(987_654, 'de')).toBe('987,7k');
    expect(formatTokenCount(3_450_000, 'en')).toBe('3.5M');
  });

  it('gives a cost axis one decimal count, as few as its largest value needs', () => {
    expect(usdAxisDigits(0)).toBe(2);
    expect(usdAxisDigits(12.5)).toBe(2);
    expect(usdAxisDigits(0.1)).toBe(2);
    expect(usdAxisDigits(0.05)).toBe(3);
    expect(usdAxisDigits(0.004)).toBe(4);
    const ticks = [0, 0.001, 0.002, 0.003, 0.004].map((v) => formatUsd(v, 'en', usdAxisDigits(0.004)));
    expect(ticks).toEqual(['$0.0000', '$0.0010', '$0.0020', '$0.0030', '$0.0040']);
  });

  it('writes a span of dates with the shared parts once', () => {
    const options = { day: 'numeric', month: 'long', year: 'numeric' } as const;
    expect(plain(formatDateRange(new Date(2026, 8, 21), new Date(2026, 8, 27), 'en', options))).toBe('September 21 – 27, 2026');
    expect(formatDateRange(new Date(2026, 8, 21), new Date(2026, 8, 27), 'de', options)).toBe('21.–27. September 2026');
    expect(plain(formatDateRange(new Date(2026, 8, 28), new Date(2026, 9, 4), 'de', options))).toBe('28. September – 4. Oktober 2026');
  });

  it('reads relative time in the app language', () => {
    expect(formatRelativeUnit(-5, 'minute', 'en')).toBe('5 minutes ago');
    expect(formatRelativeUnit(-5, 'minute', 'de')).toBe('vor 5 Minuten');
    expect(formatRelativeUnit(3, 'hour', 'en', { style: 'narrow', numeric: 'always' })).toBe('in 3h');
  });
});
