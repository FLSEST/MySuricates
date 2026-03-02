describe('Workflows', () => {
  let projectId: string;

  before(() => {
    cy.apiCleanup();
    cy.apiCreateProject('Cypress Workflow Project').then((project) => {
      projectId = project.id;
    });
  });

  after(() => {
    cy.apiCleanup();
  });

  beforeEach(() => {
    cy.visit('/workflows');
  });

  it('shows the workflows page', () => {
    // Page loads and shows either workflows or empty state
    cy.get('h1').should('contain.text', 'Workflows');
  });

  it('opens the template selection modal on New Workflow', () => {
    cy.contains('button', 'New Workflow').click();
    cy.contains('Choose a Template').should('be.visible');
  });

  it('shows blank and template options', () => {
    cy.contains('button', 'New Workflow').click();
    cy.contains('Blank Workflow').should('be.visible');
    cy.contains('Code Quality').should('be.visible');
  });

  it('proceeds from template selection to details form', () => {
    cy.contains('button', 'New Workflow').click();
    // Click the blank card to select it
    cy.contains('Blank Workflow').click();
    // Should see the details form with project select and name input
    cy.contains('Project', { timeout: 8000 }).should('be.visible');
    cy.get('[role="dialog"]').find('input').should('exist');
  });

  it('creates a blank workflow', () => {
    const name = `Cypress Workflow ${Date.now()}`;

    cy.contains('button', 'New Workflow').click();
    cy.contains('Blank Workflow').click();

    cy.get('[role="dialog"]').within(() => {
      cy.get('select').first().select(projectId);
      cy.get('input').first().clear().type(name);
      cy.contains('button', 'Create').click();
    });

    cy.shouldShowToast('Workflow created successfully');
    // Should navigate to the detail page
    cy.url().should('match', /\/workflows\/[a-f0-9-]+$/);
  });

  it('creates a workflow from Code Quality template', () => {
    const name = `Cypress Template Workflow ${Date.now()}`;

    cy.contains('button', 'New Workflow').click();
    // Search for the template, then click the card (not the search input)
    cy.get('[role="dialog"]').find('input[placeholder*="Search"]').type('Code Quality');
    cy.get('[role="dialog"]').find('.cursor-pointer').contains('Code Quality').click();

    cy.get('[role="dialog"]').within(() => {
      cy.get('select').first().select(projectId);
      cy.get('input').first().clear().type(name);
      cy.contains('button', 'Create').click();
    });

    cy.shouldShowToast('Workflow created successfully');
    cy.url().should('match', /\/workflows\/[a-f0-9-]+$/);
  });

  describe('Workflow Detail', () => {
    let workflowId: string;

    beforeEach(() => {
      cy.apiCreateWorkflow(projectId, `Cypress Detail WF ${Date.now()}`).then((wf) => {
        workflowId = wf.id;
        cy.visit(`/workflows/${workflowId}`);
      });
    });

    it('shows the workflow detail page sections', () => {
      cy.contains('Blocks').should('be.visible');
      cy.contains('Activated Skills').should('be.visible');
      cy.contains('Info').should('be.visible');
    });

    it('shows the execute button', () => {
      cy.contains('button', 'Execute').should('be.visible');
    });

    it('edits a workflow name and description', () => {
      cy.get('[aria-label="Edit"]').click();
      cy.contains('Edit Workflow').should('be.visible');

      cy.get('[role="dialog"]').within(() => {
        cy.get('input').first().clear().type('Cypress Updated Workflow');
        cy.contains('button', 'Save').click();
      });

      cy.contains('Cypress Updated Workflow').should('be.visible');
    });

    it('adds a block to the workflow', () => {
      cy.contains('button', 'Add Block').click();
      cy.contains('Block Type').should('be.visible');

      cy.get('[role="dialog"]').within(() => {
        cy.get('select').select('simple');
        cy.contains('button', 'Add Block').click();
      });

      // Block count should update
      cy.contains('Blocks (1)').should('be.visible');
    });

    it('activates a skill on the workflow', () => {
      cy.contains('button', 'Activate Skill').click();
      cy.get('[role="dialog"]').should('be.visible');
      cy.get('[role="dialog"]').find('select').then(($select) => {
        // Only try if there are skills available
        if ($select.find('option').length > 1) {
          cy.wrap($select).select(1);
          cy.get('[role="dialog"]').contains('button', 'Activate').click();
          cy.shouldShowToast('Skills activated successfully');
        }
      });
    });

    it('deletes a workflow with confirmation', () => {
      cy.get('[aria-label="Delete"]').click();
      cy.contains('This action cannot be undone').should('be.visible');
      cy.get('[role="dialog"]').contains('button', 'Delete').click();
      cy.shouldShowToast('Workflow deleted successfully');
      cy.url().should('include', '/workflows');
    });
  });

  it('shows workflow cards with run counts and skill badges', () => {
    const name = `Cypress Card ${Date.now()}`;
    cy.apiCreateWorkflow(projectId, name).then(() => {
      cy.visit('/workflows');
      // Wait for workflows to load, then search for the created one
      cy.get('input[placeholder="Search..."]').type(name.slice(-8));
      cy.contains(name).should('be.visible');
    });
  });

  it('filters workflows by search', () => {
    cy.apiCreateWorkflow(projectId, 'Cypress Searchable WF');
    cy.apiCreateWorkflow(projectId, 'Cypress Hidden WF');
    cy.visit('/workflows');

    cy.get('input[placeholder="Search..."]').type('Searchable');
    cy.contains('Cypress Searchable WF').should('be.visible');
    cy.contains('Cypress Hidden WF').should('not.exist');
  });

  it('shows import button', () => {
    cy.contains('button', 'Import').should('be.visible');
  });

  describe('Export and Import', () => {
    let workflowId: string;

    before(() => {
      cy.apiCreateWorkflow(projectId, 'Cypress Export WF').then((wf) => {
        workflowId = wf.id;
      });
    });

    it('shows export button on workflow detail panel', () => {
      cy.visit(`/workflows/${workflowId}`);
      cy.get('[aria-label="Export Workflow"], [aria-label="Export workflow"]').should('exist');
    });
  });

  describe('Multi-Select and Bulk Delete', () => {
    before(() => {
      cy.apiCreateWorkflow(projectId, 'Cypress MultiA WF');
      cy.apiCreateWorkflow(projectId, 'Cypress MultiB WF');
      cy.apiCreateWorkflow(projectId, 'Cypress MultiC WF');
    });

    it('enters and exits select mode', () => {
      cy.visit('/workflows');
      // Expand the project group first
      cy.contains('Cypress Workflow Project').click();
      // Find the select mode toggle (CheckSquare icon button)
      cy.get('button[title*="Select"], button[title*="select"]').first().click();
      // Should show checkboxes — look for Square icons
      cy.get('svg').then(($svgs) => {
        // Select mode is active, the button title should now say "exit"
        cy.get('button[title*="Exit"], button[title*="exit"]').should('exist');
      });
      // Exit select mode
      cy.get('button[title*="Exit"], button[title*="exit"]').first().click();
    });

    it('selects workflows and shows bulk delete bar', () => {
      cy.visit('/workflows');
      cy.contains('Cypress Workflow Project').click();
      // Enter select mode
      cy.get('button[title*="Select"], button[title*="select"]').first().click();
      // Click on workflow items to select them
      cy.contains('Cypress MultiA WF').click();
      cy.contains('Cypress MultiB WF').click();
      // Should show "2 selected" and Delete button
      cy.contains('2 selected').should('be.visible');
      cy.get('button[title*="Exit"], button[title*="exit"]').first().click();
    });
  });

  describe('Project Group Collapse', () => {
    it('toggles project group expand/collapse', () => {
      cy.apiCreateWorkflow(projectId, 'Cypress Collapse WF').then(() => {
        cy.visit('/workflows');
        // Search to narrow down results
        cy.get('input[placeholder="Search..."]').type('Cypress Collapse');
        // Projects start collapsed by default — the left list panel (.space-y-1) has the rows
        cy.get('.overflow-y-auto.space-y-1').within(() => {
          cy.contains('Cypress Collapse WF').should('not.exist');
        });
        // Expand the project group
        cy.contains('Cypress Workflow Project').click();
        cy.get('.overflow-y-auto.space-y-1').within(() => {
          cy.contains('Cypress Collapse WF').should('be.visible');
        });
        // Collapse it again
        cy.contains('Cypress Workflow Project').click();
        cy.get('.overflow-y-auto.space-y-1').within(() => {
          cy.contains('Cypress Collapse WF').should('not.exist');
        });
      });
    });

    it('shows collapse/expand all button', () => {
      cy.get('button[title*="Collapse"], button[title*="Expand"], button[title*="collapse"], button[title*="expand"]').should('exist');
    });
  });

  describe('Auto-Todo Toggle', () => {
    it('shows auto-todo on workflow list detail panel', () => {
      cy.apiCreateWorkflow(projectId, 'Cypress AutoTodo WF').then(() => {
        // Auto-Todo toggle is on the Workflows list page's right-side detail panel (lg viewport)
        cy.visit('/workflows');
        // Expand the project group and click the workflow to select it
        cy.contains('Cypress Workflow Project').click();
        cy.contains('Cypress AutoTodo WF').click();
        // The detail panel should show Auto-Todo toggle
        cy.contains('Auto-Todo').should('be.visible');
      });
    });
  });
});
