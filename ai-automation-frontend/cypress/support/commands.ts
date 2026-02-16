import * as api from './api';

declare global {
  // eslint-disable-next-line @typescript-eslint/no-namespace
  namespace Cypress {
    interface Chainable {
      /** Create a project via API, yields the project object */
      apiCreateProject(name?: string): Chainable;

      /** Create a workflow via API, yields the workflow object */
      apiCreateWorkflow(projectId: string, name?: string): Chainable;

      /** Delete all test data (webhooks, schedules, workflows, projects) */
      apiCleanup(): Chainable<void>;

      /** Assert a toast notification appears with the given text */
      shouldShowToast(text: string): Chainable;

      /** Click a sidebar navigation link by its label text */
      navTo(label: string): Chainable;

      /** Type into an input identified by its label text */
      fillInput(label: string, value: string): Chainable;

      /** Select an option in a <select> identified by its label text */
      selectOption(label: string, value: string): Chainable;
    }
  }
}

// ── API Commands ────────────────────────────────────────

Cypress.Commands.add('apiCreateProject', (name?: string) => {
  return api.createProject(name);
});

Cypress.Commands.add('apiCreateWorkflow', (projectId: string, name?: string) => {
  return api.createWorkflow(projectId, name);
});

Cypress.Commands.add('apiCleanup', () => {
  api.cleanupAll();
});

// ── UI Commands ─────────────────────────────────────────

Cypress.Commands.add('shouldShowToast', (text: string) => {
  return cy.contains(text, { timeout: 6000 }).should('be.visible');
});

Cypress.Commands.add('navTo', (label: string) => {
  return cy.get('aside').contains(label).click();
});

Cypress.Commands.add('fillInput', (label: string, value: string) => {
  return cy.contains('label', label).parent().find('input').clear().type(value);
});

Cypress.Commands.add('selectOption', (label: string, value: string) => {
  return cy.contains('label', label).parent().find('select').select(value);
});
