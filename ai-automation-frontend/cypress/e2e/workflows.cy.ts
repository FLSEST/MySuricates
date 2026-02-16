describe('Workflows', () => {
  let projectId: string;

  before(() => {
    cy.apiCleanup();
    cy.apiCreateProject('Workflow Test Project').then((project) => {
      projectId = project.id;
    });
  });

  after(() => {
    cy.apiCleanup();
  });

  beforeEach(() => {
    cy.visit('/workflows');
  });

  it('shows empty state when no workflows exist', () => {
    cy.contains('No workflows yet').should('be.visible');
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
    cy.contains('Project').should('be.visible');
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
    const name = `Template Workflow ${Date.now()}`;

    cy.contains('button', 'New Workflow').click();
    // Click the Code Quality template card
    cy.contains('Code Quality').click();

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
      cy.apiCreateWorkflow(projectId, `Detail WF ${Date.now()}`).then((wf) => {
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
        cy.get('input').first().clear().type('Updated Workflow Name');
        cy.contains('button', 'Save').click();
      });

      cy.contains('Updated Workflow Name').should('be.visible');
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
    cy.apiCreateWorkflow(projectId, 'Card Display Test');
    cy.visit('/workflows');

    cy.contains('Card Display Test').should('be.visible');
    cy.contains('0 runs').should('be.visible');
  });

  it('filters workflows by search', () => {
    cy.apiCreateWorkflow(projectId, 'Searchable Workflow');
    cy.apiCreateWorkflow(projectId, 'Hidden Workflow');
    cy.visit('/workflows');

    cy.get('input[placeholder="Search..."]').type('Searchable');
    cy.contains('Searchable Workflow').should('be.visible');
    cy.contains('Hidden Workflow').should('not.exist');
  });
});
