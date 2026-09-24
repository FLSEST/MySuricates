import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { Header } from '../Header';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => {
  server.resetHandlers();
  useI18nStore.setState({ locale: 'en' });
});
afterAll(() => server.close());

// The button stood in German in the English UI too (e86f6544).
describe('Header suggestions button', () => {
  function withSuggestions() {
    server.use(http.get('/api/v1/assistant/suggestions', () => HttpResponse.json(['What happened today?'])));
  }

  it('is named in English in the English UI', async () => {
    useI18nStore.setState({ locale: 'en' });
    withSuggestions();
    renderWithProviders(<Header />);

    const button = await screen.findByRole('button', { name: 'Suggestions' }, { timeout: 5000 });
    expect(button).toHaveAttribute('title', 'Suggestions for this page');
  }, 10_000);

  it('is named in German in the German UI', async () => {
    useI18nStore.setState({ locale: 'de' });
    withSuggestions();
    renderWithProviders(<Header />);

    const button = await screen.findByRole('button', { name: 'Vorschläge' }, { timeout: 5000 });
    expect(button).toHaveAttribute('title', 'Vorschläge für diese Seite');
  }, 10_000);
});
