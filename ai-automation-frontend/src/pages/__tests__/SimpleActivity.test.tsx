import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { useI18nStore } from '../../i18n';
import { SimpleActivity } from '../SimpleActivity';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  useI18nStore.setState({ locale: 'de' });
});

function entry(id: string, category: string, message: string) {
  return {
    id, timestamp: new Date().toISOString(), level: 'INFO', category, message, source: null,
    referenceId: null, workflowId: null, metadata: {}, createdAt: new Date().toISOString(),
  };
}

describe('SimpleActivity', () => {
  it('names categories in the UI language and keeps unknown ones as they are', async () => {
    server.use(http.get('/api/v1/activity-log', () => HttpResponse.json({
      content: [entry('a-1', 'SCHEDULE', 'Plan started'), entry('a-2', 'SECURITY', 'Key rotated'), entry('a-3', 'BILLING', 'New kind')],
      totalElements: 3, totalPages: 1, number: 0, size: 50,
    })));

    renderWithProviders(<SimpleActivity />);

    expect(await screen.findByText('Plan started')).toBeInTheDocument();
    expect(screen.getByText(/^Zeitplan ·/)).toBeInTheDocument();
    expect(screen.getByText(/^Sicherheit ·/)).toBeInTheDocument();
    expect(screen.getByText(/^BILLING ·/)).toBeInTheDocument();
    expect(screen.queryByText(/^SCHEDULE ·/)).not.toBeInTheDocument();
  });
});
