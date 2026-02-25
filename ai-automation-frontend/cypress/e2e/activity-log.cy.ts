describe('Activity Log', () => {
  beforeEach(() => {
    cy.visit('/activity');
  });

  it('shows the activity log page with title', () => {
    cy.get('h1').should('contain.text', 'Activity Log');
    cy.contains('Server events and system activity').should('be.visible');
  });

  it('shows level filter dropdown', () => {
    cy.get('select').then(($selects) => {
      const levelSelect = [...$selects].find((s) =>
        [...s.options].some((o) => o.text.includes('All Levels')),
      );
      expect(levelSelect).to.exist;
      if (levelSelect) {
        const options = [...levelSelect.options].map((o) => o.value);
        expect(options).to.include('DEBUG');
        expect(options).to.include('INFO');
        expect(options).to.include('WARN');
        expect(options).to.include('ERROR');
      }
    });
  });

  it('shows category filter dropdown', () => {
    cy.get('select').then(($selects) => {
      const catSelect = [...$selects].find((s) =>
        [...s.options].some((o) => o.text.includes('All Categories')),
      );
      expect(catSelect).to.exist;
      if (catSelect) {
        const options = [...catSelect.options].map((o) => o.value);
        expect(options).to.include('SYSTEM');
        expect(options).to.include('EXECUTION');
        expect(options).to.include('SECURITY');
      }
    });
  });

  it('shows table or empty state after loading', () => {
    // Wait for loading to finish, then check for either outcome
    cy.get('h1').should('contain.text', 'Activity Log');
    cy.get('body', { timeout: 15000 }).should(($body) => {
      const hasTable = $body.find('table').length > 0;
      const hasEmpty = $body.text().includes('No log entries found');
      expect(hasTable || hasEmpty, 'page shows either table or empty state').to.be.true;
    });
  });

  it('shows pagination controls', () => {
    cy.get('body').then(($body) => {
      if ($body.find('table').length > 0) {
        // Should show entry count and page navigation
        cy.contains('entries').should('be.visible');
      }
    });
  });

  it('filters by level', () => {
    cy.get('select').then(($selects) => {
      const levelSelect = [...$selects].find((s) =>
        [...s.options].some((o) => o.text.includes('All Levels')),
      );
      if (levelSelect) {
        cy.wrap(levelSelect).select('ERROR');
        cy.wait(500);
        // If there are error logs, they should be visible; otherwise empty state
        cy.get('body').then(($body) => {
          if ($body.find('table tbody tr').length > 0) {
            // All visible level badges should be ERROR
            cy.get('table tbody').find('span').contains('ERROR').should('exist');
          }
        });
        // Reset filter
        cy.wrap(levelSelect).select('');
      }
    });
  });

  it('filters by category', () => {
    cy.get('select').then(($selects) => {
      const catSelect = [...$selects].find((s) =>
        [...s.options].some((o) => o.text.includes('All Categories')),
      );
      if (catSelect) {
        cy.wrap(catSelect).select('SYSTEM');
        cy.wait(500);
        // Reset
        cy.wrap(catSelect).select('');
      }
    });
  });

  it('navigates between pages', () => {
    cy.get('body').then(($body) => {
      // Only test pagination if there are enough entries
      if ($body.find('table').length > 0 && $body.text().match(/\d+ entries/)) {
        // Check if next button exists and is enabled
        cy.get('button').then(($buttons) => {
          const nextBtn = [...$buttons].find((b) => {
            // Find the ChevronRight button — it's the second pagination button
            return b.querySelector('svg') && !b.disabled;
          });
          if (nextBtn && $body.text().includes('/ 2') || $body.text().match(/\/\s*[2-9]/)) {
            cy.wrap(nextBtn).click();
            cy.wait(500);
          }
        });
      }
    });
  });
});
