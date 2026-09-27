import { beforeEach, describe, expect, it } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { useI18nStore } from '../../../i18n';
import { ModelStatsCard } from '../ModelStatsCard';
import { aggregateModelTokens } from '../modelStatsData';

beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
});

describe('ModelStatsCard', () => {
  it('keeps request, provider and reported model visible even for zero-token errors', () => {
    render(<ModelStatsCard data={[
      { requestedModel: 'opus', provider: 'CLAUDE', model: '<synthetic>', stepCount: 2, totalTokens: 0, totalCostUsd: 0 },
      { requestedModel: 'opus', provider: 'PI', model: 'claude-opus', stepCount: 3, totalTokens: 153, totalCostUsd: 0.5 },
    ]} />);
    fireEvent.click(screen.getByRole('button', { name: 'Details' }));
    const rows = screen.getAllByRole('listitem');
    const first = within(rows[0]);
    for (const label of ['Requested', 'Provider', 'Reported']) expect(first.getByText(label)).toBeVisible();
    expect(first.getByText('opus')).toBeVisible();
    expect(first.getByText('CLAUDE')).toBeVisible();
    expect(first.getByText('<synthetic>')).toBeVisible();
    expect(first.getByText('2 Steps')).toBeVisible();
    expect(first.getByText(/^0 Tokens/)).toBeVisible();
    expect(within(rows[1]).getByText('PI')).toBeVisible();
    expect(within(rows[1]).getByText('claude-opus')).toBeVisible();
    expect(within(rows[1]).getByText(/^153 Tokens/)).toBeVisible();
  });

  it('does not substitute the requested model for a missing response and accepts legacy data', () => {
    useI18nStore.setState({ locale: 'de' });
    render(<ModelStatsCard data={[
      { requestedModel: 'gpt-model', provider: 'CODEX', model: null, stepCount: 1, totalTokens: 50, totalCostUsd: 0 },
      { model: 'legacy-model', stepCount: 1, totalTokens: 0, totalCostUsd: 0 },
    ]} />);
    fireEvent.click(screen.getByRole('button', { name: 'Details' }));
    const rows = screen.getAllByRole('listitem');
    expect(within(rows[0]).getByText('Gemeldet').nextElementSibling).toHaveTextContent('Keine Angabe');
    expect(within(rows[0]).getByText('Angefordert').nextElementSibling).toHaveTextContent('gpt-model');
    expect(within(rows[1]).getAllByText('Keine Angabe')).toHaveLength(2);
    expect(within(rows[1]).getByText('legacy-model')).toBeVisible();
  });

  it('shows the empty state without data', () => {
    render(<ModelStatsCard />);
    expect(screen.getByText('No data available yet')).toBeVisible();
    expect(screen.queryByRole('list')).not.toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('starts simple, switches both ways and remembers the selection', () => {
    const data = [{ model: 'sonnet', stepCount: 1, totalTokens: 100, totalCostUsd: 0 }];
    const { unmount } = render(<ModelStatsCard data={data} />);
    expect(screen.getByRole('button', { name: 'Simple' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('region', { name: 'Simple' })).toBeInTheDocument();
    expect(screen.queryByText('Requested')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Details' }));
    expect(screen.getByRole('button', { name: 'Details' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByText('Requested')).toBeVisible();
    unmount();
    render(<ModelStatsCard data={data} />);
    expect(screen.getByRole('button', { name: 'Details' })).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(screen.getByRole('button', { name: 'Simple' }));
    expect(screen.getByRole('region', { name: 'Simple' })).toBeInTheDocument();
    expect(screen.queryByText('Requested')).not.toBeInTheDocument();
  });

  it('combines detailed rows by reported model for Simple without replacing missing models', () => {
    const base = { stepCount: 1, totalCostUsd: 0 };
    expect(aggregateModelTokens([
      { ...base, model: 'sonnet', provider: 'CLAUDE', requestedModel: 'sonnet', totalTokens: 100 },
      { ...base, model: 'other', totalTokens: 250 },
      { ...base, model: 'sonnet', provider: 'PI', requestedModel: 'auto', totalTokens: 200 },
      { ...base, model: null, requestedModel: 'sonnet', totalTokens: 50 },
      { ...base, model: '<synthetic>', totalTokens: 0 },
    ])).toEqual([
      { model: 'sonnet', tokens: 300 }, { model: 'other', tokens: 250 },
      { model: null, tokens: 50 }, { model: '<synthetic>', tokens: 0 },
    ]);
  });
});
