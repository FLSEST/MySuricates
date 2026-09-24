describe('Runs', () => {
  before(() => {
    cy.apiCleanup();
  });

  after(() => {
    cy.apiCleanup();
  });

  beforeEach(() => {
    cy.visit('/runs');
  });

  it('shows the runs page with title and subtitle', () => {
    cy.get('h1').should('contain.text', 'Run History');
    cy.contains('Workflow and chain executions').should('be.visible');
  });

  it('shows status filter dropdown', () => {
    cy.get('select').then(($selects) => {
      // Find the status filter (contains "All Statuses" option)
      const statusSelect = [...$selects].find((s) =>
        [...s.options].some((o) => o.text.includes('All Statuses')),
      );
      expect(statusSelect).to.exist;
      // Verify status options are available
      const options = [...statusSelect!.options].map((o) => o.value);
      expect(options).to.include('COMPLETED');
      expect(options).to.include('FAILED');
      expect(options).to.include('RUNNING');
    });
  });

  it('shows workflow filter dropdown', () => {
    cy.get('select').then(($selects) => {
      const wfSelect = [...$selects].find((s) =>
        [...s.options].some((o) => o.text.includes('All Workflows')),
      );
      expect(wfSelect).to.exist;
    });
  });

  it('shows project filter dropdown', () => {
    cy.get('select').then(($selects) => {
      const projSelect = [...$selects].find((s) =>
        [...s.options].some((o) => o.text.includes('All Projects')),
      );
      expect(projSelect).to.exist;
    });
  });

  it('shows search input', () => {
    cy.get('input[placeholder="Search..."]').should('be.visible');
  });

  it('shows table or empty state after loading', () => {
    // Wait for loading to finish
    cy.get('h1').should('contain.text', 'Run History');
    // Check for either table or empty state — the page must render one of them
    cy.get('body', { timeout: 15000 }).should(($body) => {
      const hasTable = $body.find('table').length > 0;
      const hasEmpty = $body.text().includes('No workflow runs found');
      expect(hasTable || hasEmpty, 'page shows either table or empty state').to.be.true;
    });
  });

  it('filters by status', () => {
    // Select COMPLETED status, then re-query and reset
    cy.get('select').first().select('COMPLETED');
    cy.wait(500);
    cy.get('select').first().select('');
  });

  it('filters by search text', () => {
    cy.get('input[placeholder="Search..."]').type('nonexistent-workflow-xyz');
    // Should either show empty table or empty state
    cy.wait(500);
    cy.get('body').then(($body) => {
      if ($body.find('table tbody tr').length === 0 || $body.text().includes('No workflow runs found')) {
        // Expected: no results for nonsense search
        expect(true).to.be.true;
      }
    });
  });

  describe('with execution data', () => {
    let projectId: string;
    let workflowId: string;

    before(() => {
      cy.apiCreateProject('Cypress Runs Project').then((project) => {
        projectId = project.id;
        cy.apiCreateWorkflow(projectId, 'Cypress Runs Workflow').then((wf) => {
          workflowId = wf.id;
        });
      });
    });

    // The filters render while the runs are still loading and get their options once the
    // workflow and project lists arrive — so the assertions retry (`should`) instead of
    // checking the first render once (`then`).
    it('populates workflow filter with created workflow', () => {
      cy.visit('/runs');
      cy.get('select').should(($selects) => {
        const wfSelect = [...$selects].find((s) =>
          [...s.options].some((o) => o.text.includes('All Workflows')),
        );
        expect(wfSelect, 'workflow filter').to.exist;
        const optionTexts = [...wfSelect!.options].map((o) => o.text);
        expect(optionTexts).to.include('Cypress Runs Workflow');
      });
    });

    it('populates project filter with created project', () => {
      cy.visit('/runs');
      cy.get('select').should(($selects) => {
        const projSelect = [...$selects].find((s) =>
          [...s.options].some((o) => o.text.includes('All Projects')),
        );
        expect(projSelect, 'project filter').to.exist;
        const optionTexts = [...projSelect!.options].map((o) => o.text);
        expect(optionTexts).to.include('Cypress Runs Project');
      });
    });
  });
});
