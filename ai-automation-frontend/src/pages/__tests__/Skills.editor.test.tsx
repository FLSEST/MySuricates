import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from 'vitest';
import { screen, fireEvent, waitFor, within } from '@testing-library/react';
import { createElement } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { modulesFixture } from '../../test/modules';
import { useI18nStore } from '../../i18n';
import { useToastStore } from '../../stores/toastStore';
import type { Skill } from '../../types';

// jsdom can't run Monaco
vi.mock('../../components/shared/MonacoEditor', () => ({
  MonacoEditor: ({ value, onChange }: { value: string; onChange?: (v: string) => void }) =>
    createElement('textarea', {
      'data-testid': 'mock-monaco',
      value,
      onChange: (e: React.ChangeEvent<HTMLTextAreaElement>) => onChange?.(e.target.value),
    }),
}));

import { Skills } from '../Skills';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
  useToastStore.setState({ toasts: [] });
});

function skill(overrides: Partial<Skill>): Skill {
  return {
    id: 'x', name: 'X', description: null, icon: null, category: 'CODE', aiContext: null, configSchema: null,
    builtin: false, version: '1.0.0', capabilities: [], createdAt: '2026-09-23T00:00:00Z', ...overrides,
  };
}

const builtin = skill({ id: 'b1', name: 'Code Review', description: 'Reviews code', icon: '🔍', builtin: true, aiContext: 'Review carefully.' });
const custom = skill({ id: 'c1', name: 'Reviewer', description: 'Mine', aiContext: 'Be brief.' });

function serveSkills(list: Skill[], pins: Record<string, string[]> = {}) {
  server.use(
    http.get('/api/v1/skills', () => HttpResponse.json(list)),
    http.get('/api/v1/skills/seal-pins', () => HttpResponse.json(pins)),
    http.get('/api/v1/skills/:id/capabilities', () => HttpResponse.json([])),
  );
}

async function openDetail(name: string) {
  fireEvent.click(await screen.findByText(name));
  return screen.findByTestId('skill-actions');
}

// jsdom parses its default stylesheet at the first getComputedStyle of a test file (each file runs in
// its own process) and runs that cascade for every element a role query checks: the first test pays ~1 s
// of it on top of the page, the editor dialog and its emoji picker (~50 buttons), beyond the 5 s default
// under the load of the full suite (b26bb8ff)
describe('Skills page — creating and editing', { timeout: 15_000 }, () => {
  it('creates a skill and opens it afterwards', async () => {
    const list: Skill[] = [builtin];
    let posted: Record<string, unknown> | undefined;
    serveSkills(list);
    server.use(
      http.get('/api/v1/skills', () => HttpResponse.json(list)),
      http.post('/api/v1/skills', async ({ request }) => {
        posted = await request.json() as Record<string, unknown>;
        const created = skill({ id: 'n1', ...posted });
        list.push(created);
        return HttpResponse.json(created, { status: 201 });
      }),
    );
    renderWithProviders(<Skills />);

    fireEvent.click(await screen.findByRole('button', { name: /New skill/ }));
    const editor = await screen.findByTestId('skill-editor');
    const create = screen.getByRole('button', { name: 'Create' });
    expect(create).toBeDisabled();

    fireEvent.change(within(editor).getByLabelText('Name'), { target: { value: '  Summarizer ' } });
    fireEvent.change(within(editor).getByTestId('mock-monaco'), { target: { value: '# Summarize\nShort.' } });
    fireEvent.click(within(editor).getByRole('button', { name: '🚀' }));
    fireEvent.click(create);

    await waitFor(() => expect(screen.queryByTestId('skill-editor')).not.toBeInTheDocument());
    expect(posted).toEqual({ name: 'Summarizer', description: null, icon: '🚀', category: 'PRODUCTIVITY', aiContext: '# Summarize\nShort.' });
    expect(await screen.findByRole('heading', { level: 2, name: 'Summarizer' })).toBeInTheDocument();
  });

  it('offers a copy instead of editing for a built-in skill', async () => {
    serveSkills([builtin]);
    renderWithProviders(<Skills />);

    const actions = await openDetail('Code Review');
    expect(within(actions).queryByRole('button', { name: /Edit/ })).not.toBeInTheDocument();
    expect(within(actions).queryByRole('button', { name: /Delete/ })).not.toBeInTheDocument();
    expect(screen.getByText(/Built-in skills are read-only/)).toBeInTheDocument();

    fireEvent.click(within(actions).getByRole('button', { name: /Copy as custom skill/ }));

    const editor = await screen.findByTestId('skill-editor');
    expect(within(editor).getByLabelText('Name')).toHaveValue('Code Review (copy)');
    expect(within(editor).getByTestId('mock-monaco')).toHaveValue('Review carefully.');
    expect(within(editor).getByRole('button', { name: '🔍' })).toHaveAttribute('aria-pressed', 'true');
    expect(within(editor).getByText(/Capabilities are not/)).toBeInTheDocument();
  });

  it('edits a custom skill with PUT and shows the new state', async () => {
    const list: Skill[] = [custom];
    let put: Record<string, unknown> | undefined;
    serveSkills(list);
    server.use(
      http.get('/api/v1/skills', () => HttpResponse.json(list)),
      http.put('/api/v1/skills/c1', async ({ request }) => {
        put = await request.json() as Record<string, unknown>;
        list[0] = { ...custom, ...put };
        return HttpResponse.json(list[0]);
      }),
    );
    renderWithProviders(<Skills />);

    const actions = await openDetail('Reviewer');
    fireEvent.click(within(actions).getByRole('button', { name: /Edit/ }));
    const editor = await screen.findByTestId('skill-editor');
    expect(within(editor).getByLabelText('Name')).toHaveValue('Reviewer');
    fireEvent.change(within(editor).getByLabelText('Name'), { target: { value: 'Strict reviewer' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(screen.queryByTestId('skill-editor')).not.toBeInTheDocument());
    expect(put).toMatchObject({ name: 'Strict reviewer', description: 'Mine', aiContext: 'Be brief.', category: 'CODE' });
    expect(await screen.findByRole('heading', { level: 2, name: 'Strict reviewer' })).toBeInTheDocument();
  });

  it('keeps the dialog open and names the seal when the save is refused', async () => {
    serveSkills([custom]);
    server.use(http.put('/api/v1/skills/c1', () => HttpResponse.json(
      { code: 'SKILL_PINNED_BY_SEAL', error: "Skill 'Reviewer' is used by sealed workflow(s) Deploy" }, { status: 409 },
    )));
    renderWithProviders(<Skills />);

    const actions = await openDetail('Reviewer');
    fireEvent.click(within(actions).getByRole('button', { name: /Edit/ }));
    await screen.findByTestId('skill-editor');
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(useToastStore.getState().toasts.at(-1)?.message).toContain('A sealed workflow uses this skill'));
    expect(screen.getByTestId('skill-editor')).toBeInTheDocument();
  });
});

describe('Skills page — icons', () => {
  it("shows a skill's own icon and falls back to the category icon", async () => {
    serveSkills([builtin, custom]);
    renderWithProviders(<Skills />);

    const builtinCard = (await screen.findByText('Code Review')).closest('.cursor-pointer') as HTMLElement;
    const customCard = screen.getByText('Reviewer').closest('.cursor-pointer') as HTMLElement;
    expect(within(builtinCard).getByTestId('skill-icon')).toHaveTextContent('🔍');
    expect(within(builtinCard).getByText('0 capabilities')).toBeInTheDocument();
    expect(within(customCard).queryByTestId('skill-icon')).not.toBeInTheDocument();
  });
});

describe('Skills page — seal lock', () => {
  it('locks editing and deleting of a sealed skill and offers a copy', async () => {
    serveSkills([custom], { c1: ['Deploy'] });
    server.use(http.get('/api/v1/system/capabilities', () => HttpResponse.json(modulesFixture('full', ['seal-immutable']))));
    renderWithProviders(<Skills />);

    const actions = await openDetail('Reviewer');
    expect(within(actions).getByRole('button', { name: /Edit/ })).toBeDisabled();
    expect(within(actions).getByRole('button', { name: /Delete/ })).toBeDisabled();
    expect(within(actions).getByRole('button', { name: /Copy as custom skill/ })).toBeEnabled();
    expect(screen.getByTestId('skill-sealed-detail')).toHaveTextContent('Deploy');
    await waitFor(() => expect(screen.getByTestId('skill-sealed-way-out')).toHaveTextContent(/until they are unsealed/));
  });

  it('stays locked with sealing switched off and says unsealing is off', async () => {
    serveSkills([custom], { c1: ['Deploy'] });
    server.use(http.get('/api/v1/system/capabilities', () => HttpResponse.json(modulesFixture('full', ['sealing']))));
    renderWithProviders(<Skills />);

    const actions = await openDetail('Reviewer');
    expect(within(actions).getByRole('button', { name: /Edit/ })).toBeDisabled();
    await waitFor(() => expect(screen.getByTestId('skill-sealed-way-out')).toHaveTextContent(/Unsealing is switched off/));
  });

  it('says seals are immutable when seal-immutable is on', async () => {
    serveSkills([custom], { c1: ['Deploy'] });
    server.use(http.get('/api/v1/system/capabilities', () => HttpResponse.json(modulesFixture('full'))));
    renderWithProviders(<Skills />);

    await openDetail('Reviewer');
    await waitFor(() => expect(screen.getByTestId('skill-sealed-way-out')).toHaveTextContent(/Seals are immutable/));
  });
});

describe('Skills page — import', () => {
  it('rejects a file that is not a skill export', async () => {
    serveSkills([builtin]);
    let imported = false;
    server.use(http.post('/api/v1/skills/import', () => {
      imported = true;
      return HttpResponse.json({}, { status: 201 });
    }));
    renderWithProviders(<Skills />);
    await screen.findByText('Code Review');

    const file = new File([JSON.stringify({ name: 'Workflow', blocks: [] })], 'workflow.json', { type: 'application/json' });
    fireEvent.change(screen.getByTestId('skill-import-input'), { target: { files: [file] } });

    await waitFor(() => expect(useToastStore.getState().toasts.at(-1)?.message).toContain('Not a valid skill file'));
    expect(imported).toBe(false);
  });

  it('imports a skill export and opens the new skill', async () => {
    const list: Skill[] = [builtin];
    let body: unknown;
    serveSkills(list);
    server.use(
      http.get('/api/v1/skills', () => HttpResponse.json(list)),
      http.post('/api/v1/skills/import', async ({ request }) => {
        body = await request.json();
        const created = skill({ id: 'i1', name: 'Imported one' });
        list.push(created);
        return HttpResponse.json(created, { status: 201 });
      }),
    );
    renderWithProviders(<Skills />);
    await screen.findByText('Code Review');

    const data = { name: 'Imported one', category: 'DATA', capabilities: [{ name: 'parse' }] };
    const file = new File([JSON.stringify(data)], 'imported.skill.json', { type: 'application/json' });
    fireEvent.change(screen.getByTestId('skill-import-input'), { target: { files: [file] } });

    expect(await screen.findByRole('heading', { level: 2, name: 'Imported one' })).toBeInTheDocument();
    expect(body).toEqual(data);
  });
});
