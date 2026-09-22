import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { de } from '../../../i18n/de';
import { useSettingsStore } from '../../../stores/settingsStore';
import { GeneralSettingsCard } from '../GeneralSettingsCard';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  useI18nStore.setState({ locale: 'de' });
  useSettingsStore.setState({ hintsEnabled: true, dismissedHints: { 'dashboard-views': 1 } });
});

describe('GeneralSettingsCard — hints', () => {
  it('switches hints off and on', () => {
    renderWithProviders(<GeneralSettingsCard />);
    const toggle = screen.getByRole('switch', { name: de.hints.settingsTitle });

    fireEvent.click(toggle);
    expect(useSettingsStore.getState().hintsEnabled).toBe(false);

    fireEvent.click(toggle);
    expect(useSettingsStore.getState().hintsEnabled).toBe(true);
  });

  it('shows every dismissed hint again', () => {
    useSettingsStore.setState({ hintsEnabled: false });
    renderWithProviders(<GeneralSettingsCard />);

    fireEvent.click(screen.getByRole('button', { name: de.hints.resetAction }));

    expect(useSettingsStore.getState()).toMatchObject({ hintsEnabled: true, dismissedHints: {} });
  });
});
