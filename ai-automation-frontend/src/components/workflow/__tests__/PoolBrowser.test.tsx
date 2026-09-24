import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { PoolBrowser } from '../PoolBrowser';
import { usePoolWorkflows } from '../../../hooks/usePool';
import { useI18nStore } from '../../../i18n';
import { de } from '../../../i18n/de';
import { en } from '../../../i18n/en';
import type { PoolWorkflowEntry } from '../../../types';

vi.mock('../../../hooks/usePool', () => ({
  usePoolWorkflows: vi.fn(),
}));

function entry(overrides: Partial<PoolWorkflowEntry> = {}): PoolWorkflowEntry {
  return {
    id: 'build-gate',
    name: { de: 'Build Gate', en: 'Build Gate' },
    description: { de: 'Prueft den Build.', en: 'Checks the build.' },
    icon: '🚦',
    category: 'QUALITY',
    scope: 'repo',
    tags: ['quality'],
    blocks: [],
    instructions: null,
    model: null,
    version: '1.0.0',
    author: 'MySuricates',
    defaultContext: null,
    filename: 'build-gate.json',
    ...overrides,
  };
}

function mockEntries(entries: PoolWorkflowEntry[]) {
  vi.mocked(usePoolWorkflows).mockReturnValue({
    data: entries,
    isLoading: false,
  } as ReturnType<typeof usePoolWorkflows>);
}

describe('PoolBrowser scope badge', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useI18nStore.setState({ locale: 'de' });
  });

  it('rendert das Badge "repo" fuer repo-gebundene Eintraege', () => {
    mockEntries([entry({ scope: 'repo' })]);

    render(<PoolBrowser onSelect={() => {}} />);

    expect(screen.getByTestId('pool-scope-build-gate')).toHaveTextContent('repo');
  });

  it('rendert das Badge "portable" fuer portable Eintraege', () => {
    mockEntries([entry({ id: 'readme', scope: 'portable' })]);

    render(<PoolBrowser onSelect={() => {}} />);

    expect(screen.getByTestId('pool-scope-readme')).toHaveTextContent('portable');
  });

  it('nutzt den deutschen Hinweistext, wenn die Sprache Deutsch ist', () => {
    mockEntries([entry({ scope: 'repo' })]);

    render(<PoolBrowser onSelect={() => {}} />);

    expect(screen.getByTestId('pool-scope-build-gate')).toHaveAttribute('title', de.pool.scopeRepoHint);
  });

  it('nutzt den englischen Hinweistext, wenn die Sprache Englisch ist', () => {
    useI18nStore.setState({ locale: 'en' });
    mockEntries([entry({ scope: 'repo' })]);

    render(<PoolBrowser onSelect={() => {}} />);

    const badge = screen.getByTestId('pool-scope-build-gate');
    expect(badge).toHaveAttribute('title', en.pool.scopeRepoHint);
    expect(badge).not.toHaveAttribute('title', de.pool.scopeRepoHint);
    // Das Label selbst ist bewusst sprachneutral — 'repo' steht so auch in der Definition.
    expect(badge).toHaveTextContent('repo');
  });

  it('zeigt external mit dem Hinweis auf das Werkzeug außerhalb von Repo und Projekt (7ddacd1d)', () => {
    useI18nStore.setState({ locale: 'de' });
    mockEntries([entry({ id: 'saas-scaffold', scope: 'external' })]);

    render(<PoolBrowser onSelect={() => {}} />);

    const badge = screen.getByTestId('pool-scope-saas-scaffold');
    expect(badge).toHaveTextContent('external');
    expect(badge).toHaveAttribute('title', de.pool.scopeExternalHint);
  });

  it('zeigt einen unbekannten scope so, wie er in der Datei steht', () => {
    mockEntries([entry({ scope: 'someday' as PoolWorkflowEntry['scope'] })]);

    render(<PoolBrowser onSelect={() => {}} />);

    expect(screen.getByTestId('pool-scope-build-gate')).toHaveTextContent('someday');
  });

  it('übersetzt die Kategorien', () => {
    useI18nStore.setState({ locale: 'de' });
    mockEntries([entry({ category: 'MONITORING' }), entry({ id: 'docs', category: 'DOCUMENTATION' })]);

    render(<PoolBrowser onSelect={() => {}} />);

    expect(screen.getByRole('button', { name: 'Überwachung' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Dokumentation' })).toBeInTheDocument();
  });

  it('zeigt kein Badge, wenn der Eintrag keinen scope deklariert', () => {
    mockEntries([entry({ scope: null })]);

    render(<PoolBrowser onSelect={() => {}} />);

    expect(screen.queryByTestId('pool-scope-build-gate')).toBeNull();
  });
});
