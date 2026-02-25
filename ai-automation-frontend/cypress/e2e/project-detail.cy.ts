describe('Project Detail', () => {
  let projectId: string;

  before(() => {
    cy.apiCleanup();
    cy.apiCreateProject('Cypress Detail Project').then((project) => {
      projectId = project.id;
    });
  });

  after(() => {
    cy.apiCleanup();
  });

  beforeEach(() => {
    cy.visit(`/projects/${projectId}`);
  });

  it('shows project name in header', () => {
    cy.contains('Cypress Detail Project').should('be.visible');
  });

  it('shows back button', () => {
    cy.get('[aria-label="Back"]').should('exist');
  });

  it('shows project info card', () => {
    cy.contains('Type').should('be.visible');
    // Project type is translated — "Code" not "CODE"
    cy.contains('Code').should('be.visible');
    cy.contains('Created').should('be.visible');
    cy.contains('Updated').should('be.visible');
  });

  it('shows container status card', () => {
    cy.contains('Status').should('be.visible');
  });

  it('shows edit and delete buttons', () => {
    cy.contains('button', 'Edit').should('be.visible');
    cy.contains('button', 'Delete').should('be.visible');
  });

  it('opens edit modal with pre-filled data', () => {
    cy.contains('button', 'Edit').click();
    cy.get('[role="dialog"]').should('be.visible');
    cy.get('[role="dialog"]').within(() => {
      cy.get('input').first().should('have.value', 'Cypress Detail Project');
    });
  });

  it('edits project name', () => {
    cy.contains('button', 'Edit').click();
    cy.get('[role="dialog"]').within(() => {
      cy.get('input').first().clear().type('Cypress Renamed Project');
      cy.contains('button', 'Save').click();
    });
    cy.shouldShowToast('Project updated');
    cy.contains('Cypress Renamed Project').should('be.visible');

    // Rename back for other tests
    cy.contains('button', 'Edit').click();
    cy.get('[role="dialog"]').within(() => {
      cy.get('input').first().clear().type('Cypress Detail Project');
      cy.contains('button', 'Save').click();
    });
    cy.shouldShowToast('Project updated');
  });

  it('edits project subtitle and description', () => {
    cy.contains('button', 'Edit').click();
    cy.get('[role="dialog"]').within(() => {
      // Subtitle is the second input field
      cy.get('input').eq(1).clear().type('v1.0 Beta');
      cy.get('textarea').first().clear().type('A test project for Cypress E2E.');
      cy.contains('button', 'Save').click();
    });
    cy.shouldShowToast('Project updated');
  });

  it('changes project type', () => {
    cy.contains('button', 'Edit').click();
    cy.get('[role="dialog"]').within(() => {
      cy.get('select').select('RESEARCH');
      cy.contains('button', 'Save').click();
    });
    cy.shouldShowToast('Project updated');
    cy.contains('Research').should('be.visible');

    // Change back
    cy.contains('button', 'Edit').click();
    cy.get('[role="dialog"]').within(() => {
      cy.get('select').select('CODE');
      cy.contains('button', 'Save').click();
    });
  });

  it('cancels edit modal without saving', () => {
    cy.contains('button', 'Edit').click();
    cy.get('[role="dialog"]').within(() => {
      cy.get('input').first().clear().type('Should Not Save');
      cy.contains('button', 'Cancel').click();
    });
    cy.get('[role="dialog"]').should('not.exist');
    cy.contains('Should Not Save').should('not.exist');
  });

  it('shows workflows section', () => {
    cy.contains('Workflows').should('be.visible');
  });

  describe('with workflows in project', () => {
    before(() => {
      cy.apiCreateWorkflow(projectId, 'Cypress Project WF 1');
      cy.apiCreateWorkflow(projectId, 'Cypress Project WF 2');
    });

    it('lists project workflows', () => {
      cy.visit(`/projects/${projectId}`);
      cy.contains('Cypress Project WF 1').should('be.visible');
      cy.contains('Cypress Project WF 2').should('be.visible');
    });

    it('navigates to workflow detail on click', () => {
      cy.visit(`/projects/${projectId}`);
      cy.contains('Cypress Project WF 1').click();
      cy.url().should('match', /\/workflows\/[a-f0-9-]+$/);
    });
  });

  it('shows start container button for stopped project', () => {
    cy.contains('button', 'Start Container').should('be.visible');
  });

  it('shows delete confirmation modal', () => {
    cy.contains('button', 'Delete').click();
    cy.contains('This action cannot be undone').should('be.visible');
    // Cancel without deleting
    cy.get('[role="dialog"]').contains('button', 'Cancel').click();
    cy.get('[role="dialog"]').should('not.exist');
  });
});
