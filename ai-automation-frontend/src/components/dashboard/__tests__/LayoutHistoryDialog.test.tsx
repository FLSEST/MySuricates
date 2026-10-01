import { failOnUnhandledRequest } from '../../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { useDashboardSyncStore } from '../../../stores/dashboardSyncStore';
import type { DashboardLayoutVersionSummary } from '../../../types';
import { LayoutHistoryDialog } from '../LayoutHistoryDialog';

const summary = (id: string, extra: Partial<DashboardLayoutVersionSummary> = {}): DashboardLayoutVersionSummary => ({
  id, createdAt: '2026-09-15T10:00:00Z', deviceLabel: 'Laptop', name: null, formatVersion: 2, viewCount: 1,
  contentHash: `hash-${id}`, ...extra,
});

let patched: { id: string; body: unknown } | undefined;

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => {
  server.resetHandlers();
  vi.restoreAllMocks();
});
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  patched = undefined;
  useI18nStore.setState({ locale: 'en' });
  useDashboardSyncStore.setState({ deviceLabel: 'Laptop', baseVersionId: 'v2', dirty: false });
  server.use(
    http.get('/api/v1/dashboard/layouts', () => HttpResponse.json({
      content: [summary('v2', { name: 'Before the rework', deviceLabel: 'Desktop' }), summary('v1', { viewCount: 2 })],
      page: 0, size: 20, totalElements: 2, totalPages: 1,
    })),
    http.get('/api/v1/dashboard/layouts/:id', () => HttpResponse.json({
      ...summary('v1', { viewCount: 2 }),
      layout: { activeViewId: 'default', views: [{ id: 'default', name: '', widgets: [] }, { id: 'ops', name: 'Ops', widgets: [] }] },
    })),
    http.patch('/api/v1/dashboard/layouts/:id', async ({ request, params }) => {
      patched = { id: String(params.id), body: await request.json() };
      return HttpResponse.json(summary(String(params.id), { name: 'Renamed' }));
    }),
  );
});

async function renderDialog() {
  const onRestore = vi.fn();
  const onAdoptView = vi.fn();
  renderWithProviders(<LayoutHistoryDialog open onClose={vi.fn()} busy={false} onRestore={onRestore} onAdoptView={onAdoptView} />);
  expect(await screen.findByText('Before the rework')).toBeInTheDocument();
  const row = (id: string) => document.querySelector<HTMLElement>(`[data-version-id="${id}"]`)!;
  return { onRestore, onAdoptView, row };
}

describe('LayoutHistoryDialog', () => {
  it('lists versions with name, device, view count and the current marker', async () => {
    const { row } = await renderDialog();

    expect(within(row('v2')).getByText('current')).toBeInTheDocument();
    expect(within(row('v2')).getByText(/Desktop/)).toBeInTheDocument();
    expect(within(row('v1')).queryByText('current')).not.toBeInTheDocument();
    expect(within(row('v1')).getByText(/2 view\(s\)/)).toBeInTheDocument();
  });

  it('renames a version and clears the name when the field is empty', async () => {
    const { row } = await renderDialog();

    fireEvent.click(within(row('v1')).getByRole('button', { name: 'Name' }));
    fireEvent.change(within(row('v1')).getByRole('textbox', { name: 'Version name' }), { target: { value: '  Renamed ' } });
    fireEvent.click(within(row('v1')).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(patched).toEqual({ id: 'v1', body: { name: 'Renamed' } }));

    fireEvent.click(within(row('v2')).getByRole('button', { name: 'Name' }));
    fireEvent.change(within(row('v2')).getByRole('textbox', { name: 'Version name' }), { target: { value: '   ' } });
    fireEvent.click(within(row('v2')).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(patched).toEqual({ id: 'v2', body: { name: null } }));
  });

  // 23635ef6: the app's own dialog above the history instead of window.confirm
  it('restores only after confirmation', async () => {
    const { row, onRestore } = await renderDialog();
    const restoreButton = () => within(row('v1')).getAllByRole('button', { name: 'Restore' })[0]!;

    fireEvent.click(restoreButton());
    const question = screen.getByRole('dialog', { name: 'Restore' });
    expect(within(question).getByText('Restore this version? It replaces all views on this device and is saved as the newest version.'))
      .toBeInTheDocument();
    // Escape answers the question only; the history stays open
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(screen.queryByRole('dialog', { name: 'Restore' })).not.toBeInTheDocument();
    expect(screen.getByRole('dialog', { name: 'Dashboard history' })).toBeInTheDocument();
    expect(onRestore).not.toHaveBeenCalled();

    fireEvent.click(restoreButton());
    fireEvent.click(within(screen.getByRole('dialog', { name: 'Restore' })).getByRole('button', { name: 'Restore' }));
    expect(onRestore).toHaveBeenCalledWith('v1');
    expect(screen.queryByRole('dialog', { name: 'Restore' })).not.toBeInTheDocument();
  });

  it('offers the views of a version for adoption', async () => {
    const { row, onAdoptView } = await renderDialog();

    fireEvent.click(within(row('v1')).getByRole('button', { name: 'Take a view' }));
    expect(await within(row('v1')).findByRole('button', { name: 'Take “Overview”' })).toBeInTheDocument();
    fireEvent.click(within(row('v1')).getByRole('button', { name: 'Take “Ops”' }));

    expect(onAdoptView).toHaveBeenCalledWith('v1', 'ops');
  });

  it('stores the device label on blur', async () => {
    await renderDialog();

    const input = screen.getByDisplayValue('Laptop');
    fireEvent.change(input, { target: { value: 'Office PC' } });
    fireEvent.blur(input);

    expect(useDashboardSyncStore.getState().deviceLabel).toBe('Office PC');
  });
});
