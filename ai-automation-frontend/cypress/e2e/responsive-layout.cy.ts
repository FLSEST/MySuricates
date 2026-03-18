describe('Responsive Layout', () => {
  beforeEach(() => {
    cy.viewport(1024, 768);
    cy.visit('/');
  });

  it('dashboard header does not overflow at 1024px viewport', () => {
    cy.get('h1').should('be.visible');
    cy.get('body').then(($body) => {
      const bodyWidth = $body[0].scrollWidth;
      const viewportWidth = Cypress.config('viewportWidth') as number;
      expect(bodyWidth).to.be.lte(viewportWidth);
    });
  });

  it('dashboard subtitle is visible and not cut off', () => {
    cy.contains('Overview of your automation platform').should('be.visible');
  });

  it('runs table scrolls horizontally within its container (not the whole page)', () => {
    cy.navTo('Runs');
    cy.get('body').then(($body) => {
      const bodyWidth = $body[0].scrollWidth;
      const viewportWidth = Cypress.config('viewportWidth') as number;
      expect(bodyWidth).to.be.lte(viewportWidth);
    });
  });

  it('schedules page does not cause horizontal page overflow', () => {
    cy.navTo('Schedules');
    cy.get('body').then(($body) => {
      const bodyWidth = $body[0].scrollWidth;
      const viewportWidth = Cypress.config('viewportWidth') as number;
      expect(bodyWidth).to.be.lte(viewportWidth);
    });
  });
});
