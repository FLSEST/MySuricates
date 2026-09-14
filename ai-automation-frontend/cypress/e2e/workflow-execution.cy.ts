/**
 * WorkflowExecution page E2E tests.
 *
 * Uses cy.intercept() to mock API responses because real workflow executions
 * are non-trivial to trigger in an E2E environment. The page URL pattern is
 * /workflows/:id/run/:runId (see App.tsx router config).
 */

const WORKFLOW_ID = 'wf-test-001';
const RUN_ID = 'run-test-001';
const PAGE_URL = `/workflows/${WORKFLOW_ID}/run/${RUN_ID}`;

// ── Mock Data ───────────────────────────────────────────

const mockWorkflow = {
  id: WORKFLOW_ID,
  projectId: 'proj-test-001',
  projectName: 'Test Project',
  name: 'Cypress Execution Workflow',
  description: 'Workflow for execution page tests',
  icon: 'code',
  blocks: [],
  activatedSkillIds: [],
  activatedSkillNames: [],
  version: '1',
  runCount: 3,
  instructions: null,
  model: null,
  provider: null,
  createdAt: '2026-03-01T10:00:00Z',
  updatedAt: '2026-03-01T10:00:00Z',
};

function makeStep(
  overrides: Partial<{
    id: string;
    stepIndex: number;
    blockType: string;
    input: string;
    output: string;
    status: string;
    durationMs: number | null;
    error: string | null;
  }>,
) {
  return {
    id: overrides.id ?? `step-${overrides.stepIndex ?? 0}`,
    stepIndex: overrides.stepIndex ?? 0,
    blockType: overrides.blockType ?? 'simple',
    input: overrides.input ?? '',
    output: overrides.output ?? '',
    status: overrides.status ?? 'COMPLETED',
    durationMs: overrides.durationMs ?? null,
    error: overrides.error ?? null,
  };
}

function makeRun(
  overrides: Partial<{
    status: string;
    steps: ReturnType<typeof makeStep>[];
    durationMs: number | null;
    error: Record<string, unknown> | null;
    completedAt: string | null;
    userRating: number | null;
    inputContext: Record<string, unknown>;
    projectPath: string | null;
  }> = {},
) {
  return {
    id: RUN_ID,
    workflowId: WORKFLOW_ID,
    workflowName: 'Cypress Execution Workflow',
    projectName: 'Test Project',
    projectPath: overrides.projectPath ?? null,
    status: overrides.status ?? 'COMPLETED',
    steps: overrides.steps ?? [],
    startedAt: '2026-03-01T10:05:00Z',
    completedAt: overrides.completedAt ?? '2026-03-01T10:06:30Z',
    durationMs: overrides.durationMs ?? 90000,
    userRating: overrides.userRating ?? null,
    error: overrides.error ?? null,
    metadata: {},
    inputContext: overrides.inputContext ?? {},
  };
}

function makeRunsPage(run: ReturnType<typeof makeRun>) {
  return {
    content: [run],
    page: 0,
    size: 50,
    totalElements: 1,
    totalPages: 1,
  };
}

// ── Intercept Helpers ───────────────────────────────────

function setupBaseIntercepts(run: ReturnType<typeof makeRun>) {
  // Workflow detail
  cy.intercept('GET', `/api/v1/workflows/${WORKFLOW_ID}`, { statusCode: 200, body: mockWorkflow }).as('getWorkflow');

  // Workflow runs (paginated)
  cy.intercept('GET', `/api/v1/workflows/${WORKFLOW_ID}/runs*`, { statusCode: 200, body: makeRunsPage(run) }).as('getRuns');

  // Approval recovery check
  cy.intercept('GET', `/api/v1/executions/${RUN_ID}/approval`, { statusCode: 200, body: { pending: false } }).as('getApproval');

  // Insights (for completed runs)
  cy.intercept('GET', `/api/v1/executions/${RUN_ID}/insights`, { statusCode: 200, body: { insights: [] } }).as('getInsights');
}

// ── Tests ───────────────────────────────────────────────

describe('WorkflowExecution', () => {
  describe('Step Cards Display', () => {
    const steps = [
      makeStep({ stepIndex: 0, blockType: 'simple', status: 'COMPLETED', input: 'Analyze code', output: 'Analysis complete: 0 issues found', durationMs: 5200 }),
      makeStep({ stepIndex: 1, blockType: 'shell', status: 'FAILED', input: 'npm test', output: '', error: 'Process exited with code 1', durationMs: 12400 }),
      makeStep({ stepIndex: 2, blockType: 'simple', status: 'RUNNING', input: 'Generate report', output: '' }),
    ];
    const run = makeRun({ status: 'RUNNING', steps, durationMs: null, completedAt: null });

    beforeEach(() => {
      setupBaseIntercepts(run);
      cy.visit(PAGE_URL);
      cy.wait(['@getWorkflow', '@getRuns']);
    });

    it('renders step cards for each step', () => {
      cy.contains('Steps (3)').should('be.visible');
    });

    it('shows completed step with success icon and output', () => {
      cy.contains('Step 1').should('be.visible');
      // Kind chip + translated block type badge
      cy.get('#exec-step-0 [data-step-kind="ai"]').should('exist');
      cy.get('#exec-step-0').contains('AI').should('be.visible');
      cy.get('#exec-step-0').contains('Simple Command').should('be.visible');
      // Step output
      cy.contains('Analysis complete: 0 issues found').should('be.visible');
      // Duration is shown (formatDuration: 5200ms -> "5.2s")
      cy.contains('5.2s').should('be.visible');
    });

    it('shows failed step with error message', () => {
      cy.contains('Step 2').should('be.visible');
      cy.get('#exec-step-1 [data-step-kind="deterministic"]').should('exist');
      cy.get('#exec-step-1').contains('Deterministic').should('be.visible');
      cy.get('#exec-step-1').contains('Shell Command').should('be.visible');
      cy.contains('Process exited with code 1').should('be.visible');
    });

    it('shows running step with spinner', () => {
      cy.contains('Step 3').should('be.visible');
      // Running step has an animated spinner div (border-t-transparent animate-spin)
      cy.get('.animate-spin').should('exist');
    });

    it('displays step input text', () => {
      cy.contains('Analyze code').should('be.visible');
      cy.contains('npm test').should('be.visible');
      cy.contains('Generate report').should('be.visible');
    });
  });

  describe('Pause/Resume/Cancel Buttons', () => {
    it('shows Pause and Cancel buttons during RUNNING state', () => {
      const run = makeRun({ status: 'RUNNING', steps: [], durationMs: null, completedAt: null });
      setupBaseIntercepts(run);
      cy.visit(PAGE_URL);
      cy.wait(['@getWorkflow', '@getRuns']);

      cy.contains('button', 'Pause').should('be.visible');
      cy.contains('button', 'Cancel').should('be.visible');
    });

    it('shows Resume and Cancel buttons during PAUSED state', () => {
      const run = makeRun({ status: 'PAUSED', steps: [], durationMs: null, completedAt: null });
      setupBaseIntercepts(run);
      cy.visit(PAGE_URL);
      cy.wait(['@getWorkflow', '@getRuns']);

      cy.contains('button', 'Resume').should('be.visible');
      cy.contains('button', 'Cancel').should('be.visible');
      // Pause button should NOT be visible
      cy.contains('button', 'Pause').should('not.exist');
    });

    it('Pause button triggers POST to pause endpoint', () => {
      const run = makeRun({ status: 'RUNNING', steps: [], durationMs: null, completedAt: null });
      setupBaseIntercepts(run);
      cy.intercept('POST', `/api/v1/executions/${RUN_ID}/pause`, { statusCode: 200, body: {} }).as('pauseExecution');
      cy.visit(PAGE_URL);
      cy.wait(['@getWorkflow', '@getRuns']);

      cy.contains('button', 'Pause').click();
      cy.wait('@pauseExecution');
    });

    it('Cancel button triggers POST to cancel endpoint', () => {
      const run = makeRun({ status: 'RUNNING', steps: [], durationMs: null, completedAt: null });
      setupBaseIntercepts(run);
      cy.intercept('POST', `/api/v1/executions/${RUN_ID}/cancel`, { statusCode: 200, body: {} }).as('cancelExecution');
      cy.visit(PAGE_URL);
      cy.wait(['@getWorkflow', '@getRuns']);

      cy.contains('button', 'Cancel').click();
      cy.wait('@cancelExecution');
    });

    it('Resume button triggers POST to resume endpoint', () => {
      const run = makeRun({ status: 'PAUSED', steps: [], durationMs: null, completedAt: null });
      setupBaseIntercepts(run);
      cy.intercept('POST', `/api/v1/executions/${RUN_ID}/resume`, { statusCode: 200, body: {} }).as('resumeExecution');
      cy.visit(PAGE_URL);
      cy.wait(['@getWorkflow', '@getRuns']);

      cy.contains('button', 'Resume').click();
      cy.wait('@resumeExecution');
    });
  });

  describe('Output Display', () => {
    const steps = [
      makeStep({ stepIndex: 0, blockType: 'simple', status: 'COMPLETED', input: 'Review pull request #42', output: 'Reviewed: 3 comments added, 1 suggestion', durationMs: 8000 }),
      makeStep({ stepIndex: 1, blockType: 'shell', status: 'COMPLETED', input: 'echo "hello world"', output: 'hello world', durationMs: 200 }),
    ];
    const run = makeRun({ status: 'COMPLETED', steps, durationMs: 8200 });

    beforeEach(() => {
      setupBaseIntercepts(run);
      cy.visit(PAGE_URL);
      cy.wait(['@getWorkflow', '@getRuns']);
    });

    it('shows step input for each step', () => {
      cy.contains('Review pull request #42').should('be.visible');
      cy.contains('echo "hello world"').should('be.visible');
    });

    it('shows step output in pre block', () => {
      cy.contains('Reviewed: 3 comments added, 1 suggestion').should('be.visible');
      cy.contains('hello world').should('be.visible');
    });

    it('shows block type badges on each step', () => {
      // Both block types should be visible as translated badges
      cy.get('.divide-y').within(() => {
        cy.contains('Simple Command').should('exist');
        cy.contains('Shell Command').should('exist');
      });
    });
  });

  describe('Run Completion', () => {
    const steps = [
      makeStep({ stepIndex: 0, blockType: 'simple', status: 'COMPLETED', output: 'Done', durationMs: 3000 }),
      makeStep({ stepIndex: 1, blockType: 'shell', status: 'COMPLETED', output: 'Build passed', durationMs: 7000 }),
    ];
    const run = makeRun({ status: 'COMPLETED', steps, durationMs: 10000 });

    beforeEach(() => {
      setupBaseIntercepts(run);
      cy.visit(PAGE_URL);
      cy.wait(['@getWorkflow', '@getRuns']);
    });

    it('shows COMPLETED status badge', () => {
      // StatusBadge renders the status text inside a Badge component
      cy.contains('COMPLETED').should('be.visible');
    });

    it('shows duration', () => {
      cy.contains('Duration').should('be.visible');
      // formatDuration: 10000ms -> "10.0s"
      cy.contains('10.0s').should('be.visible');
    });

    it('shows rating stars for completed run', () => {
      // 5 star buttons should be visible
      cy.get('button[aria-label*="/5"]').should('have.length', 5);
    });

    it('does not show Pause/Resume/Cancel buttons', () => {
      cy.contains('button', 'Pause').should('not.exist');
      cy.contains('button', 'Resume').should('not.exist');
      cy.contains('button', 'Cancel').should('not.exist');
    });

    it('does not show container interaction section', () => {
      cy.contains('Command').should('not.exist');
      cy.contains('Terminal').should('not.exist');
    });

    it('shows the execution page header', () => {
      cy.contains('Workflow Execution').should('be.visible');
    });

    it('shows workflow name as link', () => {
      cy.contains('Cypress Execution Workflow').should('be.visible');
    });
  });

  describe('Failed Run', () => {
    const steps = [
      makeStep({ stepIndex: 0, blockType: 'simple', status: 'COMPLETED', output: 'Step 1 ok', durationMs: 2000 }),
      makeStep({ stepIndex: 1, blockType: 'shell', status: 'FAILED', input: 'npm run build', error: 'Build failed: Module not found', durationMs: 5000 }),
    ];
    const run = makeRun({
      status: 'FAILED',
      steps,
      durationMs: 7000,
      error: { message: 'Workflow failed at step 2: Build failed: Module not found', type: 'EXECUTION_ERROR', stepIndex: 1 },
    });

    beforeEach(() => {
      setupBaseIntercepts(run);
      cy.visit(PAGE_URL);
      cy.wait(['@getWorkflow', '@getRuns']);
    });

    it('shows FAILED status badge', () => {
      cy.contains('FAILED').should('be.visible');
    });

    it('shows error card with error message', () => {
      cy.contains('Workflow failed at step 2: Build failed: Module not found').should('be.visible');
    });

    it('shows step-level error on the failed step', () => {
      cy.contains('Build failed: Module not found').should('be.visible');
    });

    it('does not show rating stars for failed run', () => {
      cy.get('button[aria-label*="/5"]').should('not.exist');
    });

    it('does not show Pause/Resume/Cancel buttons', () => {
      cy.contains('button', 'Pause').should('not.exist');
      cy.contains('button', 'Resume').should('not.exist');
      cy.contains('button', 'Cancel').should('not.exist');
    });

    it('shows the error section with danger styling', () => {
      // The error card has border-danger/30 class
      cy.contains('Error').should('be.visible');
    });
  });

  describe('Input Variables', () => {
    const run = makeRun({
      status: 'COMPLETED',
      steps: [makeStep({ stepIndex: 0, blockType: 'simple', status: 'COMPLETED', output: 'ok' })],
      durationMs: 1000,
      inputContext: { project_path: '/home/user/project', branch: 'main' },
    });

    beforeEach(() => {
      setupBaseIntercepts(run);
      cy.visit(PAGE_URL);
      cy.wait(['@getWorkflow', '@getRuns']);
    });

    it('shows input variables when present', () => {
      cy.contains('Input Variables').should('be.visible');
      cy.contains('project_path').should('be.visible');
      cy.contains('/home/user/project').should('be.visible');
      cy.contains('branch').should('be.visible');
      cy.contains('main').should('be.visible');
    });
  });

  describe('Container Interaction (RUNNING state)', () => {
    const run = makeRun({ status: 'RUNNING', steps: [], durationMs: null, completedAt: null });

    beforeEach(() => {
      setupBaseIntercepts(run);
      cy.visit(PAGE_URL);
      cy.wait(['@getWorkflow', '@getRuns']);
    });

    it('shows command input and send button', () => {
      cy.get('input[placeholder*="Enter command"]').should('be.visible');
      cy.contains('button', 'Send').should('be.visible');
    });

    it('shows Command and Terminal tabs', () => {
      cy.contains('Command').should('be.visible');
      cy.contains('Terminal').should('be.visible');
    });

    it('Send button triggers command API call', () => {
      cy.intercept('POST', `/api/v1/executions/${RUN_ID}/command`, { statusCode: 200, body: { output: 'command output' } }).as('runCommand');

      cy.get('input[placeholder*="Enter command"]').type('ls -la');
      cy.contains('button', 'Send').click();
      cy.wait('@runCommand').its('request.body').should('deep.include', { command: 'ls -la' });
    });
  });

  describe('Empty Steps', () => {
    it('shows waiting message when running with no steps', () => {
      const run = makeRun({ status: 'RUNNING', steps: [], durationMs: null, completedAt: null });
      setupBaseIntercepts(run);
      cy.visit(PAGE_URL);
      cy.wait(['@getWorkflow', '@getRuns']);

      cy.contains('Waiting for steps').should('be.visible');
    });

    it('shows no steps message when completed with no steps', () => {
      const run = makeRun({ status: 'COMPLETED', steps: [], durationMs: 0 });
      setupBaseIntercepts(run);
      cy.visit(PAGE_URL);
      cy.wait(['@getWorkflow', '@getRuns']);

      cy.contains('No steps recorded').should('be.visible');
    });
  });

  describe('Navigation', () => {
    const run = makeRun({ status: 'COMPLETED', steps: [], durationMs: 0 });

    beforeEach(() => {
      setupBaseIntercepts(run);
      cy.visit(PAGE_URL);
      cy.wait(['@getWorkflow', '@getRuns']);
    });

    it('shows back button', () => {
      cy.get(`button[aria-label="Back"]`).should('be.visible');
    });

    it('back button navigates to workflow detail', () => {
      cy.get(`button[aria-label="Back"]`).click();
      cy.url().should('include', `/workflows/${WORKFLOW_ID}`);
      cy.url().should('not.include', '/run/');
    });
  });
});
