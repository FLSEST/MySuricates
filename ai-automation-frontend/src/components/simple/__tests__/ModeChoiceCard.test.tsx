import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { de } from '../../../i18n/de';
import { useSettingsStore } from '../../../stores/settingsStore';
import { ModeChoiceCard } from '../ModeChoiceCard';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  useI18nStore.setState({ locale: 'de' });
  useSettingsStore.setState({ simpleMode: false, modeChoiceMade: false });
});

function withoutProjects() {
  server.use(http.get('/api/v1/projects', () => HttpResponse.json([])));
}

describe('ModeChoiceCard', () => {
  it('offers both modes as equals while there is no project and no choice', async () => {
    withoutProjects();
    renderWithProviders(<ModeChoiceCard />);

    expect(await screen.findByText(de.simple.modeChoice.title)).toBeTruthy();
    expect(screen.getByRole('button', { name: de.simple.modeChoice.useSimple })).toBeTruthy();
    expect(screen.getByRole('button', { name: de.simple.modeChoice.usePro })).toBeTruthy();
    expect(screen.getByText(de.simple.modeChoice.simpleDesc)).toBeTruthy();
    expect(screen.getByText(de.simple.modeChoice.proDesc)).toBeTruthy();
  });

  it('stays hidden once a project exists (existing installations)', async () => {
    const { queryClient } = renderWithProviders(<ModeChoiceCard />);

    await waitFor(() => expect(queryClient.getQueryData(['projects'])).toBeDefined());
    expect(screen.queryByTestId('mode-choice')).toBeNull();
  });

  it('stays hidden once a mode was chosen', async () => {
    withoutProjects();
    useSettingsStore.setState({ modeChoiceMade: true });
    const { queryClient } = renderWithProviders(<ModeChoiceCard />);

    await waitFor(() => expect(queryClient.getQueryData(['projects'])).toBeDefined());
    expect(screen.queryByTestId('mode-choice')).toBeNull();
  });

  it('choosing Einfach switches to Simple mode and remembers the choice', async () => {
    withoutProjects();
    renderWithProviders(<ModeChoiceCard />);

    fireEvent.click(await screen.findByRole('button', { name: de.simple.modeChoice.useSimple }));

    expect(useSettingsStore.getState()).toMatchObject({ simpleMode: true, modeChoiceMade: true });
    expect(screen.queryByTestId('mode-choice')).toBeNull();
  });

  it('choosing Pro keeps Pro mode and remembers the choice', async () => {
    withoutProjects();
    useSettingsStore.setState({ simpleMode: true });
    renderWithProviders(<ModeChoiceCard />);

    fireEvent.click(await screen.findByRole('button', { name: de.simple.modeChoice.usePro }));

    expect(useSettingsStore.getState()).toMatchObject({ simpleMode: false, modeChoiceMade: true });
    expect(screen.queryByTestId('mode-choice')).toBeNull();
  });
});
