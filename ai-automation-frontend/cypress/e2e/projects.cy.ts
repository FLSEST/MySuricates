describe('Projects', () => {
  before(() => {
    cy.apiCleanup();
  });

  after(() => {
    cy.apiCleanup();
  });

  beforeEach(() => {
    cy.visit('/projects');
  });

  it('shows empty state when no projects exist', () => {
    cy.contains('No projects yet').should('be.visible');
  });

  it('opens and closes the create modal', () => {
    cy.contains('button', 'New Project').click();
    cy.contains('Project Name').should('be.visible');
    cy.contains('button', 'Cancel').click();
    cy.contains('Project Name').should('not.exist');
  });

  it('disables the Create button when required fields are empty', () => {
    cy.contains('button', 'New Project').click();
    cy.get('[role="dialog"]').contains('button', 'Create').should('be.disabled');
  });

  it('creates a project via the modal', () => {
    const name = `Cypress Project ${Date.now()}`;

    cy.contains('button', 'New Project').click();
    cy.get('[role="dialog"]').within(() => {
      cy.get('input').first().type(name);
      cy.get('input').last().type('/tmp/cypress-project');
      cy.contains('button', 'Create').click();
    });

    cy.shouldShowToast('Project created successfully');
    cy.contains(name).should('be.visible');
  });

  it('filters projects by search', () => {
    // Create two projects via API
    cy.apiCreateProject('Cypress Alpha Project');
    cy.apiCreateProject('Cypress Beta Project');
    cy.visit('/projects');

    cy.get('input[placeholder="Search..."]').type('Alpha');
    cy.contains('Cypress Alpha Project').should('be.visible');
    cy.contains('Cypress Beta Project').should('not.exist');
  });

  it('navigates to project detail page', () => {
    cy.apiCreateProject('Cypress Detail Project').then((project) => {
      cy.visit('/projects');
      cy.contains('Cypress Detail Project').click();
      cy.url().should('include', `/projects/${project.id}`);
    });
  });

  it('shows container start/stop buttons on project cards', () => {
    cy.apiCreateProject('Cypress Container Project');
    cy.visit('/projects');
    // Projects start in STOPPED state, so Start Container should be visible
    cy.contains('Start Container').should('be.visible');
  });

  it('deletes a project from the detail page', () => {
    cy.apiCreateProject('Cypress Delete Project').then((project) => {
      cy.visit(`/projects/${project.id}`);
      // The page has Edit and Delete buttons; click the danger Delete button
      cy.contains('button', 'Delete').click();
      cy.contains('This action cannot be undone').should('be.visible');
      cy.get('[role="dialog"]').contains('button', 'Delete').click();
      cy.shouldShowToast('Project deleted successfully');
      cy.url().should('include', '/projects');
    });
  });
});
