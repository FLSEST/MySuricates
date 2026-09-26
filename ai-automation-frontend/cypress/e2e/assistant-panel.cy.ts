import { en } from '../../src/i18n/en';

// Selectors come from the English texts (the locale support/e2e.ts sets), not from literals (b2cf6861).
const a = en.assistant;
const TOGGLE = `button[title="${a.toggle}"]`;
const INPUT = `textarea[placeholder="${a.inputPlaceholder}"]`;
const SEND = `button[aria-label="${a.send}"]`;

describe('AssistantPanel', () => {
  const chatResponse = {
    message: 'Hier ist meine Antwort auf deine Frage.',
    actions: [],
    model: 'claude-sonnet-4-6',
    responseTimeMs: 1234,
  };

  const suggestionsResponse = ['Was lief heute?', 'Gibt es Probleme?', 'Was steht an?'];

  beforeEach(() => {
    cy.intercept('GET', '/api/v1/assistant/suggestions*', { body: suggestionsResponse }).as('suggestions');
    cy.intercept('POST', '/api/v1/assistant/chat', { body: chatResponse }).as('chat');
    cy.visit('/');
  });

  function openPanel() {
    cy.get(TOGGLE).click();
  }

  it('shows the floating toggle button', () => {
    cy.get(TOGGLE).should('be.visible');
  });

  it('opens panel when toggle button clicked', () => {
    openPanel();
    cy.contains('MySuricates').should('be.visible');
    cy.contains(a.greetingSubtitle).should('be.visible');
  });

  it('closes panel on X button click', () => {
    openPanel();
    cy.contains('MySuricates').should('be.visible');
    // The X button in the header (last one in the header row)
    cy.get(TOGGLE).click();
    cy.contains(a.greetingSubtitle).should('not.be.visible');
  });

  it('shows suggestions on initial open', () => {
    openPanel();
    cy.contains('Was lief heute?').should('be.visible');
    cy.contains('Gibt es Probleme?').should('be.visible');
    cy.contains('Was steht an?').should('be.visible');
  });

  it('clicking a suggestion fills the input', () => {
    openPanel();
    cy.contains('Was lief heute?').click();
    cy.get(INPUT).should('have.value', 'Was lief heute?');
  });

  it('send button is disabled when input is empty', () => {
    openPanel();
    cy.get(INPUT).should('have.value', '');
    // The send button (round accent button) should be disabled
    cy.get(SEND).should('be.disabled');
  });

  it('typing a message enables send button', () => {
    openPanel();
    cy.get(INPUT).type('Hallo');
    cy.get(SEND).should('not.be.disabled');
  });

  it('sending a message shows user bubble', () => {
    openPanel();
    cy.get(INPUT).type('Test Nachricht');
    cy.get(SEND).click();
    cy.contains('Test Nachricht').should('be.visible');
    // User avatar in the UI language
    cy.contains(a.userAvatar).should('be.visible');
  });

  it('response appears as assistant bubble', () => {
    openPanel();
    cy.get(INPUT).type('Hallo');
    cy.get(SEND).click();
    cy.wait('@chat');
    cy.contains('Hier ist meine Antwort auf deine Frage.').should('be.visible');
  });

  it('shows model info after response', () => {
    openPanel();
    cy.get(INPUT).type('Test');
    cy.get(SEND).click();
    cy.wait('@chat');
    // Model name is displayed in header subtitle (stripped prefix)
    cy.contains('sonnet').should('be.visible');
  });

  it('action buttons render for run_workflow type', () => {
    cy.intercept('POST', '/api/v1/assistant/chat', {
      body: {
        message: 'Hier ist ein Workflow.',
        actions: [{ type: 'show_workflow', id: 'wf-123', label: 'Workflow anzeigen' }],
        model: 'claude-sonnet-4-6',
      },
    }).as('chatWithActions');

    openPanel();
    cy.get(INPUT).type('Zeig mir einen Workflow');
    cy.get(SEND).click();
    cy.wait('@chatWithActions');
    cy.contains('Workflow anzeigen').should('be.visible');
  });

  it('action buttons render for ask_choice type', () => {
    cy.intercept('POST', '/api/v1/assistant/chat', {
      body: {
        message: 'Welche Option?',
        actions: [{ type: 'ask_choice', options: ['Option A', 'Option B'] }],
        model: 'claude-sonnet-4-6',
      },
    }).as('chatWithChoice');

    openPanel();
    cy.get(INPUT).type('Frage');
    cy.get(SEND).click();
    cy.wait('@chatWithChoice');
    cy.contains('Option A').should('be.visible');
    cy.contains('Option B').should('be.visible');
  });

  it('new session button creates fresh chat', () => {
    openPanel();
    // Send a message first
    cy.get(INPUT).type('Erste Nachricht');
    cy.get(SEND).click();
    cy.wait('@chat');
    cy.contains('Hier ist meine Antwort').should('be.visible');

    // Click new session button
    cy.get(`button[title="${a.newSession}"]`).click();
    // Old messages should be gone, empty state should show
    cy.contains('Hier ist meine Antwort').should('not.exist');
    cy.contains(a.greetingSubtitle).should('be.visible');
  });

  it('Enter key sends message', () => {
    openPanel();
    cy.get(INPUT).type('Enter Test{enter}');
    cy.wait('@chat');
    cy.contains('Hier ist meine Antwort').should('be.visible');
  });

  it('Shift+Enter does not send message', () => {
    openPanel();
    cy.get(INPUT).type('Zeile 1{shift+enter}Zeile 2');
    // Message should NOT be sent
    cy.get('@chat.all').should('have.length', 0);
    cy.get(INPUT).should('contain.value', 'Zeile 1');
  });

  it('history button opens conversation history', () => {
    openPanel();
    cy.get(`button[title="${a.historyToggle}"]`).click();
    cy.contains(a.history).should('be.visible');
  });

  it('ESC closes the panel', () => {
    openPanel();
    cy.contains('MySuricates').should('be.visible');
    cy.get('body').type('{esc}');
    cy.contains(a.greetingSubtitle).should('not.be.visible');
  });

  it('error response shows error bubble with retry', () => {
    cy.intercept('POST', '/api/v1/assistant/chat', { statusCode: 500, body: { message: 'Server Error' } }).as('chatError');

    openPanel();
    cy.get(INPUT).type('Fehler Test');
    cy.get(SEND).click();
    cy.wait('@chatError');
    cy.contains(a.errorReply).should('be.visible');
    cy.contains('button', en.common.retry).should('be.visible');
  });
});
