import * as api from '../support/api';

describe('Chains', () => {
  const runStamp = Date.now();
  let projectId: string;
  let workflowAId: string;
  let workflowAName: string;
  let workflowBId: string;
  let workflowBName: string;

  before(() => {
    cy.apiCreateProject(`Cypress Chain Project ${runStamp}`).then((project) => {
      projectId = project.id;
      cy.apiCreateWorkflow(projectId, `Cypress Chain Workflow A ${runStamp}`).then((workflow) => {
        workflowAId = workflow.id;
        workflowAName = workflow.name;
      });
      cy.apiCreateWorkflow(projectId, `Cypress Chain Workflow B ${runStamp}`).then((workflow) => {
        workflowBId = workflow.id;
        workflowBName = workflow.name;
      });
    });
  });

  beforeEach(() => {
    cy.visit('/chains');
  });

  // Executing the chain runs workflows A and B, and each run starts the real project container
  // `automation-<projectId>`. Remove it through the instance's API (not Docker directly): let the
  // runs settle, stop the container, then delete this spec's workflows and project.
  after(() => {
    if (!projectId) return;
    api.settleProjectRuns(projectId);
    api.stopProject(projectId);
    [workflowAId, workflowBId].filter(Boolean).forEach((id) => api.deleteWorkflow(id));
    api.deleteProject(projectId);
  });

  function addWorkflowEntry(workflowName: string) {
    cy.get('[role="dialog"]').find('input[placeholder="Select workflow..."]').clear().type(workflowName);
    cy.get('[role="dialog"]').contains('button', workflowName).click();
  }

  function openChainByName(name: string) {
    cy.get('input[placeholder="Search..."]').clear().type(name);
    cy.contains(name).click();
  }

  it('shows the chains page', () => {
    cy.contains('h1', 'Workflow Chains').should('be.visible');
    cy.contains('Group and execute workflows sequentially').should('be.visible');
  });

  it('creates, edits, executes, and deletes a chain via the UI', () => {
    const chainName = `Cypress Chain ${runStamp}`;
    const updatedName = `${chainName} Updated`;

    cy.contains('button', 'New Chain').click();

    cy.get('[role="dialog"]').within(() => {
      cy.contains('label', 'Name').parent().find('input').clear().type(chainName);
      cy.contains('label', 'Description').parent().find('input').clear().type('Cypress chain created in E2E');
    });

    addWorkflowEntry(workflowAName);
    addWorkflowEntry(workflowBName);

    cy.get('[role="dialog"]').contains('button', 'Save').click();

    cy.shouldShowToast('Chain created successfully');
    openChainByName(chainName);
    cy.contains('h2', chainName).should('be.visible');
    cy.contains('Entries (2)').should('be.visible');
    cy.contains(workflowAName).should('be.visible');
    cy.contains(workflowBName).should('be.visible');

    cy.contains('button', 'Execute').parent().find('button').eq(0).click();

    cy.get('[role="dialog"]').within(() => {
      cy.contains('label', 'Name').parent().find('input').clear().type(updatedName);
      cy.contains('label', 'Description').parent().find('input').clear().type('Updated from Cypress');
      cy.contains('button', 'Save').click();
    });

    cy.shouldShowToast('Chain updated successfully');
    cy.get('input[placeholder="Search..."]').clear().type(updatedName);
    cy.contains(updatedName).should('be.visible');

    openChainByName(updatedName);
    cy.contains('button', 'Execute').click();
    cy.url({ timeout: 15000 }).should('match', /\/chains\/[a-f0-9-]+\/run\/[a-f0-9-]+$/);
    cy.contains('h1', 'Chain Execution', { timeout: 15000 }).should('be.visible');
    // Delete the chain only once its run is over — otherwise the run keeps starting workflows.
    cy.url().then((url) => {
      const [, chainId, runId] = url.match(/\/chains\/([a-f0-9-]+)\/run\/([a-f0-9-]+)$/) ?? [];
      api.settleChainRun(chainId, runId);
    });

    cy.visit('/chains');
    openChainByName(updatedName);
    cy.contains('button', 'Execute').parent().find('button').eq(1).click();
    cy.get('[role="dialog"]').contains('button', 'Delete').click();

    cy.shouldShowToast('Chain deleted successfully');
    cy.contains(updatedName).should('not.exist');
  });
});
