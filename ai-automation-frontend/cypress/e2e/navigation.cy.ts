describe('Navigation', () => {
  beforeEach(() => {
    cy.visit('/');
  });

  it('loads the dashboard by default', () => {
    cy.get('h1').should('contain.text', 'Dashboard');
  });

  it('navigates to Projects via sidebar', () => {
    cy.navTo('Projects');
    cy.url().should('include', '/projects');
    cy.get('h1').should('contain.text', 'Projects');
  });

  it('navigates to Workflows via sidebar', () => {
    cy.navTo('Workflows');
    cy.url().should('include', '/workflows');
    cy.get('h1').should('contain.text', 'Workflows');
  });

  it('navigates to Skills via sidebar', () => {
    cy.navTo('Skills');
    cy.url().should('include', '/skills');
    cy.get('h1').should('contain.text', 'Skills');
  });

  it('navigates to Schedules via sidebar', () => {
    cy.navTo('Schedules');
    cy.url().should('include', '/schedules');
    cy.get('h1').should('contain.text', 'Schedules');
  });

  it('navigates to Webhooks via sidebar', () => {
    cy.navTo('Webhooks');
    cy.url().should('include', '/webhooks');
    cy.get('h1').should('contain.text', 'Webhooks');
  });

  it('navigates to Settings via sidebar', () => {
    cy.navTo('Settings');
    cy.url().should('include', '/settings');
    cy.get('h1').should('contain.text', 'Settings');
  });

  it('collapses and expands the sidebar', () => {
    // Sidebar should show nav labels when expanded
    cy.get('aside').should('contain.text', 'Dashboard');
    cy.get('aside').invoke('attr', 'class').should('contain', 'w-56');

    // Collapse
    cy.get('aside').find('[aria-label="Collapse"]').click();
    cy.get('aside').invoke('attr', 'class').should('contain', 'w-16');

    // Expand
    cy.get('aside').find('[aria-label="Expand"]').click();
    cy.get('aside').invoke('attr', 'class').should('contain', 'w-56');
  });

  it('highlights the active nav item', () => {
    cy.navTo('Projects');
    // The active NavLink gets an inline backgroundColor style
    cy.get('aside a[href="/projects"]')
      .should('have.css', 'background-color')
      .and('not.eq', 'rgba(0, 0, 0, 0)');
  });
});
