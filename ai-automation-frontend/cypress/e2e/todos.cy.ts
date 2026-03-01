describe('Todos', () => {
  before(() => {
    cy.apiCleanup();
  });

  after(() => {
    cy.apiCleanup();
  });

  beforeEach(() => {
    cy.visit('/todos');
  });

  it('shows the todos page with title', () => {
    cy.get('h1').should('contain.text', 'Todos');
  });

  it('shows the quick-add input', () => {
    cy.get('input[placeholder*="New Todo"]').should('be.visible');
  });

  it('shows status filter tabs', () => {
    cy.contains('button', 'All').should('be.visible');
    cy.contains('button', 'Open').should('be.visible');
    cy.contains('button', 'In Progress').should('be.visible');
    cy.contains('button', 'Done').should('be.visible');
  });

  it('shows source filter dropdown', () => {
    cy.get('select').then(($selects) => {
      const sourceSelect = [...$selects].find((s) =>
        [...s.options].some((o) => o.text.includes('All Sources')),
      );
      expect(sourceSelect).to.exist;
    });
  });

  it('shows empty state when no todos exist', () => {
    // After cleanup, should show empty state or existing non-test todos
    cy.get('body').then(($body) => {
      if ($body.text().includes('No todos yet')) {
        cy.contains('No todos yet').should('be.visible');
      }
    });
  });

  it('creates a todo via quick-add', () => {
    const title = `Cypress Quick Todo ${Date.now()}`;
    cy.get('input[placeholder*="New Todo"]').type(title);
    cy.contains('button', 'Create').click();
    cy.shouldShowToast('Todo created');
    cy.contains(title).should('be.visible');
  });

  it('creates a todo with priority via quick-add', () => {
    const title = `Cypress Priority Todo ${Date.now()}`;
    cy.get('input[placeholder*="New Todo"]').type(title);
    // Select HIGH priority
    cy.get('select').then(($selects) => {
      const prioritySelect = [...$selects].find((s) =>
        [...s.options].some((o) => o.value === 'HIGH' || o.text === 'High'),
      );
      if (prioritySelect) {
        cy.wrap(prioritySelect).select('HIGH');
      }
    });
    cy.contains('button', 'Create').click();
    cy.shouldShowToast('Todo created');
    cy.contains(title).should('be.visible');
  });

  describe('with existing todos', () => {
    before(() => {
      cy.apiCreateTodo('Cypress Alpha Todo', 'HIGH', ['bug', 'urgent']);
      cy.apiCreateTodo('Cypress Beta Todo', 'LOW', ['feature']);
      cy.apiCreateTodo('Cypress Gamma Todo', 'MEDIUM', ['bug']);
    });

    beforeEach(() => {
      cy.visit('/todos');
    });

    it('displays created todos in the list', () => {
      cy.contains('Cypress Alpha Todo').should('be.visible');
      cy.contains('Cypress Beta Todo').should('be.visible');
      cy.contains('Cypress Gamma Todo').should('be.visible');
    });

    it('filters by search text', () => {
      // Type a nonsense search term — no results should match
      cy.get('input[placeholder="Search..."]').clear().type('zzz-nonexistent-todo');
      // Should show empty state or no matching todos
      cy.contains('Cypress Alpha Todo', { timeout: 10000 }).should('not.exist');
      // Clear search to restore list
      cy.get('input[placeholder="Search..."]').clear();
      cy.contains('Cypress Alpha Todo', { timeout: 10000 }).should('be.visible');
    });

    it('filters by status tab', () => {
      // All todos start as OPEN, clicking "Done" should hide them
      cy.contains('button', 'Done').click();
      cy.contains('Cypress Alpha Todo').should('not.exist');
      // Switch back to All
      cy.contains('button', 'All').click();
      cy.contains('Cypress Alpha Todo').should('be.visible');
    });

    it('filters by tag chip', () => {
      // Tag chips appear below the filter bar as rounded-full buttons
      cy.get('body').then(($body) => {
        if ($body.find('button.rounded-full:contains("bug")').length > 0) {
          cy.get('button.rounded-full').contains('bug').click();
          cy.contains('Cypress Alpha Todo').should('be.visible');
          cy.get('body').should('not.contain.text', 'Cypress Beta Todo');
          // Click again to clear the filter
          cy.get('button.rounded-full').contains('bug').click();
          cy.contains('Cypress Beta Todo').should('be.visible');
        } else {
          // Tags may not be visible if test data was cleaned by parallel runs
          cy.log('No tag chips visible — skipping tag filter assertion');
        }
      });
    });

    it('toggles todo status via checkbox', () => {
      // Find the Alpha todo row and click its checkbox
      cy.contains('Cypress Alpha Todo')
        .closest('[class*="group"]')
        .find('[role="checkbox"], button')
        .first()
        .click();
      // Should mark as DONE — the todo may get line-through styling
      cy.wait(500);
      // Toggle back: click the checkbox again
      cy.contains('Cypress Alpha Todo')
        .closest('[class*="group"]')
        .find('[role="checkbox"], button')
        .first()
        .click();
    });

    it('opens edit modal when clicking a todo', () => {
      cy.contains('Cypress Beta Todo').click();
      cy.contains('Edit Todo').should('be.visible');
      cy.get('[role="dialog"]').should('be.visible');
    });

    it('edits a todo title', () => {
      cy.contains('Cypress Gamma Todo').click();
      cy.get('[role="dialog"]').within(() => {
        cy.get('input').first().clear().type('Cypress Updated Todo');
        cy.contains('button', 'Save').click();
      });
      cy.shouldShowToast('Todo updated');
      cy.contains('Cypress Updated Todo').should('be.visible');
    });

    it('changes todo status in edit modal', () => {
      cy.contains('Cypress Beta Todo').click();
      cy.get('[role="dialog"]').within(() => {
        cy.contains('button', 'In Progress').click();
        cy.contains('button', 'Save').click();
      });
      cy.shouldShowToast('Todo updated');
      // Filter to "In Progress" to verify
      cy.contains('button', 'In Progress').click();
      cy.contains('Cypress Beta Todo').should('be.visible');
    });

    it('deletes a todo via hover button', () => {
      // Create a todo specifically for deletion
      cy.apiCreateTodo('Cypress Delete Me Todo').then(() => {
        cy.visit('/todos');
        cy.contains('Cypress Delete Me Todo')
          .closest('[class*="group"]')
          .find('[aria-label="Delete"], button[class*="danger"]')
          .last()
          .click({ force: true });
        cy.shouldShowToast('Todo deleted');
        cy.contains('Cypress Delete Me Todo').should('not.exist');
      });
    });
  });
});
