describe('Staging Card', () => {
  const stagingStopped: {
    containerRunning: boolean;
    healthy: boolean;
    lastSnapshot: string | null;
    stableVersion: string | null;
    promotePhase: string;
    promoteStep: number;
    promoteTotalSteps: number;
    error: string | null;
  } = {
    containerRunning: false,
    healthy: false,
    lastSnapshot: '2026-03-30 14:22',
    stableVersion: '0.5.0',
    promotePhase: 'IDLE',
    promoteStep: 0,
    promoteTotalSteps: 5,
    error: null,
  };

  const stagingRunning = {
    ...stagingStopped,
    containerRunning: true,
    healthy: true,
  };

  const stagingStarting = {
    ...stagingStopped,
    containerRunning: true,
    healthy: false,
  };

  const sysInfo = {
    version: '0.6.0',
    uptime: '2h 30m',
    javaVersion: '21',
  };

  function enableAdvancedMode() {
    localStorage.setItem('app-settings', JSON.stringify({
      state: {
        apiKey: 'test-key',
        simpleMode: false,
        advancedMode: true,
        autoTodoDefault: false,
        autoRepairDefault: false,
      },
      version: 0,
    }));
  }

  /** The Stable card; the Test card next to it has its own Start/Stop/Sync buttons. */
  const card = () => cy.get('[data-testid="staging-card"]');

  function interceptDefaults(stagingStatus = stagingStopped) {
    // Safety net: no click in this spec may reach the real staging.sh/Docker actions. Stubs registered
    // later in a test take precedence over these.
    cy.intercept('POST', '/api/v1/staging/**', { body: { success: true } });
    cy.intercept('POST', '/api/v1/testing/**', { body: { success: true } });
    cy.intercept('GET', '/api/v1/staging/status', { body: stagingStatus }).as('stagingStatus');
    cy.intercept('GET', '/api/v1/system/info', { body: sysInfo }).as('sysInfo');
    cy.intercept('GET', '/api/v1/app-settings', { body: {} }).as('appSettings');
    cy.intercept('GET', '/api/v1/testing/status', { body: { containerRunning: false, healthy: false } }).as('testingStatus');
    // Intercept other settings page API calls to prevent real requests
    cy.intercept('GET', '/api/v1/projects*', { body: { content: [] } }).as('projects');
    cy.intercept('GET', '/api/v1/todos*', { body: { content: [] } }).as('todos');
  }

  beforeEach(() => {
    enableAdvancedMode();
  });

  describe('card visibility', () => {
    it('shows staging card on settings page in advanced mode', () => {
      interceptDefaults();
      cy.visit('/settings');
      // The environments section may be collapsible; click to open
      cy.contains('Environments').should('be.visible').click();
      card().contains('Stable').should('be.visible');
    });

    it('shows container status section', () => {
      interceptDefaults();
      cy.visit('/settings');
      cy.contains('Environments').click();
      card().contains('localhost:9091').should('be.visible');
    });
  });

  describe('container states', () => {
    it('shows STOPPED badge when container is not running', () => {
      interceptDefaults(stagingStopped);
      cy.visit('/settings');
      cy.contains('Environments').click();
      card().contains('Stopped').should('be.visible');
    });

    it('shows RUNNING badge when container is running and healthy', () => {
      interceptDefaults(stagingRunning);
      cy.visit('/settings');
      cy.contains('Environments').click();
      card().contains('Running').should('be.visible');
    });

    it('shows STARTING badge when container is running but not healthy', () => {
      interceptDefaults(stagingStarting);
      cy.visit('/settings');
      cy.contains('Environments').click();
      card().contains('Starting').should('be.visible');
      card().contains('Running').should('not.exist');
    });
  });

  describe('buttons when stopped', () => {
    beforeEach(() => {
      interceptDefaults(stagingStopped);
      cy.visit('/settings');
      cy.contains('Environments').click();
    });

    it('shows Start button', () => {
      card().contains('button', 'Start').should('be.visible');
    });

    it('does not show Stop button', () => {
      card().contains('button', 'Stop').should('not.exist');
    });

    it('does not show Open Stable UI button', () => {
      card().contains('button', 'Open Stable UI').should('not.exist');
    });
  });

  describe('buttons when running', () => {
    beforeEach(() => {
      interceptDefaults(stagingRunning);
      cy.visit('/settings');
      cy.contains('Environments').click();
    });

    it('shows Stop button', () => {
      card().contains('button', 'Stop').should('be.visible');
    });

    it('shows Open Stable UI button', () => {
      card().contains('button', 'Open Stable UI').should('be.visible');
    });

    it('does not show Start button', () => {
      card().contains('button', 'Start').should('not.exist');
    });
  });

  describe('start/stop actions', () => {
    it('Start triggers correct API call', () => {
      interceptDefaults(stagingStopped);
      cy.intercept('POST', '/api/v1/staging/start', { body: { success: true } }).as('start');
      cy.visit('/settings');
      cy.contains('Environments').click();
      card().contains('button', 'Start').click();
      cy.wait('@start');
    });

    it('Stop triggers correct API call', () => {
      interceptDefaults(stagingRunning);
      cy.intercept('POST', '/api/v1/staging/stop', { body: { success: true } }).as('stop');
      cy.visit('/settings');
      cy.contains('Environments').click();
      card().contains('button', 'Stop').click();
      cy.wait('@stop');
    });
  });

  describe('promote', () => {
    beforeEach(() => {
      interceptDefaults(stagingStopped);
      cy.intercept('POST', '/api/v1/staging/promote', { body: { success: true } }).as('promote');
      cy.visit('/settings');
      cy.contains('Environments').click();
    });

    it('shows Promote button', () => {
      card().contains('button', 'Promote Current Code').should('be.visible');
    });

    it('Promote triggers correct API call', () => {
      card().contains('button', 'Promote Current Code').click();
      cy.wait('@promote');
    });

    it('shows promote progress when promoting', () => {
      interceptDefaults({
        ...stagingStopped,
        promotePhase: 'BUILDING_BACKEND',
        promoteStep: 2,
        promoteTotalSteps: 5,
      });
      cy.visit('/settings');
      cy.contains('Environments').click();
      card().contains('2/5').should('be.visible');
    });

    it('shows cancel button during promote', () => {
      interceptDefaults({
        ...stagingStopped,
        promotePhase: 'BUILDING_FRONTEND',
        promoteStep: 3,
        promoteTotalSteps: 5,
      });
      cy.intercept('POST', '/api/v1/staging/promote/cancel', { body: { success: true } }).as('cancelPromote');
      cy.visit('/settings');
      cy.contains('Environments').click();
      card().contains('button', 'Cancel').should('be.visible');
      card().contains('button', 'Cancel').click();
      cy.wait('@cancelPromote');
    });

    it('shows error when promote fails', () => {
      interceptDefaults({
        ...stagingStopped,
        promotePhase: 'ERROR',
        error: 'Docker build failed',
      });
      cy.visit('/settings');
      cy.contains('Environments').click();
      card().contains('Docker build failed').should('be.visible');
    });
  });

  describe('sync', () => {
    beforeEach(() => {
      interceptDefaults(stagingRunning);
      cy.visit('/settings');
      cy.contains('Environments').click();
    });

    it('shows Sync All button when healthy', () => {
      card().contains('button', 'Sync All').should('be.visible');
    });

    it('shows Selective Sync button when healthy', () => {
      card().contains('button', 'Selective Sync').should('be.visible');
    });

    it('Sync All shows confirmation dialog on first click', () => {
      card().contains('button', 'Sync All').click();
      // Should show confirmation text
      card().contains('will be updated').should('be.visible');
      card().contains('button', 'Confirm').should('be.visible');
    });

    it('Sync All confirm triggers API call', () => {
      cy.intercept('POST', '/api/v1/staging/sync-definitions', {
        body: { workflowsSynced: 3, projectsSynced: 1, skillsSynced: 0, skipped: 2, errors: [] },
      }).as('sync');
      card().contains('button', 'Sync All').click();
      card().contains('button', 'Confirm').click();
      cy.wait('@sync');
    });

    it('shows sync result after successful sync', () => {
      cy.intercept('POST', '/api/v1/staging/sync-definitions', {
        body: { workflowsSynced: 3, projectsSynced: 1, skillsSynced: 0, skipped: 2, errors: [] },
      }).as('sync');
      card().contains('button', 'Sync All').click();
      card().contains('button', 'Confirm').click();
      cy.wait('@sync');
      card().contains('3 workflows').should('be.visible');
    });

    it('shows sync errors when present', () => {
      cy.intercept('POST', '/api/v1/staging/sync-definitions', {
        body: { workflowsSynced: 1, projectsSynced: 0, skillsSynced: 0, skipped: 0, errors: ['Failed to sync workflow X'] },
      }).as('syncWithErrors');
      card().contains('button', 'Sync All').click();
      card().contains('button', 'Confirm').click();
      cy.wait('@syncWithErrors');
      card().contains('Failed to sync workflow X').should('be.visible');
    });

    it('cancel button dismisses sync confirmation', () => {
      card().contains('button', 'Sync All').click();
      card().contains('will be updated').should('be.visible');
      card().contains('button', 'Cancel').click();
      card().contains('will be updated').should('not.exist');
    });

    it('Sync buttons are disabled when not healthy', () => {
      interceptDefaults({ ...stagingStopped, containerRunning: true, healthy: false });
      cy.visit('/settings');
      cy.contains('Environments').click();
      card().contains('button', 'Sync All').should('be.disabled');
    });
  });

  describe('version display', () => {
    it('shows dev and stable versions', () => {
      interceptDefaults(stagingRunning);
      cy.visit('/settings');
      cy.contains('Environments').click();
      card().contains('v0.6.0').should('be.visible');
      card().contains('v0.5.0').should('be.visible');
    });

    it('shows promote available hint when versions differ', () => {
      interceptDefaults({ ...stagingRunning, stableVersion: '0.5.0' });
      cy.visit('/settings');
      cy.contains('Environments').click();
      card().contains('promote recommended').should('be.visible');
    });

    it('shows last snapshot timestamp', () => {
      interceptDefaults(stagingStopped);
      cy.visit('/settings');
      cy.contains('Environments').click();
      card().contains('2026-03-30 14:22').should('be.visible');
    });
  });

  describe('selective sync', () => {
    it('opens sync select modal', () => {
      interceptDefaults(stagingRunning);
      cy.intercept('GET', '/api/v1/staging/sync-preview', {
        body: { workflows: [], projects: [], skills: [] },
      }).as('syncPreview');
      cy.visit('/settings');
      cy.contains('Environments').click();
      card().contains('button', 'Selective Sync').click();
      cy.wait('@syncPreview');
    });
  });
});
