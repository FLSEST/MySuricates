/**
 * Direct HTTP helpers for test-data setup against the real backend.
 * All calls go to http://localhost:9090/api/v1 (bypassing the Vite proxy).
 */

const API = 'http://localhost:9090/api/v1';

function apiHeaders(): Record<string, string> {
  const headers: Record<string, string> = {};
  const apiKey = Cypress.env('API_KEY');
  if (apiKey) {
    headers['X-API-Key'] = apiKey;
  }
  return headers;
}

// ── Projects ────────────────────────────────────────────

export function getProjects() {
  return cy.request({ method: 'GET', url: `${API}/projects`, headers: apiHeaders() }).its('body');
}

export function createProject(
  name = `Cypress Project ${Date.now()}`,
  type = 'CODE',
  path = '/tmp/cypress-test',
) {
  return cy.request({ method: 'POST', url: `${API}/projects`, headers: apiHeaders(), body: { name, type, path } }).its('body');
}

export function deleteProject(id: string) {
  return cy.request({ method: 'DELETE', url: `${API}/projects/${id}`, headers: apiHeaders(), failOnStatusCode: false });
}

// ── Workflows ───────────────────────────────────────────

export function getWorkflows() {
  return cy.request({ method: 'GET', url: `${API}/workflows`, headers: apiHeaders() }).its('body');
}

export function createWorkflow(
  projectId: string,
  name = `Cypress Workflow ${Date.now()}`,
  description = 'Cypress test data',
) {
  return cy.request({ method: 'POST', url: `${API}/workflows`, headers: apiHeaders(), body: { projectId, name, description } }).its('body');
}

export function deleteWorkflow(id: string) {
  return cy.request({ method: 'DELETE', url: `${API}/workflows/${id}`, headers: apiHeaders(), failOnStatusCode: false });
}

// ── Schedules ───────────────────────────────────────────

export function getSchedules() {
  return cy.request({ method: 'GET', url: `${API}/schedules`, headers: apiHeaders() }).its('body');
}

export function createSchedule(
  workflowId: string,
  cronExpression = '0 0 * * *',
  description = 'Cypress test schedule',
) {
  return cy.request({ method: 'POST', url: `${API}/schedules`, headers: apiHeaders(), body: { workflowId, cronExpression, description } }).its('body');
}

export function deleteSchedule(id: string) {
  return cy.request({ method: 'DELETE', url: `${API}/schedules/${id}`, headers: apiHeaders(), failOnStatusCode: false });
}

// ── Webhooks ────────────────────────────────────────────

export function getWebhooks() {
  return cy.request({ method: 'GET', url: `${API}/webhooks`, headers: apiHeaders() }).its('body');
}

export function createWebhook(workflowId: string, description = 'Cypress test webhook') {
  return cy.request({ method: 'POST', url: `${API}/webhooks`, headers: apiHeaders(), body: { workflowId, description } }).its('body');
}

export function deleteWebhook(id: string) {
  return cy.request({ method: 'DELETE', url: `${API}/webhooks/${id}`, headers: apiHeaders(), failOnStatusCode: false });
}

// ── Todos ──────────────────────────────────────────────

export function getTodos() {
  return cy.request({ method: 'GET', url: `${API}/todos`, headers: apiHeaders() }).its('body');
}

export function createTodo(
  title = `Cypress Todo ${Date.now()}`,
  priority: 'LOW' | 'MEDIUM' | 'HIGH' = 'MEDIUM',
  tags: string[] = [],
  projectId?: string,
) {
  return cy.request({
    method: 'POST',
    url: `${API}/todos`,
    headers: apiHeaders(),
    body: { title, priority, tags, projectId, source: 'USER' },
  }).its('body');
}

export function deleteTodo(id: string) {
  return cy.request({ method: 'DELETE', url: `${API}/todos/${id}`, headers: apiHeaders(), failOnStatusCode: false });
}

// ── Executions ─────────────────────────────────────────

export function getExecutions() {
  return cy.request({ method: 'GET', url: `${API}/executions`, headers: apiHeaders() }).its('body');
}

// ── Skills ──────────────────────────────────────────────

export function getSkills() {
  return cy.request({ method: 'GET', url: `${API}/skills`, headers: apiHeaders() }).its('body');
}

// ── System ──────────────────────────────────────────────

export function getHealth() {
  return cy.request({ method: 'GET', url: `${API}/system/health`, headers: apiHeaders() }).its('body');
}

// ── Cleanup ─────────────────────────────────────────────

/** Prefix used for all test-created data. Only data with this prefix gets cleaned up. */
export const TEST_PREFIX = 'Cypress';

function isTestData(name: string | null | undefined): boolean {
  return !!name && name.startsWith(TEST_PREFIX);
}

/**
 * Deletes only Cypress test data (names starting with "Cypress") in dependency order:
 * Webhooks -> Schedules -> Workflows -> Projects
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
