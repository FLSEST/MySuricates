export interface SetupStep {
  title: { de: string; en: string };
  instructions: { de: string[]; en: string[] };
  link?: { url: string; label: { de: string; en: string } };
  code?: string;
}

export interface SetupGuide {
  steps: SetupStep[];
}

export const SETUP_GUIDES: Record<string, SetupGuide> = {
  'google-calendar': {
    steps: [
      {
        title: {
          de: 'Google Cloud Projekt erstellen',
          en: 'Create Google Cloud Project',
        },
        instructions: {
          de: [
            'Melde dich bei der Google Cloud Console an',
            'Klicke oben links auf "Projekt auswählen" → "Neues Projekt"',
            'Name: z.B. "MySuricates" → "Erstellen"',
          ],
          en: [
            'Sign in to the Google Cloud Console',
            'Click "Select a project" at the top left → "New Project"',
            'Name: e.g. "MySuricates" → "Create"',
          ],
        },
        link: {
          url: 'https://console.cloud.google.com/projectcreate',
          label: { de: 'Google Cloud Console', en: 'Google Cloud Console' },
        },
      },
      {
        title: {
          de: 'Google Calendar API aktivieren',
          en: 'Enable Google Calendar API',
        },
        instructions: {
          de: [
            'Im Seitenmenü: "APIs & Services" → "Library"',
            'Suche nach "Google Calendar API"',
            'Klicke auf das Ergebnis → "Enable"',
          ],
          en: [
            'In the side menu: "APIs & Services" → "Library"',
            'Search for "Google Calendar API"',
            'Click the result → "Enable"',
          ],
        },
        link: {
          url: 'https://console.cloud.google.com/apis/library/calendar-json.googleapis.com',
          label: { de: 'Calendar API aktivieren', en: 'Enable Calendar API' },
        },
      },
      {
        title: {
          de: 'OAuth Consent Screen konfigurieren',
          en: 'Configure OAuth Consent Screen',
        },
        instructions: {
          de: [
            '"APIs & Services" → "OAuth consent screen"',
            'User Type: "External" → "Create"',
            'App-Name: "MySuricates", deine E-Mail als Support-Kontakt',
            'Scopes: "Add or Remove Scopes" → "calendar.events" anhaken',
            'Test Users: Deine eigene Gmail-Adresse hinzufügen',
          ],
          en: [
            '"APIs & Services" → "OAuth consent screen"',
            'User Type: "External" → "Create"',
            'App name: "MySuricates", your email as support contact',
            'Scopes: "Add or Remove Scopes" → check "calendar.events"',
            'Test Users: Add your own Gmail address',
          ],
        },
        link: {
          url: 'https://console.cloud.google.com/apis/credentials/consent',
          label: { de: 'Consent Screen', en: 'Consent Screen' },
        },
      },
      {
        title: {
          de: 'OAuth2 Credentials anlegen',
          en: 'Create OAuth2 Credentials',
        },
        instructions: {
          de: [
            '"APIs & Services" → "Credentials" → "Create Credentials" → "OAuth client ID"',
            'Application type: "Web application"',
            'Name: z.B. "MySuricates Local"',
            'Authorized redirect URI hinzufügen:',
          ],
          en: [
            '"APIs & Services" → "Credentials" → "Create Credentials" → "OAuth client ID"',
            'Application type: "Web application"',
            'Name: e.g. "MySuricates Local"',
            'Add Authorized redirect URI:',
          ],
        },
        code: 'http://localhost:9090/api/v1/integrations/google-calendar/callback',
        link: {
          url: 'https://console.cloud.google.com/apis/credentials',
          label: { de: 'Credentials anlegen', en: 'Create Credentials' },
        },
      },
      {
        title: {
          de: 'Zugangsdaten eintragen',
          en: 'Enter credentials',
        },
        instructions: {
          de: [
            'Kopiere Client-ID und Clientschlüssel aus dem vorherigen Schritt',
            'Trage sie unten in die Felder ein und klicke "Speichern"',
          ],
          en: [
            'Copy Client ID and Client Secret from the previous step',
            'Enter them in the fields below and click "Save"',
          ],
        },
      },
    ],
  },
  'trello': {
    steps: [
      {
        title: {
          de: 'Trello Power-Up Admin öffnen',
          en: 'Open Trello Power-Up Admin',
        },
        instructions: {
          de: [
            'Melde dich bei Trello an und öffne den Power-Up Admin',
            'Falls noch kein Power-Up existiert: "Neu" klicken und einen Namen vergeben (z.B. "MySuricates")',
          ],
          en: [
            'Sign in to Trello and open the Power-Up Admin',
            'If no Power-Up exists: click "New" and choose a name (e.g. "MySuricates")',
          ],
        },
        link: {
          url: 'https://trello.com/power-ups/admin',
          label: { de: 'Trello Power-Up Admin', en: 'Trello Power-Up Admin' },
        },
      },
      {
        title: {
          de: 'API-Key kopieren',
          en: 'Copy API Key',
        },
        instructions: {
          de: [
            'Auf der Power-Up-Seite findest du den API-Key',
            'Kopiere den API-Key — er wird unten als "Client ID" eingetragen',
          ],
          en: [
            'On the Power-Up page you will find the API Key',
            'Copy the API Key — it will be entered below as "Client ID"',
          ],
        },
      },
      {
        title: {
          de: 'Token generieren',
          en: 'Generate Token',
        },
        instructions: {
          de: [
            'Klicke auf den Link rechts neben dem API-Key: "Token manuell generieren"',
            'Erlaube den Zugriff auf dein Trello-Konto',
            'Kopiere den generierten Token — er wird unten als "Client Secret" eingetragen',
          ],
          en: [
            'Click the link next to the API Key: "manually generate a Token"',
            'Allow access to your Trello account',
            'Copy the generated token — it will be entered below as "Client Secret"',
          ],
        },
      },
      {
        title: {
          de: 'Zugangsdaten eintragen',
          en: 'Enter credentials',
        },
        instructions: {
          de: [
            'Trage den API-Key als "Client ID" ein',
            'Trage den Token als "Client Secret" ein',
            'Klicke "Speichern" — die Verbindung wird direkt hergestellt',
          ],
          en: [
            'Enter the API Key as "Client ID"',
            'Enter the Token as "Client Secret"',
            'Click "Save" — the connection will be established immediately',
          ],
        },
      },
    ],
  },
  'jira': {
    steps: [
      {
        title: {
          de: 'Atlassian Developer Console öffnen',
          en: 'Open Atlassian Developer Console',
        },
        instructions: {
          de: [
            'Melde dich bei der Atlassian Developer Console an',
            'Falls nötig, erstelle einen Atlassian-Account',
          ],
          en: [
            'Sign in to the Atlassian Developer Console',
            'Create an Atlassian account if needed',
          ],
        },
        link: {
          url: 'https://developer.atlassian.com/console/myapps/',
          label: { de: 'Atlassian Developer Console', en: 'Atlassian Developer Console' },
        },
      },
      {
        title: {
          de: 'OAuth 2.0 App erstellen',
          en: 'Create OAuth 2.0 App',
        },
        instructions: {
          de: [
            'Klicke "Create" → "OAuth 2.0 integration"',
            'Name: z.B. "MySuricates Jira"',
            'Akzeptiere die Nutzungsbedingungen → "Create"',
          ],
          en: [
            'Click "Create" → "OAuth 2.0 integration"',
            'Name: e.g. "MySuricates Jira"',
            'Accept the terms → "Create"',
          ],
        },
      },
      {
        title: {
          de: 'Jira API Berechtigungen hinzufügen',
          en: 'Add Jira API Permissions',
        },
        instructions: {
          de: [
            'Im Seitenmenü: "Permissions"',
            'Bei "Jira API" auf "Add" klicken',
            'Scopes hinzufügen: "read:jira-work", "write:jira-work", "read:jira-user"',
          ],
          en: [
            'In the side menu: "Permissions"',
            'Click "Add" next to "Jira API"',
            'Add scopes: "read:jira-work", "write:jira-work", "read:jira-user"',
          ],
        },
      },
      {
        title: {
          de: 'Callback URL konfigurieren',
          en: 'Configure Callback URL',
        },
        instructions: {
          de: [
            'Im Seitenmenü: "Authorization" → "Add" bei OAuth 2.0 (3LO)',
            'Callback URL hinzufügen:',
          ],
          en: [
            'In the side menu: "Authorization" → "Add" next to OAuth 2.0 (3LO)',
            'Add callback URL:',
          ],
        },
        code: 'http://localhost:9090/api/v1/integrations/jira/callback',
      },
      {
        title: {
          de: 'Zugangsdaten eintragen',
          en: 'Enter credentials',
        },
        instructions: {
          de: [
            'Im Seitenmenü: "Settings" → Client-ID und Secret kopieren',
            'Trage sie unten in die Felder ein und klicke "Speichern"',
          ],
          en: [
            'In the side menu: "Settings" → copy Client ID and Secret',
            'Enter them in the fields below and click "Save"',
          ],
        },
      },
    ],
  },
};
