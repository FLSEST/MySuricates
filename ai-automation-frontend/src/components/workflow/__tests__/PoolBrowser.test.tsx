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

  it('renders the badge "repo" for repo-bound entries', () => {
    mockEntries([entry({ scope: 'repo' })]);

    render(<PoolBrowser onSelect={() => {}} />);

    expect(screen.getByTestId('pool-scope-build-gate')).toHaveTextContent('repo');
  });

  it('renders the badge "portable" for portable entries', () => {
    mockEntries([entry({ id: 'readme', scope: 'portable' })]);

    render(<PoolBrowser onSelect={() => {}} />);

    expect(screen.getByTestId('pool-scope-readme')).toHaveTextContent('portable');
  });

  it('uses the German hint text when the language is German', () => {
    mockEntries([entry({ scope: 'repo' })]);

    render(<PoolBrowser onSelect={() => {}} />);

    expect(screen.getByTestId('pool-scope-build-gate')).toHaveAttribute('title', de.pool.scopeRepoHint);
  });

  it('uses the English hint text when the language is English', () => {
    useI18nStore.setState({ locale: 'en' });
    mockEntries([entry({ scope: 'repo' })]);

    render(<PoolBrowser onSelect={() => {}} />);

    const badge = screen.getByTestId('pool-scope-build-gate');
    expect(badge).toHaveAttribute('title', en.pool.scopeRepoHint);
    expect(badge).not.toHaveAttribute('title', de.pool.scopeRepoHint);
    // The label itself is deliberately language-neutral — 'repo' appears like this in the definition too.
    expect(badge).toHaveTextContent('repo');
  });

  it('shows external with the hint about the tool outside repo and project (7ddacd1d)', () => {
    useI18nStore.setState({ locale: 'de' });
    mockEntries([entry({ id: 'saas-scaffold', scope: 'external' })]);

    render(<PoolBrowser onSelect={() => {}} />);

    const badge = screen.getByTestId('pool-scope-saas-scaffold');
    expect(badge).toHaveTextContent('external');
    expect(badge).toHaveAttribute('title', de.pool.scopeExternalHint);
  });

  it('shows an unknown scope as it appears in the file', () => {
    mockEntries([entry({ scope: 'someday' as PoolWorkflowEntry['scope'] })]);

    render(<PoolBrowser onSelect={() => {}} />);

    expect(screen.getByTestId('pool-scope-build-gate')).toHaveTextContent('someday');
  });

  it('translates the categories', () => {
    useI18nStore.setState({ locale: 'de' });
    mockEntries([entry({ category: 'MONITORING' }), entry({ id: 'docs', category: 'DOCUMENTATION' })]);

    render(<PoolBrowser onSelect={() => {}} />);

    expect(screen.getByRole('button', { name: 'Überwachung' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Dokumentation' })).toBeInTheDocument();
  });

  it('shows no badge when the entry declares no scope', () => {
    mockEntries([entry({ scope: null })]);

    render(<PoolBrowser onSelect={() => {}} />);

    expect(screen.queryByTestId('pool-scope-build-gate')).toBeNull();
  });
});
