/**
 * Direct HTTP helpers for test-data setup against the real backend.
 * All calls go to http://localhost:9090/api/v1 (bypassing the Vite proxy).
 */

const API = 'http://localhost:9090/api/v1';

// ── Projects ────────────────────────────────────────────

export function getProjects() {
  return cy.request('GET', `${API}/projects`).its('body');
}

export function createProject(
  name = `Cypress Project ${Date.now()}`,
  type = 'CODE',
  path = '/tmp/cypress-test',
) {
  return cy.request('POST', `${API}/projects`, { name, type, path }).its('body');
}

export function deleteProject(id: string) {
  return cy.request({ method: 'DELETE', url: `${API}/projects/${id}`, failOnStatusCode: false });
}

// ── Workflows ───────────────────────────────────────────

export function getWorkflows() {
  return cy.request('GET', `${API}/workflows`).its('body');
}

export function createWorkflow(
  projectId: string,
  name = `Cypress Workflow ${Date.now()}`,
  description = 'Cypress test data',
) {
  return cy.request('POST', `${API}/workflows`, { projectId, name, description }).its('body');
}

export function deleteWorkflow(id: string) {
  return cy.request({ method: 'DELETE', url: `${API}/workflows/${id}`, failOnStatusCode: false });
}

// ── Schedules ───────────────────────────────────────────

export function getSchedules() {
  return cy.request('GET', `${API}/schedules`).its('body');
}

export function createSchedule(
  workflowId: string,
  cronExpression = '0 0 * * *',
  description = 'Cypress test schedule',
) {
  return cy.request('POST', `${API}/schedules`, { workflowId, cronExpression, description }).its('body');
}

export function deleteSchedule(id: string) {
  return cy.request({ method: 'DELETE', url: `${API}/schedules/${id}`, failOnStatusCode: false });
}

// ── Webhooks ────────────────────────────────────────────

export function getWebhooks() {
  return cy.request('GET', `${API}/webhooks`).its('body');
}

export function createWebhook(workflowId: string, description = 'Cypress test webhook') {
  return cy.request('POST', `${API}/webhooks`, { workflowId, description }).its('body');
}

export function deleteWebhook(id: string) {
  return cy.request({ method: 'DELETE', url: `${API}/webhooks/${id}`, failOnStatusCode: false });
}

// ── Skills ──────────────────────────────────────────────

export function getSkills() {
  return cy.request('GET', `${API}/skills`).its('body');
}

// ── System ──────────────────────────────────────────────

export function getHealth() {
  return cy.request('GET', `${API}/system/health`).its('body');
}

// ── Cleanup ─────────────────────────────────────────────

/** Prefix used for all test-created data. Only data with this prefix gets cleaned up. */
export const TEST_PREFIX = 'Cypress';

function isTestData(name: string): boolean {
  return name.startsWith(TEST_PREFIX);
}

/**
 * Deletes only Cypress test data (names starting with "Cypress") in dependency order:
 * Webhooks -> Schedules -> Workflows -> Projects
 *
 * User-created data is never touched.
 */
export function cleanupAll() {
  // Webhooks first (depend on workflows)
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
