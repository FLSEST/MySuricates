import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { useI18nStore } from '../../i18n';
import { Docs } from '../Docs';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => useI18nStore.setState({ locale: 'de' }));

describe('Docs page', () => {
  it('hides the HTML comment that marks a generated page', async () => {
    server.use(
      http.get('/api/v1/docs', () =>
        HttpResponse.json([{ name: 'api-referenz.md', label: 'API-Referenz', lang: 'de' }])),
      http.get('/api/v1/docs/:name', () =>
        HttpResponse.json({
          content: '<!-- Erzeugt von scripts/docs-reference.py – nicht von Hand ändern -->\n\n# API-Referenz\n\nText.',
        })),
    );

    renderWithProviders(<Docs />);

    expect(await screen.findByRole('heading', { name: 'API-Referenz', level: 1 })).toBeInTheDocument();
    expect(screen.queryByText(/Erzeugt von scripts/)).not.toBeInTheDocument();
  });
});
