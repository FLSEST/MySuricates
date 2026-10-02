import { failOnUnhandledRequest } from '../../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { en } from '../../../i18n/en';
import { de } from '../../../i18n/de';
import { useI18nStore } from '../../../i18n';
import { SealBanner } from '../SealBanner';
import { useSealStatus } from '../../../hooks/useSealStatus';
import type { Workflow, WorkflowSealStatus } from '../../../types';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => { localStorage.clear(); useI18nStore.setState({ locale: 'en' }); });

function workflow(overrides: Partial<Workflow> = {}): Workflow {
  return {
    id: 'wf-1', projectId: 'p1', projectName: 'P', name: 'Deploy', description: '', icon: '🚀', blocks: [],
    activatedSkillIds: [], activatedSkillNames: [], version: '1', runCount: 0, instructions: null, model: null,
    provider: null, effort: null, createdAt: '2026-09-14T10:00:00Z', updatedAt: '2026-09-14T10:00:00Z', ...overrides,
  };
}

function status(overrides: Partial<WorkflowSealStatus> = {}): WorkflowSealStatus {
  return {
    workflowId: 'wf-1', workflowName: 'Deploy', state: 'INTACT', problem: 'NONE', detail: null, changedSkills: [],
    skillsPinned: true, signed: true, pinnedSkillCount: 2, sealedAt: '2026-09-20T08:00:00Z', refusedStarts: 0,
    lastRefusedAt: null, lastRefusal: null, ...overrides,
  };
}

describe('SealBanner', () => {
  it('renders nothing for an unsealed workflow without refused starts', () => {
    renderWithProviders(<SealBanner workflow={workflow()} />);
    expect(screen.queryByTestId('seal-banner')).not.toBeInTheDocument();
  });

  it('says what an intact seal locks, including the pinned skills', () => {
    renderWithProviders(<SealBanner workflow={workflow({ sealed: true, sealedAt: '2026-09-20T08:00:00Z' })} status={status()} />);

    const banner = screen.getByTestId('seal-banner');
    expect(banner).toHaveAttribute('data-state', 'intact');
    expect(banner).toHaveTextContent(en.workflows.sealView.intactBody.replace('{count}', '2'));
    expect(screen.queryByTestId('seal-legacy')).not.toBeInTheDocument();
  });

  it('offers to pin the skills of a seal that predates skill checking', () => {
    renderWithProviders(<SealBanner workflow={workflow({ sealed: true })} status={status({ skillsPinned: false, pinnedSkillCount: 0 })} />);

    expect(screen.getByTestId('seal-legacy')).toHaveTextContent(en.workflows.sealView.legacyBody);
    expect(screen.getByRole('button', { name: new RegExp(en.workflows.sealView.pinSkills) })).toBeInTheDocument();
  });

  it('explains a broken seal: changed skills, refused starts and what to do', () => {
    const lastRefusedAt = new Date(Date.now() - 60_000).toISOString();
    renderWithProviders(<SealBanner workflow={workflow({ sealed: true })} status={status({
      state: 'VIOLATED', problem: 'SKILLS_CHANGED',
      changedSkills: [{ id: 's1', name: 'Reviewer' }, { id: 'abcdef1234', name: null }],
      refusedStarts: 3, lastRefusedAt, lastRefusal: "Run refused — sealed workflow 'Deploy'",
    })} />);

    const banner = screen.getByRole('alert');
    expect(banner).toHaveAttribute('data-state', 'violated');
    expect(banner).toHaveTextContent(en.workflows.sealView.violatedTitle);
    expect(banner).toHaveTextContent('Reviewer');
    expect(banner).toHaveTextContent(`${en.workflows.sealView.deletedSkill} (abcdef12)`);
    expect(banner).toHaveTextContent(en.workflows.sealView.violatedAction);
    expect(screen.getByTestId('seal-refused')).toHaveTextContent(/^3 start\(s\) refused/);
  });

  it('shows refused starts of an unsealed workflow — they leave no run behind', () => {
    renderWithProviders(<SealBanner workflow={workflow()} status={status({
      state: 'NOT_SEALED', skillsPinned: false, pinnedSkillCount: 0, sealedAt: null,
      refusedStarts: 1, lastRefusedAt: new Date().toISOString(), lastRefusal: 'Run refused — unattended run (SCHEDULE)',
    })} />);

    expect(screen.getByTestId('seal-banner')).toHaveAttribute('data-state', 'refused');
    expect(screen.getByTestId('seal-banner')).toHaveTextContent('unattended run (SCHEDULE)');
  });

  it('says when a seal is not signed', () => {
    renderWithProviders(<SealBanner workflow={workflow({ sealed: true })} status={status({ signed: false })} />);

    expect(screen.getByTestId('seal-unsigned')).toHaveTextContent(en.workflows.sealView.unsignedBody);
  });

  // 23635ef6: the app's own dialog instead of window.confirm
  it('unseals from the banner only after confirmation', async () => {
    const unsealed: string[] = [];
    server.use(http.post('/api/v1/workflows/wf-1/unseal', () => {
      unsealed.push('wf-1');
      return HttpResponse.json(workflow());
    }));
    renderWithProviders(<SealBanner workflow={workflow({ sealed: true })} status={status({ state: 'VIOLATED', problem: 'DEFINITION_CHANGED' })} />);

    fireEvent.click(within(screen.getByRole('alert')).getByRole('button', { name: en.workflows.unseal }));
    const dialog = screen.getByRole('dialog', { name: en.workflows.unseal });
    expect(within(dialog).getByText(en.workflows.unsealConfirm)).toBeInTheDocument();
    fireEvent.keyDown(window, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(unsealed).toEqual([]);

    fireEvent.click(within(screen.getByRole('alert')).getByRole('button', { name: en.workflows.unseal }));
    fireEvent.click(within(screen.getByRole('dialog', { name: en.workflows.unseal })).getByRole('button', { name: en.workflows.unseal }));
    await waitFor(() => expect(unsealed).toEqual(['wf-1']));
  });

  it('names a forged signature as the reason', () => {
    renderWithProviders(<SealBanner workflow={workflow({ sealed: true })} status={status({
      state: 'VIOLATED', problem: 'SIGNATURE_INVALID', signed: true,
    })} />);

    expect(screen.getByRole('alert')).toHaveTextContent(en.workflows.sealView.problemSignature);
  });

  // 2b73ff48: the seal status sends the refusal reasons as keys + params. The frontend goes live before the
  // backend restarts, so the old answer (only English text) must keep working — both via the real request.
  describe('refusal reasons from /workflows/seal-status', () => {
    beforeEach(() => useI18nStore.setState({ locale: 'de' }));

    function FromServer() {
      const sealed = useSealStatus('wf-1');
      return <SealBanner workflow={workflow({ sealed: true })} status={sealed} />;
    }

    const english = "Sealed workflow 'Deploy' was not started: its skills could not be verified (store down)";
    const violated = {
      ...status({ state: 'VIOLATED', problem: 'UNVERIFIABLE', detail: 'its skills could not be verified (store down)' }),
      refusedStarts: 1, lastRefusedAt: new Date(Date.now() - 60_000).toISOString(),
      lastRefusal: "Run refused — sealed workflow 'Deploy': its skills could not be verified (store down)",
    };

    it('shows the English text from a backend without keys', async () => {
      server.use(http.get('/api/v1/workflows/seal-status', () => HttpResponse.json([violated])));
      renderWithProviders(<FromServer />);

      expect(await screen.findByTestId('seal-cause')).toHaveTextContent('its skills could not be verified (store down)');
      expect(screen.getByTestId('seal-last-refusal')).toHaveTextContent(violated.lastRefusal);
    });

    it('translates cause and latest refusal from a backend with keys', async () => {
      const params = { name: 'Deploy', error: 'store down' };
      server.use(http.get('/api/v1/workflows/seal-status', () => HttpResponse.json([{
        ...violated, refusalKey: 'SEAL_SKILLS_UNVERIFIABLE', refusalParams: params,
        lastRefusalCode: 'WORKFLOW_TAMPERED', lastRefusalKey: 'SEAL_SKILLS_UNVERIFIABLE', lastRefusalParams: params,
      }])));
      renderWithProviders(<FromServer />);

      const german = de.errors.messages.SEAL_SKILLS_UNVERIFIABLE.replace('{name}', 'Deploy').replace('{error}', 'store down');
      expect(await screen.findByTestId('seal-cause')).toHaveTextContent(german);
      expect(screen.getByTestId('seal-last-refusal')).toHaveTextContent(german);
      expect(screen.getByRole('alert')).not.toHaveTextContent(english);
    });

    it('translates an unattended refusal of an unsealed workflow by its code', async () => {
      server.use(http.get('/api/v1/workflows/seal-status', () => HttpResponse.json([{
        ...status({ state: 'NOT_SEALED', sealedAt: null }), refusedStarts: 1, lastRefusedAt: new Date().toISOString(),
        lastRefusal: "Run refused — unattended run (SCHEDULE) of unsealed workflow 'Deploy'",
        lastRefusalCode: 'UNATTENDED_REQUIRES_SEAL', lastRefusalParams: { name: 'Deploy', origin: 'SCHEDULE' },
      }])));
      renderWithProviders(<UnsealedFromServer />);

      expect(await screen.findByTestId('seal-last-refusal')).toHaveTextContent(
        de.errors.codes.UNATTENDED_REQUIRES_SEAL.replace('{name}', 'Deploy').replace('{origin}', 'SCHEDULE'));
    });

    function UnsealedFromServer() {
      const sealed = useSealStatus('wf-1');
      return <SealBanner workflow={workflow()} status={sealed} />;
    }
  });
});
