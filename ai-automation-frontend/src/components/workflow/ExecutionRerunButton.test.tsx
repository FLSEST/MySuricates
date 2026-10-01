import { failOnUnhandledRequest } from '../../test/msw';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { ExecutionRerunButton } from './ExecutionRerunButton';
import type { RunStatus } from '../../types';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('ExecutionRerunButton', () => {
  it.each<RunStatus>(['COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED', 'CANCELLED', 'INTERRUPTED'])(
    'starts a fresh run from %s and opens its detail page', async status => {
      let calls = 0;
      server.use(http.post('/api/v1/executions/old-run/retry', () => {
        calls++;
        return HttpResponse.json({ id: 'new-run', workflowId: 'workflow-1' });
      }));
      renderWithProviders(
        <Routes>
          <Route path="/" element={<ExecutionRerunButton run={{ id: 'old-run', status }} />} />
          <Route path="/workflows/workflow-1/run/new-run" element={<p>New execution</p>} />
        </Routes>,
      );
      fireEvent.click(screen.getByRole('button', { name: 'Run again' }));
      expect(await screen.findByText('New execution')).toBeInTheDocument();
      expect(calls).toBe(1);
    },
  );

  it.each<RunStatus>(['PENDING', 'RUNNING', 'PAUSED'])('offers no rerun for %s', status => {
    renderWithProviders(<ExecutionRerunButton run={{ id: 'active-run', status }} />);
    expect(screen.queryByRole('button', { name: 'Run again' })).not.toBeInTheDocument();
  });

  it('disables repeated clicks while the request is pending and re-enables after failure', async () => {
    let finish!: () => void;
    let calls = 0;
    server.use(http.post('/api/v1/executions/old-run/retry', async () => {
      calls++;
      await new Promise<void>(resolve => { finish = resolve; });
      return HttpResponse.json({ message: 'Cannot start' }, { status: 409 });
    }));
    renderWithProviders(<ExecutionRerunButton run={{ id: 'old-run', status: 'COMPLETED' }} />);
    const button = screen.getByRole('button', { name: 'Run again' });
    fireEvent.click(button);
    await waitFor(() => expect(button).toBeDisabled());
    fireEvent.click(button);
    await waitFor(() => expect(calls).toBe(1));
    finish();
    await waitFor(() => expect(button).toBeEnabled());
  });
});
