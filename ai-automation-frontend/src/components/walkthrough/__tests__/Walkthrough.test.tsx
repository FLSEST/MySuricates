/**
 * The walkthrough deliberately links nowhere into the app, so almost nothing here can go stale —
 * except the module gates. A panel that survives on an instance whose module is off promises a
 * feature that does not exist there, which is the exact failure the design set out to avoid.
 * These tests pin the gates and the one state bug they can cause: the list shrinking under a
 * reader who is already past its new end.
 */
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from 'vitest';
import { screen, fireEvent, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { modulesFixture } from '../../../test/modules';
import { useI18nStore } from '../../../i18n';
import { walkthroughText } from '../../../i18n/walkthrough';
import type { ModuleName } from '../../../types';
import { WALKTHROUGH_PANELS } from '../../../data/walkthroughPanels';
import { Walkthrough } from '../Walkthrough';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
});

function serve(disabled: ModuleName[] = []) {
  server.use(
    http.get('/api/v1/system/capabilities', () => HttpResponse.json(modulesFixture('full', disabled))),
  );
}

const title = (id: string) => walkthroughText(id, 'en').title;

/** Click "next" until the tour ends, collecting the headline of every panel on the way. */
async function walk(): Promise<string[]> {
  const seen: string[] = [];
  for (;;) {
    const heading = await screen.findByRole('heading', { level: 2 });
    seen.push(heading.textContent ?? '');
    const button = screen.getByRole('button', { name: /next|done/i });
    if (/done/i.test(button.textContent ?? '')) break;
    fireEvent.click(button);
  }
  return seen;
}

describe('Walkthrough', () => {
  it('shows every panel when all modules are on', async () => {
    serve();
    renderWithProviders(<Walkthrough onClose={vi.fn()} />);
    // Wait for the module answer, otherwise this passes on the optimistic default alone.
    await waitFor(() => expect(screen.getAllByRole('button').length).toBeGreaterThan(0));

    expect(await walk()).toEqual(WALKTHROUGH_PANELS.map((p) => title(p.id)));
  });

  it.each([
    ['factory', 'repair'],
    ['integrations', 'connect'],
    ['audit', 'verdict'],
  ])('leaves out the %s panel when the module is off', async (module, panel) => {
    serve([module as ModuleName]);
    renderWithProviders(<Walkthrough onClose={vi.fn()} />);
    await waitFor(() => expect(screen.queryByText(title(panel))).not.toBeInTheDocument());

    const seen = await walk();
    expect(seen).not.toContain(title(panel));
    expect(seen).toContain(title('intro'));
    expect(seen).toContain(title('closing'));
  });

  it('still ends on the closing panel when several modules are off', async () => {
    serve(['factory', 'integrations', 'audit']);
    renderWithProviders(<Walkthrough onClose={vi.fn()} />);

    const seen = await walk();
    expect(seen[seen.length - 1]).toBe(title('closing'));
    expect(seen).toHaveLength(WALKTHROUGH_PANELS.length - 3);
  });

  it('does not go blank when the panel list shrinks past the current position', async () => {
    // Module state is optimistic while unknown, so the first paint can show panels that the
    // backend then reports as off. Read to the end first, then let the answer arrive.
    let disabled: ModuleName[] = [];
    server.use(
      http.get('/api/v1/system/capabilities', () => HttpResponse.json(modulesFixture('full', disabled))),
    );
    const { queryClient } = renderWithProviders(<Walkthrough onClose={vi.fn()} />);

    await walk();
    expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent(title('closing'));

    disabled = ['factory', 'integrations', 'audit'];
    await queryClient.invalidateQueries();

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent(title('closing')),
    );
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('closes on the last panel and on Escape', async () => {
    serve();
    const onClose = vi.fn();
    renderWithProviders(<Walkthrough onClose={onClose} />);

    await screen.findByRole('heading', { level: 2 });
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(1);

    await walk();
    fireEvent.click(screen.getByRole('button', { name: /done/i }));
    expect(onClose).toHaveBeenCalledTimes(2);
  });

  it('switches the copy language without leaving the panel', async () => {
    serve();
    renderWithProviders(<Walkthrough onClose={vi.fn()} />);

    await screen.findByText(title('intro'));
    // The label is upper-cased by CSS, so the accessible name stays the lower-case locale code.
    fireEvent.click(screen.getByRole('button', { name: /^de$/i }));
    expect(screen.getByText(walkthroughText('intro', 'de').title)).toBeInTheDocument();
  });
});
