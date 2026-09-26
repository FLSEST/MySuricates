import { describe, it, expect, afterEach } from 'vitest';
import { screen } from '@testing-library/react';
import { renderWithProviders } from '../../test/utils';
import { useI18nStore } from '../../i18n';
import { de } from '../../i18n/de';
import { en } from '../../i18n/en';
import { PendingToolCard } from '../assistant/PendingToolCard';
import { pendingToolText } from '../assistant/pendingToolText';
import type { PendingTool } from '../../hooks/useAssistant';

// TODO 4e152029: the confirmation card speaks the UI language and names the object, not its id.

const future = () => new Date(Date.now() + 600_000).toISOString();

function tool(partial: Partial<PendingTool> & Pick<PendingTool, 'type'>): PendingTool {
  return { id: 'pt-1', description: 'English fallback', expiresAt: future(), ...partial };
}

function renderCard(t: PendingTool, locale: 'de' | 'en') {
  useI18nStore.setState({ locale });
  return renderWithProviders(<PendingToolCard tool={t} />);
}

afterEach(() => {
  useI18nStore.setState({ locale: 'en' });
});

describe('PendingToolCard', () => {
  it('names the workflow to start in German', () => {
    renderCard(tool({ type: 'start_workflow', subject: 'Tagesbericht', params: {} }), 'de');
    expect(screen.getByText('Workflow „Tagesbericht“ starten')).toBeInTheDocument();
    expect(screen.queryByText('English fallback')).not.toBeInTheDocument();
  });

  it('names the workflow to start in English', () => {
    renderCard(tool({ type: 'start_workflow', subject: 'Daily Report', params: {} }), 'en');
    expect(screen.getByText('Start workflow “Daily Report”')).toBeInTheDocument();
  });

  it('translates the task priority', () => {
    renderCard(tool({ type: 'create_todo', subject: 'Anna anrufen', params: { priority: 'HIGH' } }), 'de');
    expect(screen.getByText(`Aufgabe „Anna anrufen“ anlegen (Priorität: ${de.todos.priorities.HIGH})`)).toBeInTheDocument();
  });

  it('translates the task priority in English', () => {
    renderCard(tool({ type: 'create_todo', subject: 'Call Anna', params: { priority: 'LOW' } }), 'en');
    expect(screen.getByText(`Create task “Call Anna” (priority: ${en.todos.priorities.LOW})`)).toBeInTheDocument();
  });

  it('translates the target status', () => {
    renderCard(tool({ type: 'update_todo_status', subject: 'Login reparieren', params: { status: 'DONE' } }), 'de');
    expect(screen.getByText(`Status der Aufgabe „Login reparieren“ auf „${de.todos.statuses.DONE}“ setzen`)).toBeInTheDocument();
  });

  it('keeps the delete emphasis and marks the card as destructive', () => {
    const { container } = renderCard(tool({ type: 'delete_workflow', subject: 'Alt', params: {} }), 'de');
    expect(screen.getByText('Workflow „Alt“ LÖSCHEN')).toBeInTheDocument();
    expect(container.querySelector('svg.text-danger')).not.toBeNull();
  });

  it('shows the short id without quotes when the name could not be resolved', () => {
    renderCard(tool({ type: 'cancel_run', subject: '76662293…', params: { ref: 'id' } }), 'de');
    expect(screen.getByText('Lauf ID 76662293… abbrechen')).toBeInTheDocument();
  });

  it('names the run by its workflow', () => {
    renderCard(tool({ type: 'cancel_run', subject: 'Nightly', params: {} }), 'en');
    expect(screen.getByText('Cancel run of “Nightly”')).toBeInTheDocument();
  });

  it('falls back to the description for an older backend without subject', () => {
    renderCard(tool({ type: 'start_workflow', description: 'Start workflow (ID: 76662293…)' }), 'de');
    expect(screen.getByText('Start workflow (ID: 76662293…)')).toBeInTheDocument();
  });

  it('falls back to the description for an unknown type', () => {
    renderCard(tool({ type: 'launch_rocket', subject: 'Moon', params: {} }), 'de');
    expect(screen.getByText('English fallback')).toBeInTheDocument();
  });
});

describe('pendingToolText', () => {
  it('covers every known type in both languages', () => {
    const types = ['create_todo', 'update_todo_status', 'create_workflow', 'update_workflow',
      'delete_workflow', 'start_workflow', 'cancel_run', 'create_chain'];
    for (const t of [de, en]) {
      for (const type of types) {
        const text = pendingToolText(tool({ type, subject: 'X', params: { priority: 'MEDIUM', status: 'OPEN' } }), t);
        expect(text).not.toBe('English fallback');
        expect(text).not.toMatch(/\{\w+\}/);
      }
    }
    expect(pendingToolText(tool({ type: 'create_chain', subject: 'Release' }), de)).toBe('Kette „Release“ anlegen');
  });

  it('inserts names literally', () => {
    expect(pendingToolText(tool({ type: 'create_workflow', subject: 'Cost $& {status}' }), en))
      .toBe('Create workflow “Cost $& {status}”');
  });
});
