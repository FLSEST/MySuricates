/**
 * Direct HTTP helpers for test-data setup against the selected E2E backend.
 * The target comes from the exposed `API_BASE_URL` (see ./target) so setup/cleanup and UI traffic
 * can point to the same instance (dev on :9090 or test on :8090).
 */

import { apiBaseUrl, apiKey } from './target';

function apiUrl(path: string): string {
  return `${apiBaseUrl()}${path}`;
}

function apiHeaders(): Record<string, string> {
  const headers: Record<string, string> = {};
  const key = apiKey();
  if (key) {
    headers['X-API-Key'] = key;
  }
  return headers;
}

/** Run states (workflow and chain runs) that can still start work. */
const ACTIVE_RUN_STATUSES = ['PENDING', 'RUNNING', 'PAUSED'];

// ── Projects ────────────────────────────────────────────

export function getProjects() {
  return cy.request({ method: 'GET', url: apiUrl('/projects'), headers: apiHeaders() }).its('body');
}

export function createProject(
  name = `Cypress Project ${Date.now()}`,
  type = 'CODE',
  path = '/tmp/cypress-test',
) {
  return cy.request({ method: 'POST', url: apiUrl('/projects'), headers: apiHeaders(), body: { name, type, path } }).its('body');
}

export function deleteProject(id: string) {
  return cy.request({ method: 'DELETE', url: apiUrl(`/projects/${id}`), headers: apiHeaders(), failOnStatusCode: false });
}

/**
 * Stops the project's container. A workflow run starts the container `automation-<projectId>` itself
 * without marking the project RUNNING, and older backends only stop a container on delete when the
 * project is RUNNING — so a spec whose runs start containers stops them explicitly before deleting.
 */
export function stopProject(id: string) {
  return cy.request({ method: 'POST', url: apiUrl(`/projects/${id}/stop`), headers: apiHeaders(), failOnStatusCode: false });
}

// ── Workflows ───────────────────────────────────────────

export function getWorkflows() {
  return cy.request({ method: 'GET', url: apiUrl('/workflows'), headers: apiHeaders() }).its('body');
}

export function createWorkflow(
  projectId: string,
  name = `Cypress Workflow ${Date.now()}`,
  description = 'Cypress test data',
) {
  return cy.request({ method: 'POST', url: apiUrl('/workflows'), headers: apiHeaders(), body: { projectId, name, description } }).its('body');
}

export function deleteWorkflow(id: string) {
  return cy.request({ method: 'DELETE', url: apiUrl(`/workflows/${id}`), headers: apiHeaders(), failOnStatusCode: false });
}

// ── Chains ──────────────────────────────────────────────

export function getChains() {
  return cy.request({ method: 'GET', url: apiUrl('/chains'), headers: apiHeaders() }).its('body');
}

export function deleteChain(id: string) {
  return cy.request({ method: 'DELETE', url: apiUrl(`/chains/${id}`), headers: apiHeaders(), failOnStatusCode: false });
}

/**
 * Waits until the chain run is no longer active (one poll per second); after `attempts` polls it is
 * cancelled. Deleting a chain while its run still starts workflows leaves runs (and the project
 * container they start) behind.
 */
export function settleChainRun(chainId: string, runId: string, attempts = 30): void {
  cy
    .request({ method: 'GET', url: apiUrl(`/chains/${chainId}/runs/${runId}`), headers: apiHeaders(), failOnStatusCode: false })
    .then((resp) => {
      if (resp.status !== 200 || !ACTIVE_RUN_STATUSES.includes(resp.body.status)) return;
      if (attempts <= 1) {
        cy.request({ method: 'POST', url: apiUrl(`/chains/runs/${runId}/cancel`), headers: apiHeaders(), failOnStatusCode: false });
        return;
      }
      cy.wait(1000);
      settleChainRun(chainId, runId, attempts - 1);
    });
}

// ── Schedules ───────────────────────────────────────────

export function getSchedules() {
  return cy.request({ method: 'GET', url: apiUrl('/schedules'), headers: apiHeaders() }).its('body');
}

export function createSchedule(
  workflowId: string,
  cronExpression = '0 0 * * *',
  description = 'Cypress test schedule',
) {
  return cy.request({ method: 'POST', url: apiUrl('/schedules'), headers: apiHeaders(), body: { workflowId, cronExpression, description } }).its('body');
}

export function deleteSchedule(id: string) {
  return cy.request({ method: 'DELETE', url: apiUrl(`/schedules/${id}`), headers: apiHeaders(), failOnStatusCode: false });
}

// ── Webhooks ────────────────────────────────────────────

export function getWebhooks() {
  return cy.request({ method: 'GET', url: apiUrl('/webhooks'), headers: apiHeaders() }).its('body');
}

export function createWebhook(workflowId: string, description = 'Cypress test webhook') {
  return cy.request({ method: 'POST', url: apiUrl('/webhooks'), headers: apiHeaders(), body: { workflowId, description } }).its('body');
}

export function deleteWebhook(id: string) {
  return cy.request({ method: 'DELETE', url: apiUrl(`/webhooks/${id}`), headers: apiHeaders(), failOnStatusCode: false });
}

// ── Todos ──────────────────────────────────────────────

export function getTodos() {
  return cy.request({ method: 'GET', url: apiUrl('/todos'), headers: apiHeaders() }).then((resp) => {
    const body = resp.body;
    // Handle both paginated ({content: []}) and plain array responses
    return Array.isArray(body) ? body : (body.content ?? []);
  });
}

export function createTodo(
  title = `Cypress Todo ${Date.now()}`,
  priority: 'LOW' | 'MEDIUM' | 'HIGH' = 'MEDIUM',
  tags: string[] = [],
  projectId?: string,
) {
  return cy.request({
    method: 'POST',
    url: apiUrl('/todos'),
    headers: apiHeaders(),
    body: { title, priority, tags, projectId, source: 'USER' },
  }).its('body');
}

export function deleteTodo(id: string) {
  return cy.request({ method: 'DELETE', url: apiUrl(`/todos/${id}`), headers: apiHeaders(), failOnStatusCode: false });
}

// ── Executions ─────────────────────────────────────────

export function getExecutions() {
  return cy.request({ method: 'GET', url: apiUrl('/executions'), headers: apiHeaders() }).then((resp) => {
    const body = resp.body;
    return Array.isArray(body) ? body : (body.content ?? []);
  });
}

export function cancelExecution(id: string) {
  return cy.request({ method: 'POST', url: apiUrl(`/executions/${id}/cancel`), headers: apiHeaders(), failOnStatusCode: false });
}

/**
 * Waits until no run of the project is active any more; after `attempts` polls (one per second)
 * the remaining active runs are cancelled. A run still going would start the project container
 * again after the spec stopped it.
 */
export function settleProjectRuns(projectId: string, attempts = 30): void {
  cy
    .request({ method: 'GET', url: apiUrl(`/executions?projectId=${projectId}&size=100`), headers: apiHeaders() })
    .then((resp) => {
      const body = resp.body;
      const runs: { id: string; status: string }[] = Array.isArray(body) ? body : (body.content ?? []);
      const active = runs.filter((r) => ACTIVE_RUN_STATUSES.includes(r.status));
      if (active.length === 0) return;
      if (attempts <= 1) {
        active.forEach((r) => cancelExecution(r.id));
        return;
      }
      cy.wait(1000);
      settleProjectRuns(projectId, attempts - 1);
    });
}

// ── Skills ──────────────────────────────────────────────

export function getSkills() {
  return cy.request({ method: 'GET', url: apiUrl('/skills'), headers: apiHeaders() }).its('body');
}

// ── System ──────────────────────────────────────────────

export function getHealth() {
  return cy.request({ method: 'GET', url: apiUrl('/system/health'), headers: apiHeaders() }).its('body');
}

// ── Cleanup ─────────────────────────────────────────────

/** Prefix used for all test-created data. Only data with this prefix gets cleaned up. */
export const TEST_PREFIX = 'Cypress';

function isTestData(name: string | null | undefined): boolean {
  return !!name && name.startsWith(TEST_PREFIX);
}

/**
 * Deletes only Cypress test data (names starting with "Cypress") in dependency order:
 * Webhooks -> Schedules -> Chains -> Workflows -> Projects
 *
 * User-created data is never touched.
 */
export function cleanupAll() {
  // Todos first (no dependencies)
  getTodos().then((todos: { id: string; title: string }[]) => {
    todos
      .filter((t) => isTestData(t.title))
      .forEach((t) => deleteTodo(t.id));
  });

  // Webhooks (depend on workflows)
  getWebhooks().then((webhooks: { id: string; description: string; workflowName: string }[]) => {
    webhooks
      .filter((w) => isTestData(w.description) || isTestData(w.workflowName))
      .forEach((w) => deleteWebhook(w.id));
  });

  // Schedules (depend on workflows)
  getSchedules().then((schedules: { id: string; description: string; workflowName: string }[]) => {
    schedules
      .filter((s) => isTestData(s.description) || isTestData(s.workflowName))
      .forEach((s) => deleteSchedule(s.id));
  });

  // Chains (depend on workflows)
  getChains().then((chains: { id: string; name: string }[]) => {
    chains
      .filter((c) => isTestData(c.name))
      .forEach((c) => deleteChain(c.id));
  });

  // Workflows (depend on projects)
  getWorkflows().then((workflows: { id: string; name: string }[]) => {
    workflows
      .filter((w) => isTestData(w.name))
      .forEach((w) => deleteWorkflow(w.id));
  });

  // Projects last
  getProjects().then((projects: { id: string; name: string }[]) => {
    projects
      .filter((p) => isTestData(p.name))
      .forEach((p) => deleteProject(p.id));
  });
}
