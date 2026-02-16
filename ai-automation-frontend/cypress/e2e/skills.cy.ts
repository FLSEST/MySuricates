describe('Skills', () => {
  beforeEach(() => {
    cy.visit('/skills');
  });

  it('shows the page title and subtitle', () => {
    cy.get('h1').should('contain.text', 'Skills');
    cy.contains('AI capabilities for your workflows').should('be.visible');
  });

  it('loads built-in skills', () => {
    // At least some built-in skills should be visible
    cy.get('.grid').find('[class*="cursor-pointer"]').should('have.length.greaterThan', 0);
    cy.contains('Built-in').should('be.visible');
  });

  it('filters skills by search', () => {
    // Get the first skill name, then search for it
    cy.get('.grid h3').first().invoke('text').then((skillName) => {
      cy.get('input[placeholder="Search..."]').type(skillName);
      cy.contains(skillName).should('be.visible');
    });
  });

  it('filters skills by category', () => {
    // Click the "Code" category filter
    cy.contains('button', 'Code').click();
    // All visible skills should belong to the Code category
    cy.get('.grid').find('[class*="cursor-pointer"]').each(($card) => {
      cy.wrap($card).contains('Code').should('exist');
    });
  });

  it('opens skill detail slide-over on card click', () => {
    cy.get('.grid').find('[class*="cursor-pointer"]').first().click();
    // Slide-over should appear with skill name and capabilities
    cy.contains('Capabilities').should('be.visible');
    cy.contains('button', 'Close').should('be.visible');
  });

  it('shows capabilities in the detail slide-over', () => {
    cy.get('.grid').find('[class*="cursor-pointer"]').first().click();
    cy.contains('Capabilities').should('be.visible');
    // Wait for capabilities to load
    cy.get('.fixed').within(() => {
      cy.get('[class*="rounded-lg"]').should('have.length.greaterThan', 0);
    });
  });

  it('shows no results for an impossible search', () => {
    cy.get('input[placeholder="Search..."]').type('zzz_no_match_zzz');
    cy.contains('No results found').should('be.visible');
  });

  it('closes the skill detail slide-over', () => {
    cy.get('.grid').find('[class*="cursor-pointer"]').first().click();
    cy.contains('Capabilities').should('be.visible');
    cy.contains('button', 'Close').click();
    cy.get('.fixed').should('not.exist');
  });
});
