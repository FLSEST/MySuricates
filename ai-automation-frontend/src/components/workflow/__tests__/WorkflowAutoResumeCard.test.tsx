import { failOnUnhandledRequest } from '../../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, waitFor, fireEvent } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { de } from '../../../i18n/de';
import { en } from '../../../i18n/en';
import { WorkflowAutoResumeCard } from '../WorkflowAutoResumeCard';
import type { Workflow, WorkflowMetadata } from '../../../types';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => useI18nStore.setState({ locale: 'en' }));

function workflow(metadata?: WorkflowMetadata): Workflow {
  return {
    id: 'wf-1', projectId: 'p1', projectName: 'P', name: 'Nightly', description: '', icon: '🌙', blocks: [],
    activatedSkillIds: [], activatedSkillNames: [], version: '1', runCount: 0, instructions: null, model: null,
    provider: null, effort: null, createdAt: '2026-09-26T10:00:00Z', updatedAt: '2026-09-26T10:00:00Z', metadata,
  };
}

/** Captures the PUT bodies the switch sends through the real update hook. */
function captureUpdates(): unknown[] {
  const bodies: unknown[] = [];
  server.use(http.put('/api/v1/workflows/:id', async ({ request, params }) => {
    const body = await request.json();
    bodies.push(body);
    return HttpResponse.json({ ...workflow(), id: params.id, metadata: (body as { metadata?: unknown }).metadata });
  }));
  return bodies;
}

describe('WorkflowAutoResumeCard (TODO 90d10ba1)', () => {
  it('is off by default and shows no repeat hint', () => {
    renderWithProviders(<WorkflowAutoResumeCard workflow={workflow()} />);

    expect(screen.getByRole('switch', { name: en.workflows.autoResume })).toHaveAttribute('aria-checked', 'false');
    expect(screen.queryByText(en.workflows.autoResumeHint)).toBeNull();
  });

  it('switching on writes metadata.autoResume.enabled only — other keys stay with the server merge', async () => {
    const bodies = captureUpdates();
    renderWithProviders(<WorkflowAutoResumeCard workflow={workflow({ autoRepair: { enabled: true } })} />);

    fireEvent.click(screen.getByRole('switch', { name: en.workflows.autoResume }));

    await waitFor(() => expect(bodies).toEqual([{ metadata: { autoResume: { enabled: true } } }]));
  });

  it('while on it warns that the interrupted block runs again, and switching off sends false', async () => {
    const bodies = captureUpdates();
    renderWithProviders(<WorkflowAutoResumeCard workflow={workflow({ autoResume: { enabled: true } })} />);

    expect(screen.getByText(en.workflows.autoResumeHint).closest('p')).toHaveClass('text-warning');
    fireEvent.click(screen.getByRole('switch', { name: en.workflows.autoResume }));

    await waitFor(() => expect(bodies).toEqual([{ metadata: { autoResume: { enabled: false } } }]));
  });

  it('speaks German too', () => {
    useI18nStore.setState({ locale: 'de' });
    renderWithProviders(<WorkflowAutoResumeCard workflow={workflow({ autoResume: { enabled: true } })} />);

    expect(screen.getByRole('switch', { name: de.workflows.autoResume })).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByText(de.workflows.autoResumeHint)).toBeInTheDocument();
  });
});
