import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { useState } from 'react';
import { screen, fireEvent, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { useToastStore } from '../../../stores/toastStore';
import { RunNotesCard } from '../RunNotesCard';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  useI18nStore.setState({ locale: 'en' });
  useToastStore.setState({ toasts: [] });
});

/** The page holds the draft; this stand-in does the same. */
function Notes({ saved }: { saved: string }) {
  const [draft, setDraft] = useState<string | null>(null);
  return <RunNotesCard runId="run-1" savedNotes={saved} draft={draft} onDraftChange={setDraft} />;
}

function recordNotes(saved: unknown[]) {
  server.use(http.patch('/api/v1/executions/:runId/notes', async ({ request, params }) => {
    saved.push({ runId: params.runId, ...(await request.json() as object) });
    return HttpResponse.json({});
  }));
}

describe('RunNotesCard', () => {
  it('shows the saved notes of the run', () => {
    renderWithProviders(<Notes saved="checked by Flo" />);
    expect(screen.getByPlaceholderText(/notes/i)).toHaveValue('checked by Flo');
  });

  it('offers saving only after typing and confirms it', async () => {
    const saved: unknown[] = [];
    recordNotes(saved);
    renderWithProviders(<Notes saved="" />);

    const field = screen.getByPlaceholderText(/notes/i);
    expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument();

    fireEvent.change(field, { target: { value: 'checked twice' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(saved).toEqual([{ runId: 'run-1', notes: 'checked twice' }]));
    await waitFor(() => expect(useToastStore.getState().toasts.at(-1)?.message).toBe('Notes saved'));
  });

  it('saves on leaving the field, but only when something changed', async () => {
    const saved: unknown[] = [];
    recordNotes(saved);
    renderWithProviders(<Notes saved="" />);
    const field = screen.getByPlaceholderText(/notes/i);

    fireEvent.blur(field);
    fireEvent.change(field, { target: { value: 'flaky network' } });
    fireEvent.blur(field);
    await waitFor(() => expect(saved).toEqual([{ runId: 'run-1', notes: 'flaky network' }]));
  });
});
