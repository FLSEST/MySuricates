describe('Schedules', () => {
  let projectId: string;
  let workflowId: string;

  before(() => {
    cy.apiCleanup();
    cy.apiCreateProject('Schedule Test Project').then((project) => {
      projectId = project.id;
      cy.apiCreateWorkflow(projectId, 'Schedule Test Workflow').then((wf) => {
        workflowId = wf.id;
      });
    });
  });

  after(() => {
    cy.apiCleanup();
  });

  beforeEach(() => {
    cy.visit('/schedules');
  });

  it('shows empty state when no schedules exist', () => {
    cy.contains('No scheduled workflows').should('be.visible');
  });

  it('creates a schedule', () => {
    cy.contains('button', 'New Schedule').click();

    cy.get('[role="dialog"]').within(() => {
      cy.get('select').select(workflowId);
      cy.get('input').eq(0).type('0 0 * * *');
      cy.get('input').eq(1).type('Daily test schedule');
      cy.contains('button', 'Create').click();
    });

    cy.shouldShowToast('Schedule created successfully');
  });

  it('shows the schedule in the table', () => {
    cy.get('table').should('exist');
    cy.contains('Schedule Test Workflow').should('be.visible');
    cy.contains('0 0 * * *').should('be.visible');
  });

  it('displays correct table headers', () => {
    // Ensure we have a schedule first
    cy.get('table').within(() => {
      cy.contains('th', 'Workflow').should('be.visible');
      cy.contains('th', 'Cron Expression').should('be.visible');
      cy.contains('th', 'Next Run').should('be.visible');
      cy.contains('th', 'Last Run').should('be.visible');
      cy.contains('th', 'Status').should('be.visible');
    });
  });

  it('toggles a schedule enabled/disabled', () => {
    cy.get('table').should('exist');
    // Find the toggle switch in the table
    cy.get('[role="switch"]').first().then(($toggle) => {
      const wasEnabled = $toggle.attr('aria-checked') === 'true';
      cy.wrap($toggle).click();
      cy.shouldShowToast('Schedule updated');
      // Verify state changed
      cy.get('[role="switch"]').first().should(
        'have.attr', 'aria-checked', wasEnabled ? 'false' : 'true',
      );
    });
  });

  it('deletes a schedule', () => {
    cy.get('[aria-label="Delete"]').first().click();
    cy.shouldShowToast('Schedule deleted successfully');
  });
});
