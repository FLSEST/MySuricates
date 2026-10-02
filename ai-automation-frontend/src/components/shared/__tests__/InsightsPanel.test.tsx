import { failOnUnhandledRequest } from '../../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { de } from '../../../i18n/de';
import type { RunInsights } from '../../../types';
import { InsightsPanel } from '../InsightsPanel';

/** A recorded Claude audit step: 2 uncached input tokens, the rest served by the prompt cache (TODO 20adda30). */
const WITH_CACHE: RunInsights = {
  runId: 'r1',
  status: 'COMPLETED',
  totalCostUsd: 0.408808,
  totalInputTokens: 2,
  totalOutputTokens: 918,
  totalTokens: 920,
  totalCacheReadInputTokens: 13720,
  totalCacheCreationInputTokens: 48462,
  durationMs: 13043,
  steps: [{
    stepIndex: 0, status: 'COMPLETED', durationMs: 13043, costUsd: 0.408808, inputTokens: 2, outputTokens: 918,
    cacheReadInputTokens: 13720, cacheCreationInputTokens: 48462,
    model: 'claude-opus-5-5', provider: 'CLAUDE', numTurns: 1, toolNames: null, toolUseCount: null,
  }],
};

/** What a backend before the cache figures sends — and what Codex/Pi steps look like. */
const WITHOUT_CACHE: RunInsights = {
  runId: 'r1',
  status: 'COMPLETED',
  totalCostUsd: 0,
  totalInputTokens: 400,
  totalOutputTokens: 100,
  totalTokens: 500,
  durationMs: 2000,
  steps: [{
    stepIndex: 0, status: 'COMPLETED', durationMs: 2000, costUsd: 0, inputTokens: 400, outputTokens: 100,
    model: null, provider: 'CODEX', numTurns: null, toolNames: null, toolUseCount: null,
  }],
};

let payload: RunInsights = WITH_CACHE;

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  useI18nStore.setState({ locale: 'en' });
  server.use(http.get('/api/v1/learning/runs/r1/insights', () => HttpResponse.json(payload)));
});

describe('InsightsPanel cache tokens', () => {
  it('shows the cached input next to the uncached input, in the summary and per step', async () => {
    payload = WITH_CACHE;
    renderWithProviders(<InsightsPanel runId="r1" />);

    const labels = await screen.findAllByText('+62.2k cache');
    expect(labels).toHaveLength(2);
    expect(labels[0]).toHaveAttribute('title', '13.7k read from the prompt cache, 48.5k written to the cache – on top of the input tokens');
  });

  it('shows no cache label when the backend sends no cache figures', async () => {
    payload = WITHOUT_CACHE;
    renderWithProviders(<InsightsPanel runId="r1" />);

    expect(await screen.findByText('400')).toBeInTheDocument();
    expect(screen.queryByText(/cache/)).not.toBeInTheDocument();
  });

  // 17eb7994: the per-step token count carried a fixed "tok"
  it('names the step tokens in the UI language', async () => {
    payload = WITHOUT_CACHE;
    useI18nStore.setState({ locale: 'de' });
    renderWithProviders(<InsightsPanel runId="r1" />);

    expect(await screen.findByText(de.insights.stepTokens.replace('{count}', '500'))).toBeInTheDocument();
    expect(screen.queryByText(/\btok\b/)).not.toBeInTheDocument();
  });
});

describe('InsightsPanel thinking tokens (TODO 20861200)', () => {
  it('shows the thinking part of the output in the summary and per step', async () => {
    payload = {
      ...WITH_CACHE,
      totalThinkingTokens: 377,
      steps: [{ ...WITH_CACHE.steps[0], thinkingTokens: 377 }],
    };
    renderWithProviders(<InsightsPanel runId="r1" />);

    const labels = await screen.findAllByText('incl. 377 thinking');
    expect(labels).toHaveLength(2);
    expect(labels[0]).toHaveAttribute('title', expect.stringContaining('already included in the output tokens'));
  });

  it('shows no thinking label for a backend without the figures', async () => {
    payload = WITH_CACHE;
    renderWithProviders(<InsightsPanel runId="r1" />);

    expect(await screen.findAllByText('+62.2k cache')).toHaveLength(2);
    expect(screen.queryByText(/thinking/)).not.toBeInTheDocument();
  });
});
