import { failOnUnhandledRequest } from '../../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from 'vitest';
import { fireEvent, screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { WorkflowWidgetConfigForm } from '../WorkflowWidgetConfigForm';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => useI18nStore.setState({ locale: 'en' }));

describe('WorkflowWidgetConfigForm', () => {
  it('offers the workflows and submits the chosen id', async () => {
    const onSubmit = vi.fn();
    const onCancel = vi.fn();
    renderWithProviders(<WorkflowWidgetConfigForm onSubmit={onSubmit} onCancel={onCancel} />);

    const select = await screen.findByRole('combobox', { name: 'Workflow' });
    // findByRole resolves as soon as the select itself exists, which is before the workflow list has
    // loaded (the select is rendered unconditionally, only disabled while loading) — await the option
    // too, or the next assertion races the pending /workflows fetch.
    expect(await within(select).findByRole('option', { name: 'Test Workflow' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Add' })).toBeDisabled();

    fireEvent.change(select, { target: { value: 'w1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));

    expect(onSubmit).toHaveBeenCalledWith({ workflowId: 'w1' });

    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onCancel).toHaveBeenCalled();
  });

  it('shows workflow names as text and skips ids the endpoint would refuse', async () => {
    server.use(http.get('/api/v1/workflows', () => HttpResponse.json([
      { id: 'w1', projectId: 'p1', projectName: 'P', name: '<b>Bold</b> workflow', description: '', icon: '', blocks: [], version: '1', metadata: {}, activatedSkillIds: [], activatedSkillNames: [], runCount: 0, instructions: null, model: null, provider: null, effort: null, templateId: null, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' },
      { id: 'NOT AN ID', projectId: 'p1', projectName: 'P', name: 'Refused', description: '', icon: '', blocks: [], version: '1', metadata: {}, activatedSkillIds: [], activatedSkillNames: [], runCount: 0, instructions: null, model: null, provider: null, effort: null, templateId: null, createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z' },
    ])));

    const { container } = renderWithProviders(<WorkflowWidgetConfigForm onSubmit={vi.fn()} onCancel={vi.fn()} />);

    expect(await screen.findByRole('option', { name: '<b>Bold</b> workflow' })).toBeInTheDocument();
    expect(container.querySelector('b')).toBeNull();
    expect(screen.queryByRole('option', { name: 'Refused' })).not.toBeInTheDocument();
  });
});
