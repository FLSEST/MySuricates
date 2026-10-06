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

 /** Response shape of older backends: without triggerSource (before V96, MySuricates-TODO 6db47bc2). */
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
  it('names what started the chain', async () => {
    renderRun({ ...oldShape, triggerSource: 'SCHEDULE' });

    const chip = await screen.findByTestId('trigger-source');
    expect(chip).toHaveTextContent('Gestartet durch');
    // no link without a schedule ID
    expect(chip.tagName).toBe('SPAN');
  });

  // 3a35ced1: chain and schedule of origin are linked
  it('links the chain to the chain page and the starter to its schedule', async () => {
    renderRun({ ...oldShape, triggerSource: 'SCHEDULE', triggeredByScheduleId: 's-9' });

    expect(await screen.findByTestId('trigger-source')).toHaveAttribute('href', '/schedules?schedule=s-9');
    expect(screen.getByRole('link', { name: 'Release' })).toHaveAttribute('href', '/chains?chain=c-1');
  });

  it('shows no starter for older backends without the field, the page stays complete', async () => {
    renderRun(oldShape);

    expect(await screen.findByText('Release')).toBeInTheDocument();
    expect(screen.queryByTestId('trigger-source')).not.toBeInTheDocument();
  });
});
