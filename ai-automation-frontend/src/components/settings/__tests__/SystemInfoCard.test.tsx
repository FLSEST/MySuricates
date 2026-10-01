import { failOnUnhandledRequest } from '../../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { SystemInfoCard } from '../SystemInfoCard';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
});

function serveHealth(status: string, docker: string, dockerComponent: string) {
  server.use(
    http.get('/api/v1/system/health', () => HttpResponse.json({
      status, docker, timestamp: '2026-09-21T12:00:00Z',
      components: { database: { status: 'UP' }, docker: { status: dockerComponent } },
    })),
    http.get('/api/v1/system/info', () => HttpResponse.json({})),
  );
}

describe('SystemInfoCard', () => {
  it('shows missing Docker as "not set up", not as an error (a0e85182)', async () => {
    serveHealth('UP', 'NOT_CONFIGURED', 'NOT_CONFIGURED');
    renderWithProviders(<SystemInfoCard />);
    expect(await screen.findByText('not set up')).toBeInTheDocument();
    expect(screen.queryByText('NOT_CONFIGURED')).not.toBeInTheDocument();
  });

  it('still shows an unreachable, configured Docker as UNAVAILABLE', async () => {
    serveHealth('DEGRADED', 'UNAVAILABLE', 'DOWN');
    renderWithProviders(<SystemInfoCard />);
    expect(await screen.findByText('UNAVAILABLE')).toBeInTheDocument();
  });
});
