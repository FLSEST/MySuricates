import { describe, it, expect, beforeEach, vi } from 'vitest';
import { screen, fireEvent } from '@testing-library/react';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { GoalCandidates } from '../GoalCandidates';
import type { Todo } from '../../../types';

beforeEach(() => useI18nStore.setState({ locale: 'en' }));

const todo = { id: 'c1', title: 'Release notes', priority: 'HIGH', tags: ['release', 'docs'], projectName: 'Site' } as unknown as Todo;

function renderCandidates(overrides: Partial<Parameters<typeof GoalCandidates>[0]> = {}) {
  const props = {
    filter: { q: '', tag: '' }, onFilterChange: vi.fn(), candidates: [todo], isLoading: false,
    suggestions: ['release', 'backend'], onAssign: vi.fn(), assigning: false, ...overrides,
  };
  renderWithProviders(<GoalCandidates {...props} />);
  return props;
}

describe('GoalCandidates', () => {
  it('lists candidates with project and tags and assigns one per click when its project has no work package', () => {
    const props = renderCandidates();

    expect(screen.getByText('Site · #release · #docs')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Add: Release notes' }));
    expect(props.onAssign).toHaveBeenCalledWith(todo, null);
  });

  // 85889063 (Flo): „sollte man dort ja einem Arbeitspaket zuordnen können“
  it('asks for the work package when the project has some: no package or one of them, one click each', () => {
    const packagesOf = vi.fn(() => [{ id: 'w1', title: 'Repo clean' }, { id: 'w2', title: 'Getting started' }]);
    const props = renderCandidates({ packagesOf });

    const trigger = screen.getByRole('button', { name: 'Add: Release notes' });
    expect(trigger).toHaveAttribute('aria-haspopup', 'menu');
    fireEvent.click(trigger);
    expect(packagesOf).toHaveBeenCalledWith(todo);
    expect(screen.getAllByRole('menuitem').map((item) => item.textContent)).toEqual(['No work package', 'Repo clean', 'Getting started']);
    fireEvent.click(screen.getByRole('menuitem', { name: 'Getting started' }));
    expect(props.onAssign).toHaveBeenCalledWith(todo, { id: 'w2', title: 'Getting started' });
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();

    fireEvent.click(trigger);
    fireEvent.click(screen.getByRole('menuitem', { name: 'No work package' }));
    expect(props.onAssign).toHaveBeenLastCalledWith(todo, null);
  });

  it('filters by search text and by a suggested tag, and clears the tag again', () => {
    const props = renderCandidates({ filter: { q: '', tag: 'backend' } });

    fireEvent.change(screen.getByPlaceholderText('Search'), { target: { value: 'notes' } });
    expect(props.onFilterChange).toHaveBeenLastCalledWith({ q: 'notes', tag: 'backend' });
    expect(screen.getByRole('button', { name: 'backend' })).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(screen.getByRole('button', { name: 'release' }));
    expect(props.onFilterChange).toHaveBeenLastCalledWith({ q: '', tag: 'release' });
    fireEvent.click(screen.getByRole('button', { name: 'all' }));
    expect(props.onFilterChange).toHaveBeenLastCalledWith({ q: '', tag: '' });
  });

  it('says so when nothing without a goal matches', () => {
    renderCandidates({ candidates: [] });
    expect(screen.getByText('No matching TODOs without a goal.')).toBeInTheDocument();
  });
});
