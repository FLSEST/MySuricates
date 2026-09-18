import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, fireEvent, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { TodoSettingsCard } from '../TodoSettingsCard';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
});

const CONFIG = {
  todoHygieneEnabled: 1,
  todoStaleDays: 45,
  todoHygieneMaxPerRun: 300,
  todoSimilarityKeywordThreshold: 0.35,
  activeRuns: 0,
};

/** Serves the execution config and records every PUT body the card sends. */
function serve(bodies: Record<string, unknown>[]) {
  server.use(
    http.get('/api/v1/system/execution-config', () => HttpResponse.json(CONFIG)),
    http.put('/api/v1/system/execution-config', async ({ request }) => {
      bodies.push((await request.json()) as Record<string, unknown>);
      return HttpResponse.json(CONFIG);
    }),
  );
}

describe('TodoSettingsCard — hygiene thresholds', () => {
  it('shows the runtime hygiene settings from the server', async () => {
    serve([]);
    renderWithProviders(<TodoSettingsCard />);

    expect(await screen.findByLabelText('Days until stale')).toHaveValue(45);
    expect(screen.getByLabelText('Max. archived per run')).toHaveValue(300);
    expect(screen.getByRole('switch', { name: 'Archive stale auto todos' })).toHaveAttribute('aria-checked', 'true');
  });

  it('saves a changed threshold through the execution-config endpoint', async () => {
    const bodies: Record<string, unknown>[] = [];
    serve(bodies);
    renderWithProviders(<TodoSettingsCard />);

    fireEvent.change(await screen.findByLabelText('Days until stale'), { target: { value: '90' } });
    fireEvent.click(screen.getByRole('button', { name: /save/i }));

    await waitFor(() => expect(bodies).toHaveLength(1));
    expect(bodies[0]).toMatchObject({ todoStaleDays: 90, todoHygieneMaxPerRun: 300, todoHygieneEnabled: 1 });
  });

  it('shows the similarity threshold from the server', async () => {
    serve([]);
    renderWithProviders(<TodoSettingsCard />);

    expect(await screen.findByLabelText('Similarity threshold')).toHaveValue(0.35);
  });

  it('saves a changed similarity threshold as a fraction', async () => {
    const bodies: Record<string, unknown>[] = [];
    serve(bodies);
    renderWithProviders(<TodoSettingsCard />);

    fireEvent.change(await screen.findByLabelText('Similarity threshold'), { target: { value: '0.5' } });
    fireEvent.click(screen.getByRole('button', { name: /save/i }));

    await waitFor(() => expect(bodies).toHaveLength(1));
    expect(bodies[0]).toMatchObject({ todoSimilarityKeywordThreshold: 0.5 });
  });

  it('turns the scheduled archival off', async () => {
    const bodies: Record<string, unknown>[] = [];
    serve(bodies);
    renderWithProviders(<TodoSettingsCard />);

    fireEvent.click(await screen.findByRole('switch', { name: 'Archive stale auto todos' }));
    fireEvent.click(screen.getByRole('button', { name: /save/i }));

    await waitFor(() => expect(bodies).toHaveLength(1));
    expect(bodies[0]).toMatchObject({ todoHygieneEnabled: 0 });
  });

  it('keeps the save button disabled while nothing changed', async () => {
    serve([]);
    renderWithProviders(<TodoSettingsCard />);

    expect(await screen.findByRole('button', { name: /save/i })).toBeDisabled();
  });

  it('still offers the local page sizes', async () => {
    serve([]);
    renderWithProviders(<TodoSettingsCard />);

    expect(await screen.findByLabelText('Rows per page (list)')).toBeInTheDocument();
    expect(screen.getByLabelText('Cards per kanban column')).toBeInTheDocument();
  });
});
