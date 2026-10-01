import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { useI18nStore } from '../../i18n';
import type { ChainRun } from '../../types';
import { ChainExecution } from '../ChainExecution';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => useI18nStore.setState({ locale: 'de' }));

/** Antwortform älterer Backends: ohne triggerSource (vor V96, MySuricates-TODO 6db47bc2). */
const oldShape: ChainRun = {
  id: 'cr-1', chainId: 'c-1', chainName: 'Release', status: 'COMPLETED', inputContext: {}, metadata: {},
  startedAt: '2026-09-14T10:00:00Z', completedAt: '2026-09-14T10:01:00Z', durationMs: 60000, error: null,
  entryRuns: [],
};

function renderRun(run: ChainRun) {
  server.use(http.get('/api/v1/chains/c-1/runs/cr-1', () => HttpResponse.json(run)));
  return renderWithProviders(
    <Routes><Route path="/chains/:id/run/:runId" element={<ChainExecution />} /></Routes>,
    { route: '/chains/c-1/run/cr-1' },
  );
}

describe('ChainExecution — Starter der Kette', () => {
  it('nennt, was die Kette gestartet hat', async () => {
    renderRun({ ...oldShape, triggerSource: 'SCHEDULE' });

    const chip = await screen.findByTestId('trigger-source');
    expect(chip).toHaveTextContent('Gestartet durch');
  });

  it('zeigt bei älteren Backends ohne das Feld keinen Starter, die Seite bleibt vollständig', async () => {
    renderRun(oldShape);

    expect(await screen.findByText('Release')).toBeInTheDocument();
    expect(screen.queryByTestId('trigger-source')).not.toBeInTheDocument();
  });
});
