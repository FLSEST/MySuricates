describe('Dashboard', () => {
  beforeEach(() => {
    cy.visit('/');
  });

  it('shows the page title and subtitle', () => {
    cy.get('h1').should('contain.text', 'Dashboard');
    cy.contains('Overview of your automation platform').should('be.visible');
  });

  it('displays all four stat cards', () => {
    cy.contains('Active Projects').should('be.visible');
    cy.contains('Total Workflows').should('be.visible');
    cy.contains('Runs Today').should('be.visible');
    cy.contains('Success Rate').should('be.visible');
  });

  it('shows chart sections', () => {
    cy.contains('Runs Over Time').should('be.visible');
    cy.contains('Success Rate').should('be.visible');
  });

  it('shows recent runs section', () => {
    cy.contains('Recent Runs').should('be.visible');
  });

  it('navigates to projects via quick actions', () => {
    cy.contains('Quick Actions').should('be.visible');
    cy.contains('button', 'New Project').click();
    cy.url().should('include', '/projects');
  });

  it('navigates to workflows via quick actions', () => {
    cy.visit('/');
    cy.contains('Quick Actions').should('be.visible');
    cy.contains('button', 'New Workflow').click();
    cy.url().should('include', '/workflows');
  });

  it('makes an API call to the learning dashboard endpoint', () => {
    cy.intercept('GET', '/api/v1/learning/dashboard').as('dashboardApi');
    cy.visit('/');
    cy.wait('@dashboardApi').its('response.statusCode').should('be.oneOf', [200, 304]);
  });
});
