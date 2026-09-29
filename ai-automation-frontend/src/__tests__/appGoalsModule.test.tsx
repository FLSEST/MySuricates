import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '../test/server';
import { modulesFixture } from '../test/modules';
import { useI18nStore } from '../i18n';

// Review c60bf914: the real routes of the app, not a text search in App.tsx – with the module "goals" off,
// /goals and /goals/:goalId show the module hint and nothing asks the goals endpoints.

beforeAll(() => {
  server.listen({ onUnhandledRequest: 'bypass' });
  // jsdom kennt kein scrollIntoView – der Assistent der ganzen App ruft es beim Öffnen
  Element.prototype.scrollIntoView = vi.fn();
});
afterEach(() => {
  server.resetHandlers();
  localStorage.clear();
  window.history.pushState({}, '', '/');
});
afterAll(() => server.close());

async function renderAppAt(path: string, goalsOn: boolean) {
  const modules = modulesFixture('full', goalsOn ? [] : ['goals']);
  localStorage.setItem('mysuricates-modules', JSON.stringify(modules));
  useI18nStore.setState({ locale: 'en' });
  const goalCalls: string[] = [];
  server.use(
    http.get('/api/v1/system/auth-status', () => HttpResponse.json({ securityEnabled: false })),
    http.get('/api/v1/system/capabilities', () => HttpResponse.json({ blockTypes: [], ...modules })),
    http.all('/api/v1/goals', ({ request }) => { goalCalls.push(new URL(request.url).pathname); return HttpResponse.json([]); }),
    http.all('/api/v1/goals/*', ({ request }) => { goalCalls.push(new URL(request.url).pathname); return HttpResponse.json({}, { status: 404 }); }),
  );
  window.history.pushState({}, '', path);
  // The browser router reads the address when App.tsx loads – load it anew for each address
  vi.resetModules();
  const { App } = await import('../App');
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><App /></QueryClientProvider>);
  return goalCalls;
}

describe('App routes and the module "goals"', { timeout: 30_000 }, () => {
  it.each(['/goals', '/goals/g1'])('shows the module hint at %s when the module is off, without a goals call', async (path) => {
    const goalCalls = await renderAppAt(path, false);
    expect(await screen.findByRole('heading', { name: 'Module "Goals" is disabled' }, { timeout: 15_000 })).toBeInTheDocument();
    await new Promise((resolve) => setTimeout(resolve, 100));
    expect(goalCalls).toEqual([]);
  });

  it('opens the goals overview when the module is on', async () => {
    const goalCalls = await renderAppAt('/goals', true);
    await waitFor(() => expect(goalCalls).toContain('/api/v1/goals'), { timeout: 15_000 });
    expect(screen.queryByRole('heading', { name: 'Module "Goals" is disabled' })).not.toBeInTheDocument();
  });
});
