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
    // The full URL and token are shown once, right after creating
    cy.get('[role="dialog"]').within(() => {
      cy.contains('Token shown only now').should('be.visible');
      cy.contains('button', 'Close').click();
    });
    cy.get('[role="dialog"]').should('not.exist');
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

  // The table shows the token masked; copying happens in the one-time reveal after creating or
  // regenerating it
  it('offers copy buttons in the one-time reveal after regenerating the token', () => {
    cy.get('table').should('exist');
    cy.get('[aria-label="Regenerate token"]').first().click();
    cy.get('[role="dialog"]').contains('button', 'Regenerate token').click();
    cy.get('[role="dialog"]').within(() => {
      cy.contains('Token shown only now').should('be.visible');
      // One for the trigger URL, one for the token
      cy.get('[aria-label="Copy"]').should('have.length', 2).first().click();
      // After clicking, the icon should change to a check mark briefly; the button stays (no crash)
      cy.get('[aria-label="Copy"]').should('exist');
      cy.contains('button', 'Close').click();
    });
    cy.get('[role="dialog"]').should('not.exist');
  });

  it('deletes a webhook after confirmation', () => {
    cy.get('[aria-label="Delete"]').first().click();
    cy.get('[role="dialog"]').contains('button', 'Delete').click();
    cy.shouldShowToast('Webhook deleted successfully');
  });
});
