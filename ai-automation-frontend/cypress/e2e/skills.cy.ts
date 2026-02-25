describe('Skills', () => {
  beforeEach(() => {
    cy.visit('/skills');
  });

  it('shows the page title and subtitle', () => {
    cy.get('h1').should('contain.text', 'Skills');
    cy.contains('AI capabilities for your workflows').should('be.visible');
  });

  it('loads built-in skills', () => {
    // At least 16 built-in skills should be visible
    cy.get('main .grid').find('[class*="cursor-pointer"]').should('have.length.greaterThan', 15);
  });

  it('shows built-in badge on seeded skills', () => {
    cy.contains('Built-in').should('be.visible');
  });

  it('filters skills by search', () => {
    cy.get('main input[placeholder="Search..."]').type('Code Review');
    cy.contains('Code Review').should('be.visible');
  });

  it('filters skills by category', () => {
    cy.get('main').find('button').contains('All').should('be.visible');
    cy.get('main').find('button').contains('Code').click();
    // Should show only Code category skills
    cy.get('main .grid').find('[class*="cursor-pointer"]').should('have.length.greaterThan', 0);
  });

  it('opens skill detail slide-over on card click', () => {
    cy.get('main .grid').find('[class*="cursor-pointer"]').first().click();
    cy.get('.fixed.inset-0').should('exist');
    cy.get('.fixed.inset-0').contains('Capabilities').should('be.visible');
    cy.get('.fixed.inset-0').contains('button', 'Close').should('be.visible');
  });

  it('shows capabilities in the detail slide-over', () => {
    cy.get('main .grid').find('[class*="cursor-pointer"]').first().click();
    cy.get('.fixed.inset-0').contains('Capabilities').should('be.visible');
    cy.get('.fixed.inset-0').within(() => {
      cy.get('[class*="rounded-lg"]').should('have.length.greaterThan', 0);
    });
  });

  it('shows no results for an impossible search', () => {
    cy.get('main input[placeholder="Search..."]').type('zzz_no_match_zzz');
    cy.contains('No results found').should('be.visible');
  });

  it('closes the skill detail slide-over', () => {
    cy.get('main .grid').find('[class*="cursor-pointer"]').first().click();
    cy.get('.fixed.inset-0').contains('Capabilities').should('be.visible');
    cy.get('.fixed.inset-0').contains('button', 'Close').click();
    cy.get('.fixed.inset-0').should('not.exist');
  });
});
