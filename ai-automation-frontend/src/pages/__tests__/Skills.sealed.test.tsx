import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { useI18nStore } from '../../i18n';
import { Skills } from '../Skills';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => { localStorage.clear(); useI18nStore.setState({ locale: 'en' }); });

describe('Skills page seal pins', () => {
  it('marks a skill that a sealed workflow uses', async () => {
    server.use(
      http.get('/api/v1/skills', () => HttpResponse.json([
        { id: 's1', name: 'Reviewer', description: 'Reviews', icon: null, category: 'CODE', aiContext: null,
          builtin: false, version: '1.0.0', capabilities: [] },
        { id: 's2', name: 'Writer', description: 'Writes', icon: null, category: 'CODE', aiContext: null,
          builtin: false, version: '1.0.0', capabilities: [] },
      ])),
      http.get('/api/v1/skills/seal-pins', () => HttpResponse.json({ s1: ['Deploy'] })),
    );

    renderWithProviders(<Skills />);

    const marker = await screen.findByTestId('skill-sealed');
    expect(marker).toHaveAttribute('title', expect.stringContaining('Deploy'));
    expect(screen.getAllByTestId('skill-sealed')).toHaveLength(1);
  });
});
