import { failOnUnhandledRequest } from '../../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { de } from '../../../i18n/de';
import { en } from '../../../i18n/en';
import { useSettingsStore } from '../../../stores/settingsStore';
import { hintText } from '../../../i18n/hints';
import { TemplateSelector } from '../TemplateSelector';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  useI18nStore.setState({ locale: 'en' });
  useSettingsStore.setState({ simpleMode: true, hintsEnabled: true, dismissedHints: {} });
});

const base = { icon: '🧪', instructions: null, model: null, builtIn: true, qualityStatus: 'UNTESTED', verifications: [],
  version: '1.0.0', createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' };
const templates = [
  { ...base, id: 'code-quality', kind: 'TEMPLATE', category: 'CODE', sortOrder: 0,
    name: { de: 'Code-Qualität', en: 'Code Quality' }, description: { de: 'Prüft', en: 'Checks' },
    blocks: [{ type: 'shell', config: { command: 'npm run lint' } }] },
  { ...base, id: 'blueprint-approval', kind: 'BLUEPRINT', category: 'BASICS', sortOrder: 1,
    name: { de: 'Freigabe vor dem Handeln', en: 'Approval before acting' }, description: { de: 'Zeigt Freigabe', en: 'Shows approval' },
    blocks: [{ type: 'approval', config: { question: 'Go?' } }] },
];

const poolEntry = {
  id: 'inbox-sorter', name: { de: 'Posteingang sortieren', en: 'Inbox sorter' }, description: { de: 'Sortiert', en: 'Sorts' },
  icon: '📥', category: 'PRODUCTIVITY', version: '1.0.0', scope: 'portable', tags: [], instructions: null, model: null,
  author: null, defaultContext: null, filename: 'inbox-sorter.json',
  blocks: [{ type: 'shell', config: { command: 'ls' } }, { type: 'shell', config: { command: 'pwd' } }],
};

function tabLabels() {
  // The tab row: every button whose text is one of the three tab names
  const names = [en.blueprints.tab, en.templates.tab, en.pool.tab];
  return screen.getAllByRole('button').map((b) => b.textContent).filter((text) => names.includes(text ?? ''));
}

describe('TemplateSelector — simple variant', () => {
  it('opens on templates, keeps the pool, and has neither blueprints nor a blank workflow', async () => {
    server.use(http.get('/api/v1/workflow-templates', () => HttpResponse.json(templates)));
    const onSelect = vi.fn();

    renderWithProviders(<TemplateSelector onSelect={onSelect} variant="simple" />);

    expect(await screen.findByText('Code Quality')).toBeInTheDocument();
    expect(tabLabels()).toEqual([en.templates.tab, en.pool.tab]);
    expect(screen.queryByText(en.templates.blank)).not.toBeInTheDocument();
    expect(screen.queryByText('Approval before acting')).not.toBeInTheDocument();
    expect(screen.getByText(en.simple.create.subtitle)).toBeInTheDocument();

    fireEvent.click(screen.getByText('Code Quality'));
    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ id: 'code-quality' }));
  });

  it('hides build details on template cards: no step count, no export', async () => {
    server.use(http.get('/api/v1/workflow-templates', () => HttpResponse.json(templates)));

    renderWithProviders(<TemplateSelector onSelect={() => {}} variant="simple" />);

    await screen.findByText('Code Quality');
    expect(screen.queryByText(`1 ${en.workflows.blocks.toLowerCase()}`)).not.toBeInTheDocument();
    expect(screen.queryByTitle(en.templates.exportTemplate)).not.toBeInTheDocument();
  });

  it('hides the step count on pool cards too', async () => {
    server.use(
      http.get('/api/v1/workflow-templates', () => HttpResponse.json(templates)),
      http.get('/api/v1/pool', () => HttpResponse.json([poolEntry])),
    );

    renderWithProviders(<TemplateSelector onSelect={() => {}} variant="simple" />);
    await screen.findByText('Code Quality');
    fireEvent.click(screen.getByRole('button', { name: en.pool.tab }));

    expect(await screen.findByText('Inbox sorter')).toBeInTheDocument();
    expect(screen.queryByText(`2 ${en.workflows.blocks.toLowerCase()}`)).not.toBeInTheDocument();
  });

  it('explains templates in place and hands building over to Pro visibly', async () => {
    server.use(http.get('/api/v1/workflow-templates', () => HttpResponse.json(templates)));

    renderWithProviders(<TemplateSelector onSelect={() => {}} variant="simple" />);

    expect(await screen.findByText(hintText('simple-create', 'en').title)).toBeInTheDocument();
    expect(screen.getByText(en.simple.create.buildInPro)).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: en.simple.handoff.openInPro }));
    expect(useSettingsStore.getState().simpleMode).toBe(false);
  });

  it('keeps the handoff on the pool tab too, in German', async () => {
    useI18nStore.setState({ locale: 'de' });
    server.use(
      http.get('/api/v1/workflow-templates', () => HttpResponse.json(templates)),
      http.get('/api/v1/pool', () => HttpResponse.json([])),
    );

    renderWithProviders(<TemplateSelector onSelect={() => {}} variant="simple" />);
    await screen.findByText('Code-Qualität');
    fireEvent.click(screen.getByRole('button', { name: de.pool.tab }));

    expect(screen.queryByText('Code-Qualität')).not.toBeInTheDocument();
    expect(screen.getByText(de.simple.create.buildInPro)).toBeInTheDocument();
  });

  it('leaves the pro variant as it was: blueprints first, no handoff', async () => {
    server.use(
      http.get('/api/v1/workflow-templates', () => HttpResponse.json(templates)),
      http.get('/api/v1/pool', () => HttpResponse.json([poolEntry])),
    );

    renderWithProviders(<TemplateSelector onSelect={() => {}} />);

    expect(await screen.findByText('Approval before acting')).toBeInTheDocument();
    expect(tabLabels()).toEqual([en.blueprints.tab, en.templates.tab, en.pool.tab]);
    expect(screen.queryByText(en.simple.create.buildInPro)).not.toBeInTheDocument();
    expect(screen.queryByText(hintText('simple-create', 'en').title)).not.toBeInTheDocument();

    // template and pool cards keep their build details in Pro
    fireEvent.click(screen.getByRole('button', { name: en.templates.tab }));
    expect(await screen.findByText(`1 ${en.workflows.blocks.toLowerCase()}`)).toBeInTheDocument();
    expect(screen.getByTitle(en.templates.exportTemplate)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: en.pool.tab }));
    expect(await screen.findByText(`2 ${en.workflows.blocks.toLowerCase()}`)).toBeInTheDocument();
  });
});
