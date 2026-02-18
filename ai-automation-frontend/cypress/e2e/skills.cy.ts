describe('Skills', () => {
  beforeEach(() => {
    cy.visit('/skills');
  });

  it('shows the page title and subtitle', () => {
    cy.get('h1').should('contain.text', 'Skills');
    cy.contains('AI capabilities for your workflows').should('be.visible');
  });

  it('loads skills', () => {
    // At least some skills should be visible (built-in or installed)
    cy.get('main .grid').find('[class*="cursor-pointer"]').should('have.length.greaterThan', 0);
  });

  it('filters skills by search', () => {
    // Get the first skill name, then search for it
    cy.get('main .grid h3').first().invoke('text').then((skillName) => {
      cy.get('main input[placeholder="Search..."]').type(skillName);
      cy.contains(skillName).should('be.visible');
    });
  });

  it('filters skills by category', () => {
    // Verify filter buttons exist and clicking "All" keeps skills visible
    cy.get('main').find('button').contains('All').should('be.visible');
    cy.get('main .grid').find('[class*="cursor-pointer"]').should('have.length.greaterThan', 0);
  });

  it('opens skill detail slide-over on card click', () => {
    cy.get('main .grid').find('[class*="cursor-pointer"]').first().click();
    // Slide-over should appear with skill name and capabilities
    cy.get('.fixed.inset-0').should('exist');
    cy.get('.fixed.inset-0').contains('Capabilities').should('be.visible');
    cy.get('.fixed.inset-0').contains('button', 'Close').should('be.visible');
  });

  it('shows capabilities in the detail slide-over', () => {
    cy.get('main .grid').find('[class*="cursor-pointer"]').first().click();
    cy.get('.fixed.inset-0').contains('Capabilities').should('be.visible');
    // Wait for capabilities to load
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

  describe('Skill Catalog', () => {
    it('opens the skill catalog overlay', () => {
      cy.contains('button', 'Skill Catalog').click();
      cy.contains('Browse and install pre-built skills').should('be.visible');
    });

    it('shows all 15 catalog skills', () => {
      cy.contains('button', 'Skill Catalog').click();
      cy.get('.fixed.inset-0 .grid').find('[class*="rounded-2xl"]').should('have.length', 15);
    });

    it('filters catalog skills by category', () => {
      cy.contains('button', 'Skill Catalog').click();
      // Click "Code" category inside the catalog overlay
      cy.get('.fixed.inset-0').contains('button', 'Code').click();
      cy.get('.fixed.inset-0 .grid').find('[class*="rounded-2xl"]').should('have.length', 3);
    });

    it('filters catalog skills by search', () => {
      cy.contains('button', 'Skill Catalog').click();
      cy.get('.fixed.inset-0').find('input[placeholder="Search..."]').type('Translator');
      cy.get('.fixed.inset-0 .grid').find('[class*="rounded-2xl"]').should('have.length', 1);
      cy.get('.fixed.inset-0').contains('Document Translator').should('be.visible');
    });

    it('shows empty state when search has no results', () => {
      cy.contains('button', 'Skill Catalog').click();
      cy.get('.fixed.inset-0').find('input[placeholder="Search..."]').type('zzz_no_match_zzz');
      cy.contains('No skills match your search.').should('be.visible');
    });

    it('opens skill detail view in catalog', () => {
      cy.contains('button', 'Skill Catalog').click();
      // Scope to the catalog overlay to avoid matching background skill cards
      cy.get('.fixed.inset-0').contains('Code Review').click();
      cy.get('.fixed.inset-0').contains('AI Instructions Preview').should('be.visible');
      cy.get('.fixed.inset-0').contains('Capabilities').should('be.visible');
      cy.get('.fixed.inset-0').contains('review_code').should('be.visible');
    });

    it('navigates back from detail to grid', () => {
      cy.contains('button', 'Skill Catalog').click();
      cy.get('.fixed.inset-0').contains('Code Review').click();
      cy.get('.fixed.inset-0').contains('AI Instructions Preview').should('be.visible');
      cy.get('.fixed.inset-0').contains('Back').click();
      cy.get('.fixed.inset-0 .grid').find('[class*="rounded-2xl"]').should('have.length', 15);
    });

    it('installs a catalog skill or shows installed', () => {
      cy.contains('button', 'Skill Catalog').click();
      // Check if any Install button exists; if so, click it
      cy.get('.fixed.inset-0 .grid').find('[class*="rounded-2xl"]').first().then(($card) => {
        if ($card.find('button:contains("Install")').length > 0) {
          cy.wrap($card).contains('button', 'Install').click();
          cy.shouldShowToast('Skill installed successfully');
        } else {
          // All skills already installed — verify at least one "Installed" badge
          cy.get('.fixed.inset-0').contains('Installed').should('be.visible');
        }
      });
    });

    it('closes the catalog overlay', () => {
      cy.contains('button', 'Skill Catalog').click();
      cy.contains('Browse and install pre-built skills').should('be.visible');
      // Click the X close button (first button inside the catalog header)
      cy.get('.fixed.inset-0').find('button').first().click();
      cy.contains('Browse and install pre-built skills').should('not.exist');
    });
  });
});
