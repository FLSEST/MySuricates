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

  describe('Chart rendering', () => {
    // Regression: Tailwind v4 respects .gitignore for content scanning.
    // A bare "dashboard/" pattern silently excluded src/components/dashboard/,
    // so utility classes (h-40) were never generated and charts got 0px height.

    it('renders all dashboard charts as SVGs with non-zero dimensions', () => {
      // Wait for charts to render — Recharts adds .recharts-surface to each SVG
      cy.get('.recharts-surface', { timeout: 15000 }).should('have.length.at.least', 3);

      // Each chart SVG must have a real width and height (not 0)
      cy.get('.recharts-surface').each(($svg) => {
        cy.wrap($svg)
          .invoke('attr', 'width')
          .then(Number)
          .should('be.greaterThan', 0);
        cy.wrap($svg)
          .invoke('attr', 'height')
          .then(Number)
          .should('be.greaterThan', 0);
      });
    });

    it('renders the Failing Workflows card', () => {
      cy.contains('h3', 'Failing Workflows').should('be.visible');
    });
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
