import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, fireEvent, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { modulesFixture } from '../../../test/modules';
import { useI18nStore } from '../../../i18n';
import { RunContainerPanel } from '../RunContainerPanel';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => useI18nStore.setState({ locale: 'en' }));

describe('RunContainerPanel', () => {
  it('sendet das Kommando per Enter und per Knopf und leert das Feld', async () => {
    const sent: unknown[] = [];
    server.use(http.post('/api/v1/executions/:runId/command', async ({ request, params }) => {
      sent.push({ runId: params.runId, ...(await request.json() as object) });
      return HttpResponse.json({ output: '' });
    }));
    renderWithProviders(<RunContainerPanel runId="run-1" />);
    const input = screen.getByPlaceholderText('Enter command to run in container...');
    const send = screen.getByRole('button', { name: 'Send' });
    expect(send).toBeDisabled();

    fireEvent.change(input, { target: { value: '  ls -la  ' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(input).toHaveValue('');
    await waitFor(() => expect(sent).toHaveLength(1));

    fireEvent.change(input, { target: { value: 'pwd' } });
    // Während ein Kommando läuft, ist der Knopf im Ladezustand gesperrt.
    await waitFor(() => expect(send).not.toBeDisabled());
    fireEvent.click(send);
    expect(input).toHaveValue('');

    await waitFor(() => expect(sent).toEqual([
      { runId: 'run-1', command: 'ls -la' },
      { runId: 'run-1', command: 'pwd' },
    ]));
  });

  it('blendet den Terminal-Reiter aus, sobald das Modul terminal als aus gemeldet ist', async () => {
    server.use(http.get('/api/v1/system/capabilities', () =>
      HttpResponse.json({ apiVersion: 'v1', routes: [], blockTypes: [], ...modulesFixture('full', ['terminal']) })));
    renderWithProviders(<RunContainerPanel runId="run-1" />);
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Terminal' })).not.toBeInTheDocument());
    expect(screen.getByRole('button', { name: 'Command' })).toBeInTheDocument();
  });
});
