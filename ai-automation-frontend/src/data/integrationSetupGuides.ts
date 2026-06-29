export interface SetupStep {
  title: { de: string; en: string };
  instructions: { de: string[]; en: string[] };
  link?: { url: string; label: { de: string; en: string } };
  code?: string;
}

export interface CredentialLabels {
  clientIdPlaceholder: { de: string; en: string };
  clientSecretPlaceholder: { de: string; en: string };
}

export interface SetupGuide {
  steps: SetupStep[];
  credentialLabels?: CredentialLabels;
}

function backendBaseUrl(): string {
  const explicit = (import.meta as { env?: Record<string, string | undefined> }).env?.VITE_API_BASE_URL?.trim();
  if (explicit) {
    return explicit.replace(/\/api\/v1\/?$/, '').replace(/\/$/, '');
  }
  if (window.location.port === '3000') {
    const target = (import.meta as { env?: Record<string, string | undefined> }).env?.CYPRESS_TARGET === 'test'
      ? '8090'
      : '9090';
    return `${window.location.protocol}//${window.location.hostname}:${target}`;
  }
  return window.location.origin;
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
        code: `${backendBaseUrl()}/api/v1/integrations/google-calendar/callback`,
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
  'google-gmail': {
    steps: [
      {
        title: {
          de: 'Bestehendes Google Cloud Projekt nutzen',
          en: 'Reuse existing Google Cloud project',
        },
        instructions: {
          de: [
            'Wenn Google Calendar bereits eingerichtet ist, nutze dasselbe Google Cloud Projekt und dieselben OAuth-Zugangsdaten',
            'Falls noch kein Projekt existiert: Erstelle eines in der Google Cloud Console',
            'Achte darauf, dass deine eigene Gmail-Adresse als Test User eingetragen ist',
          ],
          en: [
            'If Google Calendar is already set up, reuse the same Google Cloud project and OAuth credentials',
            'If no project exists yet, create one in Google Cloud Console',
            'Make sure your own Gmail address is added as a test user',
          ],
        },
        link: {
          url: 'https://console.cloud.google.com/apis/credentials',
          label: { de: 'Google Cloud Console', en: 'Google Cloud Console' },
        },
      },
      {
        title: {
          de: 'Gmail API aktivieren',
          en: 'Enable Gmail API',
        },
        instructions: {
          de: [
            'Im Seitenmenue: "APIs & Services" -> "Library"',
            'Suche nach "Gmail API"',
            'Klicke auf das Ergebnis -> "Enable"',
          ],
          en: [
            'In the side menu: "APIs & Services" -> "Library"',
            'Search for "Gmail API"',
            'Click the result -> "Enable"',
          ],
        },
        link: {
          url: 'https://console.cloud.google.com/apis/library/gmail.googleapis.com',
          label: { de: 'Gmail API aktivieren', en: 'Enable Gmail API' },
        },
      },
      {
        title: {
          de: 'OAuth Consent Screen pruefen',
          en: 'Review OAuth consent screen',
        },
        instructions: {
          de: [
            'Oeffne "APIs & Services" -> "OAuth consent screen"',
            'Fuege den Scope ".../auth/gmail.readonly" hinzu',
            'Falls die App noch im Testmodus ist: eigene Gmail-Adresse als Test User belassen oder ergaenzen',
          ],
          en: [
            'Open "APIs & Services" -> "OAuth consent screen"',
            'Add the scope ".../auth/gmail.readonly"',
            'If the app is still in testing mode, keep or add your own Gmail address as a test user',
          ],
        },
        link: {
          url: 'https://console.cloud.google.com/apis/credentials/consent',
          label: { de: 'Consent Screen', en: 'Consent Screen' },
        },
      },
      {
        title: {
          de: 'Redirect URI ergaenzen oder pruefen',
          en: 'Add or verify redirect URI',
        },
        instructions: {
          de: [
            'Oeffne "APIs & Services" -> "Credentials"',
            'Bearbeite den bestehenden OAuth Client oder erstelle einen neuen vom Typ "Web application"',
            'Diese Redirect URI muss hinterlegt sein:',
          ],
          en: [
            'Open "APIs & Services" -> "Credentials"',
            'Edit the existing OAuth client or create a new "Web application" client',
            'This redirect URI must be configured:',
          ],
        },
        code: `${backendBaseUrl()}/api/v1/integrations/google-gmail/callback`,
        link: {
          url: 'https://console.cloud.google.com/apis/credentials',
          label: { de: 'Credentials oeffnen', en: 'Open credentials' },
        },
      },
      {
        title: {
          de: 'Zugangsdaten eintragen und verbinden',
          en: 'Enter credentials and connect',
        },
        instructions: {
          de: [
            'Trage dieselbe Client-ID und denselben Clientschluessel wie fuer Google Calendar ein',
            'Klicke auf "Speichern" und danach auf "Verbinden"',
            'Google fragt danach einmalig den Read-only-Zugriff auf Gmail an',
          ],
          en: [
            'Enter the same client ID and client secret used for Google Calendar',
            'Click "Save" and then "Connect"',
            'Google will then ask once for read-only access to Gmail',
          ],
        },
      },
    ],
  },
  'taskwarrior': {
    credentialLabels: {
      clientIdPlaceholder: { de: 'TASKDATA-Pfad (optional)', en: 'TASKDATA path (optional)' },
      clientSecretPlaceholder: { de: 'TASKRC-Pfad (optional)', en: 'TASKRC path (optional)' },
    },
    steps: [
      {
        title: {
          de: 'Taskwarrior installieren',
          en: 'Install Taskwarrior',
        },
        instructions: {
          de: [
            'Taskwarrior muss auf dem Host laufen, auf dem das MySuricates-Backend läuft',
            'Debian/Ubuntu: "sudo apt install taskwarrior" · macOS: "brew install task"',
            'Andere Systeme: siehe Download-Seite (Link unten)',
          ],
          en: [
            'Taskwarrior must run on the host where the MySuricates backend runs',
            'Debian/Ubuntu: "sudo apt install taskwarrior" · macOS: "brew install task"',
            'Other systems: see the download page (link below)',
          ],
        },
        link: {
          url: 'https://taskwarrior.org/download/',
          label: { de: 'Taskwarrior Download', en: 'Taskwarrior Download' },
        },
        code: 'sudo apt install taskwarrior',
      },
      {
        title: {
          de: 'Installation prüfen',
          en: 'Verify the installation',
        },
        instructions: {
          de: [
            'Führe "task --version" aus — erwartet wird Version 3.x oder neuer',
            'Beim ersten Aufruf legt Taskwarrior automatisch ~/.taskrc und ~/.task an',
          ],
          en: [
            'Run "task --version" — version 3.x or newer is expected',
            'On first run Taskwarrior creates ~/.taskrc and ~/.task automatically',
          ],
        },
        code: 'task --version',
      },
      {
        title: {
          de: 'Datenverzeichnis (optional)',
          en: 'Data directory (optional)',
        },
        instructions: {
          de: [
            'Standard: ~/.task (Daten) und ~/.taskrc (Konfiguration) — Felder unten leer lassen',
            'Nur wenn deine Tasks woanders liegen: TASKDATA- bzw. TASKRC-Pfad unten eintragen',
          ],
          en: [
            'Default: ~/.task (data) and ~/.taskrc (config) — leave the fields below empty',
            'Only if your tasks live elsewhere: enter the TASKDATA / TASKRC path below',
          ],
        },
      },
      {
        title: {
          de: 'Geräteübergreifender Sync (optional)',
          en: 'Cross-device sync (optional)',
        },
        instructions: {
          de: [
            'Taskwarrior ist standardmäßig rein lokal',
            'Für mehrere Geräte: einen Sync-Server in ~/.taskrc konfigurieren und "task sync" nutzen',
            'Die Integration bietet dafür die Aktion "sync_task"',
          ],
          en: [
            'Taskwarrior is purely local by default',
            'For multiple devices: configure a sync server in ~/.taskrc and use "task sync"',
            'The integration exposes the "sync_task" action for this',
          ],
        },
        link: {
          url: 'https://taskwarrior.org/docs/sync/',
          label: { de: 'Taskwarrior Sync-Doku', en: 'Taskwarrior Sync docs' },
        },
      },
      {
        title: {
          de: 'Verbinden',
          en: 'Connect',
        },
        instructions: {
          de: [
            'Pfade optional eintragen (oder leer lassen für die Standardwerte)',
            '"Zugangsdaten speichern" klicken — der Status wird grün, sobald "task" erreichbar ist',
          ],
          en: [
            'Enter the paths optionally (or leave empty for the defaults)',
            'Click "Save credentials" — the status turns green once "task" is reachable',
          ],
        },
      },
    ],
  },
  'trello': {
    credentialLabels: {
      clientIdPlaceholder: { de: 'API-Schlüssel', en: 'API Key' },
      clientSecretPlaceholder: { de: 'Token', en: 'Token' },
    },
    steps: [
      {
        title: {
          de: 'API-Schlüssel kopieren',
          en: 'Copy API Key',
        },
        instructions: {
          de: [
            'Öffne den Trello Power-Up Admin (Link unten)',
            'Wähle dein Power-Up aus (oder erstelle ein neues)',
            'Gehe zum Tab "API-Schlüssel" und kopiere den Schlüssel',
            'Das "Secret" auf derselben Seite wird nicht benötigt',
          ],
          en: [
            'Open the Trello Power-Up Admin (link below)',
            'Select your Power-Up (or create a new one)',
            'Go to the "API Key" tab and copy the key',
            'The "Secret" on the same page is not needed',
          ],
        },
        link: {
          url: 'https://trello.com/power-ups/admin',
          label: { de: 'Trello Power-Up Admin', en: 'Trello Power-Up Admin' },
        },
      },
      {
        title: {
          de: 'API-Schlüssel eintragen & Token generieren',
          en: 'Enter API Key & generate Token',
        },
        instructions: {
          de: [
            'Trage den API-Schlüssel unten ein — der Token-Link erscheint automatisch',
            'Klicke den Link, erlaube den Zugriff, und kopiere den angezeigten Token',
            'Trage den Token in das zweite Feld ein',
          ],
          en: [
            'Enter the API Key below — the token link will appear automatically',
            'Click the link, allow access, and copy the displayed token',
            'Enter the token in the second field',
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
            'Pflicht-Scopes: "read:jira-work", "read:jira-user". Optional für Schreibzugriff: "write:jira-work"',
          ],
          en: [
            'In the side menu: "Permissions"',
            'Click "Add" next to "Jira API"',
            'Required scopes: "read:jira-work", "read:jira-user". Optional for write access: "write:jira-work"',
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
        code: `${window.location.port === '3000' ? window.location.origin.replace(':3000', ':9090') : window.location.origin}/api/v1/integrations/jira/callback`,
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

  'github': {
    credentialLabels: {
      clientIdPlaceholder: { de: 'GitHub-Benutzername (optional)', en: 'GitHub username (optional)' },
      clientSecretPlaceholder: { de: 'Personal Access Token (PAT)', en: 'Personal Access Token (PAT)' },
    },
    steps: [
      {
        title: {
          de: 'Personal Access Token erstellen',
          en: 'Create a Personal Access Token',
        },
        instructions: {
          de: [
            'Öffne die GitHub Token-Einstellungen (Link unten)',
            'Empfohlen: "Fine-grained token" → wähle das/die Repository/s aus',
            'Repository-Berechtigungen: "Issues" auf "Read and write" setzen',
            '(Alternativ: klassischer Token mit Scope "repo")',
            'Token erzeugen und sofort kopieren — er wird nur einmal angezeigt',
          ],
          en: [
            'Open the GitHub token settings (link below)',
            'Recommended: "Fine-grained token" → select the repository/repositories',
            'Repository permissions: set "Issues" to "Read and write"',
            '(Alternatively: classic token with the "repo" scope)',
            'Generate the token and copy it immediately — it is shown only once',
          ],
        },
        link: {
          url: 'https://github.com/settings/tokens',
          label: { de: 'GitHub Token-Einstellungen', en: 'GitHub token settings' },
        },
      },
      {
        title: {
          de: 'Zugangsdaten eintragen',
          en: 'Enter credentials',
        },
        instructions: {
          de: [
            'GitHub-Benutzername in das erste Feld (optional, nur zur Anzeige)',
            'Personal Access Token in das zweite Feld',
            '"Zugangsdaten speichern" klicken — der Status wird grün, sobald der Token gültig ist',
          ],
          en: [
            'GitHub username in the first field (optional, for display only)',
            'Personal Access Token in the second field',
            'Click "Save credentials" — the status turns green once the token is valid',
          ],
        },
      },
    ],
  },

  'confluence': {
    steps: [
      {
        title: {
          de: 'Atlassian Developer Console öffnen',
          en: 'Open Atlassian Developer Console',
        },
        instructions: {
          de: [
            'Melde dich bei der Atlassian Developer Console an',
            'Du kannst dieselbe App wie für Jira verwenden, oder eine neue erstellen',
          ],
          en: [
            'Sign in to the Atlassian Developer Console',
            'You can use the same app as for Jira, or create a new one',
          ],
        },
        link: {
          url: 'https://developer.atlassian.com/console/myapps/',
          label: { de: 'Atlassian Developer Console', en: 'Atlassian Developer Console' },
        },
      },
      {
        title: {
          de: 'OAuth 2.0 App erstellen (falls nötig)',
          en: 'Create OAuth 2.0 App (if needed)',
        },
        instructions: {
          de: [
            'Klicke "Create" → "OAuth 2.0 integration"',
            'Name: z.B. "MySuricates Confluence"',
            'Akzeptiere die Nutzungsbedingungen → "Create"',
          ],
          en: [
            'Click "Create" → "OAuth 2.0 integration"',
            'Name: e.g. "MySuricates Confluence"',
            'Accept the terms → "Create"',
          ],
        },
      },
      {
        title: {
          de: 'Confluence API Berechtigungen hinzufügen',
          en: 'Add Confluence API Permissions',
        },
        instructions: {
          de: [
            'Im Seitenmenü: "Permissions"',
            'Bei "Confluence API" auf "Add" klicken',
            'Pflicht-Scopes: "read:confluence-content.all", "read:confluence-space.summary", "search:confluence". Optional für Schreibzugriff: "write:confluence-content"',
          ],
          en: [
            'In the side menu: "Permissions"',
            'Click "Add" next to "Confluence API"',
            'Required scopes: "read:confluence-content.all", "read:confluence-space.summary", "search:confluence". Optional for write access: "write:confluence-content"',
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
        code: `${window.location.port === '3000' ? window.location.origin.replace(':3000', ':9090') : window.location.origin}/api/v1/integrations/confluence/callback`,
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
