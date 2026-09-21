import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import { renderWithProviders } from '../../../test/utils';
import { QuickActionsCard } from '../QuickActionsCard';

const moduleState = vi.hoisted(() => ({ skills: true }));

vi.mock('../../../hooks/useModules', () => ({
  useModuleEnabled: (name: string) => (name === 'skills' ? moduleState.skills : true),
}));

vi.mock('../dashboardDataContext', () => ({
  useDashboardData: () => ({ data: undefined }),
}));

// TODO a2003fae: on the public `core` preset the skills module is off — the quick action
// must not lead to the "module disabled" page.
describe('QuickActionsCard', () => {
  it('offers the skills shortcut while the module is enabled', () => {
    moduleState.skills = true;
    renderWithProviders(<QuickActionsCard />);

    expect(screen.getByRole('button', { name: /skills/i })).toBeInTheDocument();
  });

  it('hides the skills shortcut when the module is disabled', () => {
    moduleState.skills = false;
    renderWithProviders(<QuickActionsCard />);

    expect(screen.queryByRole('button', { name: /skills/i })).not.toBeInTheDocument();
    expect(screen.getAllByRole('button')).toHaveLength(2);
  });
});
