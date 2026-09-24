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

/** A mouse click as a browser does it: mousedown moves the focus (unless prevented), then click. */
function clickLikeBrowser(target: HTMLElement) {
  if (fireEvent.mouseDown(target)) target.focus();
  fireEvent.mouseUp(target);
  fireEvent.click(target);
}

/** Waits for the first request and a moment more, so a second one would have arrived too. */
async function settled(saved: unknown[]) {
  await waitFor(() => expect(saved.length).toBeGreaterThan(0));
  await new Promise((r) => setTimeout(r, 50));
}

function recordNotes(saved: unknown[]) {
  server.use(http.patch('/api/v1/executions/:runId/notes', async ({ request, params }) => {
    saved.push({ runId: params.runId, ...(await request.json() as object) });
    return HttpResponse.json({});
  }));
}

describe('RunNotesCard', () => {
  it('shows the saved notes of the run and offers no saving while nobody typed', () => {
    renderWithProviders(<Notes saved="checked by Flo" />);
    expect(screen.getByPlaceholderText(/notes/i)).toHaveValue('checked by Flo');
    // Saving here would send the untouched (empty) draft and wipe the notes
    expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument();
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

  it('sends one request when the button is clicked while the field has the focus', async () => {
    const saved: unknown[] = [];
    recordNotes(saved);
    renderWithProviders(<Notes saved="" />);
    const field = screen.getByPlaceholderText(/notes/i);
    field.focus();
    fireEvent.change(field, { target: { value: 'checked twice' } });

    clickLikeBrowser(screen.getByRole('button', { name: 'Save' }));
    await settled(saved);
    expect(saved).toEqual([{ runId: 'run-1', notes: 'checked twice' }]);
  });

  it('sends one request when the button is reached with Tab and pressed', async () => {
    const saved: unknown[] = [];
    recordNotes(saved);
    renderWithProviders(<Notes saved="" />);
    const field = screen.getByPlaceholderText(/notes/i);
    field.focus();
    fireEvent.change(field, { target: { value: 'checked twice' } });

    const save = screen.getByRole('button', { name: 'Save' });
    save.focus();            // Tab: the field hands the focus to the button
    fireEvent.click(save);   // Enter or Space
    await settled(saved);
    expect(saved).toEqual([{ runId: 'run-1', notes: 'checked twice' }]);
  });

  it('saves when the focus leaves the notes, also after tabbing past the button', async () => {
    const saved: unknown[] = [];
    recordNotes(saved);
    renderWithProviders(<><Notes saved="" /><button type="button">elsewhere</button></>);
    const field = screen.getByPlaceholderText(/notes/i);
    field.focus();
    fireEvent.change(field, { target: { value: 'tabbed past' } });

    screen.getByRole('button', { name: 'Save' }).focus();     // Tab onto the button: still in the notes
    await new Promise((r) => setTimeout(r, 30));
    expect(saved).toEqual([]);
    screen.getByRole('button', { name: 'elsewhere' }).focus(); // Tab on: the notes lose the focus
    await settled(saved);
    expect(saved).toEqual([{ runId: 'run-1', notes: 'tabbed past' }]);
  });

  it('sends the same text only once', async () => {
    const saved: unknown[] = [];
    recordNotes(saved);
    renderWithProviders(<Notes saved="" />);
    const field = screen.getByPlaceholderText(/notes/i);
    fireEvent.change(field, { target: { value: 'once' } });

    fireEvent.blur(field);
    await settled(saved);
    // The run has not been reloaded yet, so the button is still there
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    fireEvent.blur(field);
    await new Promise((r) => setTimeout(r, 50));
    expect(saved).toEqual([{ runId: 'run-1', notes: 'once' }]);
  });
});
