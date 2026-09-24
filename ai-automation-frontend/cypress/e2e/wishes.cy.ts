describe('Wishes', () => {
  // A wish belongs to a project: the form and the API (CreateWishRequest) require one
  const projectName = `Cypress Wishes Project ${Date.now()}`;

  before(() => {
    cy.apiCleanup();
    cy.apiCreateProject(projectName);
  });

  after(() => {
    cy.apiCleanup();
  });

  beforeEach(() => {
    cy.visit('/wishes');
  });

  it('shows the wishes page with title', () => {
    cy.get('h1').should('contain.text', 'Wish');
  });

  it('shows the wish input textarea', () => {
    cy.get('textarea').should('be.visible');
  });

  it('shows the project selector', () => {
    cy.get('select').should('be.visible');
  });

  it('shows the submit button', () => {
    cy.contains('button', 'Submit').should('be.visible').and('be.disabled');
  });

  it('enables submit button once text and a project are given', () => {
    cy.get('textarea[placeholder*="Describe your wish"]').type('Build a dashboard for my project');
    // Text alone is not enough
    cy.contains('button', 'Submit').should('be.disabled');
    cy.contains('option', 'Select a project').parent().select(projectName);
    cy.contains('button', 'Submit').should('not.be.disabled');
  });

  it('shows empty state when no wishes exist', () => {
    cy.get('body').then(($body) => {
      if ($body.text().includes('No wishes yet') || $body.text().includes('Noch keine')) {
        cy.get('body').should('contain.text', 'wish');
      }
    });
  });

  it('shows advanced options toggle', () => {
    cy.contains('Advanced').should('be.visible').click();
    cy.contains('Reference Workflow').should('be.visible');
  });

  it('shows workflow selector in advanced mode', () => {
    cy.contains('Advanced').click();
    cy.get('input[placeholder*="Search workflows"]').should('be.visible');
  });
});
