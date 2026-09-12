/** Read-only smoke coverage for router and browser-runner upgrades. */
describe('Router navigation', () => {
  it('supports sidebar navigation, history, and direct reloads in advanced mode', () => {
    cy.visit('/projects', {
      onBeforeLoad(win) {
        const settings = JSON.parse(win.localStorage.getItem('app-settings') || '{}');
        win.localStorage.setItem('app-settings', JSON.stringify({
          ...settings, version: 0, state: { ...settings.state, advancedMode: true },
        }));
      },
    });
    cy.get('h1').should('contain.text', 'Projects');
    cy.get('aside a[href="/workflows"]').click();
    cy.location('pathname').should('eq', '/workflows');
    cy.get('h1').should('contain.text', 'Workflows');
    cy.go('back');
    cy.location('pathname').should('eq', '/projects');
    cy.go('forward');
    cy.location('pathname').should('eq', '/workflows');
    cy.reload();
    cy.location('pathname').should('eq', '/workflows');
    cy.get('h1').should('contain.text', 'Workflows');
  });
});
