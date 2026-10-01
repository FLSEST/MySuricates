import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { de } from '../../../i18n/de';
import { en } from '../../../i18n/en';
import type { WorkflowMemory } from '../../../types';
import { WorkflowMemoryItem } from '../WorkflowMemoryItem';

const memory: WorkflowMemory = {
  id: 'm1', workflowId: 'w1', title: 'Lessons', content: 'remember this', tags: [],
  sourceRunIds: ['r1', 'r2', 'r3'], provider: 'CLAUDE', active: true, createdAt: '2026-09-01T10:00:00Z',
};

function renderItem(expanded = false) {
  const handlers = { onToggleExpanded: vi.fn(), onActivate: vi.fn(), onEdit: vi.fn(), onDelete: vi.fn() };
  renderWithProviders(
    <WorkflowMemoryItem memory={memory} fallbackTitle="v1" expanded={expanded} activating={false} {...handlers} />,
  );
  return handlers;
}

afterEach(() => useI18nStore.setState({ locale: 'en' }));

// f206504e: icon-only buttons had no accessible name, run count and provider were fixed text.
describe('WorkflowMemoryItem', () => {
  it.each([['en', en], ['de', de]] as const)('names its icon buttons and texts in the UI language (%s)', (locale, texts) => {
    useI18nStore.setState({ locale });
    const handlers = renderItem();

    fireEvent.click(screen.getByRole('button', { name: texts.common.expand }));
    fireEvent.click(screen.getByRole('button', { name: texts.common.edit }));
    fireEvent.click(screen.getByRole('button', { name: texts.common.delete }));
    expect(handlers.onToggleExpanded).toHaveBeenCalledOnce();
    expect(handlers.onEdit).toHaveBeenCalledOnce();
    expect(handlers.onDelete).toHaveBeenCalledOnce();

    expect(screen.getByText(texts.memory.runCount.replace('{count}', '3'))).toBeInTheDocument();
    expect(screen.getByText(`${texts.memory.provider}: Claude`)).toBeInTheDocument();
  });

  it('labels the toggle as collapse while expanded', () => {
    renderItem(true);
    const toggle = screen.getByRole('button', { name: en.common.collapse });
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByText('remember this')).toBeInTheDocument();
  });
});
