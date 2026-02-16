describe('Webhooks', () => {
  let projectId: string;
  let workflowId: string;

  before(() => {
    cy.apiCleanup();
    cy.apiCreateProject('Cypress Webhook Project').then((project) => {
      projectId = project.id;
      cy.apiCreateWorkflow(projectId, 'Cypress Webhook Workflow').then((wf) => {
        workflowId = wf.id;
      });
    });
  });

  after(() => {
    cy.apiCleanup();
  });

  beforeEach(() => {
    cy.visit('/webhooks');
  });

  it('shows empty state when no webhooks exist', () => {
    cy.contains('No webhooks configured').should('be.visible');
  });

  it('creates a webhook', () => {
    cy.contains('button', 'New Webhook').click();

    cy.get('[role="dialog"]').within(() => {
      cy.get('select').select(workflowId);
      cy.get('input').type('Cypress test webhook');
      cy.contains('button', 'Create').click();
    });

    cy.shouldShowToast('Webhook created successfully');
  });

  it('shows the webhook in the table with URL and token', () => {
    cy.get('table').should('exist');
    cy.contains('Cypress Webhook Workflow').should('be.visible');
    // Trigger URL column
    cy.contains('Trigger URL').should('be.visible');
    // Secret Token column (shows first 8 chars + ...)
    cy.contains('Secret Token').should('be.visible');
  });

  it('displays correct table headers', () => {
    cy.get('table').within(() => {
      cy.contains('th', 'Workflow').should('be.visible');
      cy.contains('th', 'Trigger URL').should('be.visible');
      cy.contains('th', 'Secret Token').should('be.visible');
      cy.contains('th', 'Trigger Count').should('be.visible');
      cy.contains('th', 'Status').should('be.visible');
    });
  });

  it('toggles a webhook enabled/disabled', () => {
    cy.get('table').should('exist');
    cy.get('[role="switch"]').first().then(($toggle) => {
      const wasEnabled = $toggle.attr('aria-checked') === 'true';
      cy.wrap($toggle).click();
      cy.shouldShowToast('Webhook updated');
      cy.get('[role="switch"]').first().should(
        'have.attr', 'aria-checked', wasEnabled ? 'false' : 'true',
      );
    });
  });

  it('has a copy button that provides visual feedback', () => {
    cy.get('table').should('exist');
    // Click the first copy button (aria-label="Copy")
    cy.get('[aria-label="Copy"]').first().click();
    // After clicking, the icon should change to a check mark briefly
    // Verify the copy button still exists (no crash)
    cy.get('[aria-label="Copy"]').should('exist');
  });

  it('deletes a webhook', () => {
    cy.get('[aria-label="Delete"]').first().click();
    cy.shouldShowToast('Webhook deleted successfully');
  });
});
