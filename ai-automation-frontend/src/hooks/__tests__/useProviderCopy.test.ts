import { describe, it, expect, beforeEach } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { useI18nStore } from '../../i18n';
import { useProviderCopy } from '../useProviderCopy';

const caldav = { providerId: 'caldav', displayName: 'caldav (backend)', description: 'backend text' };
const unknown = { providerId: 'example-forge', displayName: 'Example Forge', description: 'Forge issues' };

beforeEach(() => useI18nStore.setState({ locale: 'en' }));

describe('useProviderCopy', () => {
  it('uses the translation of a shipped provider in the app language', () => {
    const { result } = renderHook(() => useProviderCopy());
    expect(result.current.name(caldav)).toBe('CalDAV');
    expect(result.current.description(caldav)).toMatch(/^Calendars via CalDAV/);
    expect(result.current.actionDescription('caldav', { name: 'list_calendars', description: 'x' }))
      .toBe('List the calendars of the account');

    act(() => useI18nStore.setState({ locale: 'de' }));
    expect(result.current.description(caldav)).toMatch(/^Kalender per CalDAV/);
    expect(result.current.actionDescription('caldav', { name: 'list_calendars', description: 'x' }))
      .toBe('Kalender des Kontos auflisten');
  });

  it('falls back to the backend text for unknown providers and actions', () => {
    const { result } = renderHook(() => useProviderCopy());
    expect(result.current.name(unknown)).toBe('Example Forge');
    expect(result.current.description(unknown)).toBe('Forge issues');
    expect(result.current.actionDescription('example-forge', { name: 'sync', description: 'Sync issues' })).toBe('Sync issues');
    expect(result.current.actionDescription('caldav', { name: 'not_translated', description: 'Backend only' })).toBe('Backend only');
  });
});
