/**
 * The walkthrough deliberately links nowhere into the app, so almost nothing here can go stale —
 * except the module gates. A panel that survives on an instance whose module is off promises a
 * feature that does not exist there, which is the exact failure the design set out to avoid.
 * These tests pin the gates and the one state bug they can cause: the list shrinking under a
 * reader who is already past its new end.
 */
import { failOnUnhandledRequest } from '../../../test/msw';
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

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
});

let modulesAnswered = false;
let modulesOff: ModuleName[] = [];
function serve(disabled: ModuleName[] = []) {
  modulesAnswered = false;
  modulesOff = disabled;
  server.use(
    http.get('/api/v1/system/capabilities', () => {
      modulesAnswered = true;
      return HttpResponse.json(modulesFixture('full', disabled));
    }),
  );
}

/** One progress dot per panel the tour shows. */
const progressDots = () => document.querySelectorAll('[role="dialog"] div.flex-wrap[aria-hidden="true"] > span').length;

/**
 * Module state is optimistic while unknown, so a walk before the answer can pass a panel the answer
 * then removes. Waits for the request, then for the dialog to show it: as many progress dots as
 * panels the served modules allow (all modules and panel points come from the same answer). With
 * every module on the count equals the optimistic one — there the walk itself is the check.
 */
async function moduleAnswer() {
  await waitFor(() => expect(modulesAnswered).toBe(true));
  const expected = WALKTHROUGH_PANELS.filter((p) => !p.module || !modulesOff.includes(p.module)).length;
  await waitFor(() => expect(progressDots()).toBe(expected));
}

const title = (id: string) => walkthroughText(id, 'en').title;
const point = (id: string, index: number) => walkthroughText(id, 'en').points[index] ?? '';

/** Everything the public `core` preset leaves off: all research and advanced modules. */
const CORE_OFF: ModuleName[] = [
  'chains', 'assistant', 'learning', 'memory', 'audit', 'bpmn', 'integrations', 'webhooks',
  'remote-access', 'terminal', 'staging', 'conversion', 'dialog-import', 'onboarding-profiles',
  'skills', 'debug', 'factory', 'wishes',
];

/**
 * "Next", on the last panel "Done". By its text: a role query with a name computes the accessible
 * name and visibility of every element after each panel change, 13 times per walk (b26bb8ff).
 */
const nextButton = () => screen.getByText(/^(next|done)$/i, { selector: 'button' });

/** Click "next" until the tour ends, collecting the headline of every panel on the way. */
async function walk(): Promise<string[]> {
  const seen: string[] = [];
  for (;;) {
    const heading = await screen.findByRole('heading', { level: 2 });
    seen.push(heading.textContent ?? '');
    const button = nextButton();
    if (/done/i.test(button.textContent ?? '')) break;
    fireEvent.click(button);
  }
  return seen;
}

// A walk clicks through all 13 panels, and the first test of the file also pays jsdom's and React's
// warm-up: alone up to 2 s, under the load of the full suite beyond the 5 s default (b26bb8ff)
describe('Walkthrough', { timeout: 15_000 }, () => {
  it('shows every panel when all modules are on', async () => {
    serve();
    renderWithProviders(<Walkthrough onClose={vi.fn()} />);
    // The optimistic default shows every panel too; waiting for the answer makes sure the answer
    // itself removes none
    await moduleAnswer();

    expect(await walk()).toEqual(WALKTHROUGH_PANELS.map((p) => title(p.id)));
  });

  it.each([
    ['factory', 'repair'],
    ['integrations', 'connect'],
    ['audit', 'verdict'],
  ])('leaves out the %s panel when the module is off', async (module, panel) => {
    serve([module as ModuleName]);
    renderWithProviders(<Walkthrough onClose={vi.fn()} />);
    await moduleAnswer();

    const seen = await walk();
    expect(seen).not.toContain(title(panel));
    expect(seen).toContain(title('intro'));
    expect(seen).toContain(title('closing'));
  });

  // f2a233eb: a fresh installation runs `core` — the tour must not promise chains, a process
  // diagram, an audit center, repairs or integrations there, neither as a panel nor as a sentence.
  it('mentions nothing on core that core does not have', async () => {
    serve(CORE_OFF);
    renderWithProviders(<Walkthrough onClose={vi.fn()} />);
    await moduleAnswer();

    const said: string[] = [];
    for (;;) {
      said.push(screen.getByRole('heading', { level: 2 }).textContent ?? '');
      said.push(...screen.getAllByRole('listitem').map((li) => li.textContent ?? ''));
      const button = nextButton();
      if (/done/i.test(button.textContent ?? '')) break;
      fireEvent.click(button);
      await screen.findByRole('heading', { level: 2 });
    }
    const text = said.join('\n');

    for (const panel of ['repair', 'verdict', 'connect']) expect(text).not.toContain(title(panel));
    expect(text).not.toContain(point('blocks', 1));
    expect(text).not.toContain(point('trace', 2));
    expect(text).not.toMatch(/chain|process diagram|audit|integration/i);
    // The panels themselves stay — only the sentence that needs the module goes
    expect(text).toContain(title('blocks'));
    expect(text).toContain(point('blocks', 0));
    expect(text).toContain(title('trace'));
    expect(text).toContain(title('closing'));
  });

  it('keeps a gated sentence while its module is on', async () => {
    serve(['bpmn']);
    renderWithProviders(<Walkthrough onClose={vi.fn()} />);
    await moduleAnswer();

    await walk();
    fireEvent.click(screen.getByRole('button', { name: /back/i }));
    expect(await screen.findByRole('heading', { level: 2 })).toHaveTextContent(title('blocks'));
    expect(screen.getByText(point('blocks', 1))).toBeInTheDocument();
  });

  it('still ends on the closing panel when several modules are off', async () => {
    serve(['factory', 'integrations', 'audit']);
    renderWithProviders(<Walkthrough onClose={vi.fn()} />);
    await moduleAnswer();

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
