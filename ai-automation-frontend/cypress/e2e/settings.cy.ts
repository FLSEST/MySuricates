describe('Settings', () => {
  beforeEach(() => {
    cy.visit('/settings');
  });

  /** Since a8cc678d most sections start collapsed; their header button opens them. */
  function openSection(id: string) {
    cy.get(`#${id} > button[aria-expanded]`).click();
    cy.get(`#${id} > button[aria-expanded]`).should('have.attr', 'aria-expanded', 'true');
  }

  it('shows the page title and all sections', () => {
    cy.get('h1').should('contain.text', 'Settings');
    for (const title of ['General', 'Appearance', 'Execution', 'Infrastructure', 'System']) {
      cy.contains('h2', title).should('be.visible');
    }
    // The general section is always open
    cy.get('#general').contains('h3', 'Security').should('be.visible');
    // Collapsed sections hide their cards until opened
    cy.get('#infrastructure').contains('h3', 'Remote Access').should('not.be.visible');
    openSection('infrastructure');
    cy.get('#infrastructure').contains('h3', 'Remote Access').should('be.visible');
    openSection('system');
    cy.get('#system').contains('h3', 'System Info').should('be.visible');
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

  it('switches between a light and a dark theme', () => {
    // Since a8cc678d the appearance section offers theme swatches instead of a Light/Dark switch
    openSection('appearance');
    cy.get('#appearance button[title="MySuricates Light"]').click();
    cy.get('#appearance button[title="MySuricates Light"]').should('have.attr', 'aria-pressed', 'true');
    cy.get('html').should('not.have.class', 'dark');

    cy.get('#appearance button[title="MySuricates Dark"]').click();
    cy.get('#appearance button[title="MySuricates Dark"]').should('have.attr', 'aria-pressed', 'true');
    cy.get('#appearance button[title="MySuricates Light"]').should('have.attr', 'aria-pressed', 'false');
    cy.get('html').should('have.class', 'dark');
  });

  it('has an API key input field', () => {
    cy.contains('API Key').should('be.visible');
    cy.get('input[placeholder="sk-..."]').should('exist');
    cy.contains('Used for authenticating API requests').should('be.visible');
  });

  it('shows system health status', () => {
    openSection('system');
    // The side instance under test is up: the badge shows the health status of /system/health
    cy.get('#system').contains('h3', 'System Info').closest('.rounded-xl').within(() => {
      cy.contains('UP').should('be.visible');
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
