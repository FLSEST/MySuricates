describe('Settings', () => {
  beforeEach(() => {
    cy.visit('/settings');
  });

  it('shows the page title and all sections', () => {
    cy.get('h1').should('contain.text', 'Settings');
    cy.contains('General').should('be.visible');
    cy.contains('Appearance').should('be.visible');
    cy.contains('Security').should('be.visible');
    cy.contains('Remote Access').should('be.visible');
    cy.contains('System Info').should('be.visible');
  });

  it('switches language between English and Deutsch', () => {
    // Start in English
    cy.get('h1').should('contain.text', 'Settings');

    // The language select is in the row container (justify-between), not the inner icon group
    cy.contains('Language').closest('.justify-between').find('select').select('de');

    // Page title should change to German
    cy.get('h1').should('contain.text', 'Einstellungen');

    // Switch back to English
    cy.contains('Sprache').closest('.justify-between').find('select').select('en');
    cy.get('h1').should('contain.text', 'Settings');
  });

  it('toggles theme between light and dark', () => {
    // Click Light
    cy.contains('button', 'Light').click();
    // Verify the Light button has the active style (bg-accent)
    cy.contains('button', 'Light').should('have.class', 'bg-accent');

    // Click Dark
    cy.contains('button', 'Dark').click();
    cy.contains('button', 'Dark').should('have.class', 'bg-accent');
  });

  it('has an API key input field', () => {
    cy.contains('API Key').should('be.visible');
    cy.get('input[placeholder="sk-..."]').should('exist');
    cy.contains('Used for authenticating API requests').should('be.visible');
  });

  it('shows system health status', () => {
    cy.contains('System Info').should('be.visible');
    // Health status badge should appear
    cy.contains('System Info').parents('[class*="rounded"]').first().within(() => {
      cy.get('[class*="rounded"]').should('exist');
    });
  });

  it('toggles advanced mode', () => {
    cy.contains('Advanced Settings').should('be.visible');
    cy.contains('Show JSON configs, debug info, raw data').should('be.visible');
    // Find the toggle in the advanced settings row
    cy.contains('Advanced Settings').parents('.flex').first()
      .find('[role="switch"]').click();
  });
});
