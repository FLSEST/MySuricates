export interface SetupStep {
  title: { de: string; en: string };
  instructions: { de: string[]; en: string[] };
  link?: { url: string; label: { de: string; en: string } };
  code?: string;
}

export interface CredentialLabels {
  clientIdPlaceholder: { de: string; en: string };
  clientSecretPlaceholder: { de: string; en: string };
  /** Prefills the first field, e.g. the public server of a self-hostable service; the user may overwrite it. */
  clientIdDefault?: string;
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

  'gitea': {
    credentialLabels: {
      clientIdPlaceholder: { de: 'Server-URL (z. B. https://git.example.org)', en: 'Server URL (e.g. https://git.example.org)' },
      clientSecretPlaceholder: { de: 'Zugangstoken (Access Token)', en: 'Access token' },
    },
    steps: [
      {
        title: {
          de: 'Zugangstoken erstellen',
          en: 'Create an access token',
        },
        instructions: {
          de: [
            'Auf deinem Gitea- bzw. Forgejo-Server anmelden',
            'Oben rechts: Profilbild → "Einstellungen" → "Anwendungen"',
            'Unter "Zugangstoken verwalten" einen Namen eingeben, z. B. "MySuricates"',
            'Berechtigungen: "issue" auf Lesen und Schreiben, "repository" und "user" auf Lesen',
            'Token erzeugen und sofort kopieren — er wird nur einmal angezeigt',
          ],
          en: [
            'Sign in to your Gitea or Forgejo server',
            'Top right: avatar → "Settings" → "Applications"',
            'Under "Manage access tokens" enter a name, e.g. "MySuricates"',
            'Permissions: "issue" read and write, "repository" and "user" read',
            'Generate the token and copy it immediately — it is shown only once',
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
            'Server-URL in das erste Feld, nur die Basisadresse ohne /api/v1 (z. B. https://git.example.org oder https://codeberg.org)',
            'Zugangstoken in das zweite Feld',
            '"Zugangsdaten speichern" klicken — der Status wird grün, sobald Server und Token passen',
            'Labels gibst du in Workflows als Namen an; sie müssen im Repository schon existieren',
          ],
          en: [
            'Server URL in the first field, just the base address without /api/v1 (e.g. https://git.example.org or https://codeberg.org)',
            'Access token in the second field',
            'Click "Save credentials" — the status turns green once server and token match',
            'Labels are given by name in workflows; they must already exist in the repository',
          ],
        },
      },
    ],
  },

  'gitlab': {
    credentialLabels: {
      clientIdPlaceholder: { de: 'Server-URL (https://gitlab.com oder eigene Instanz)', en: 'Server URL (https://gitlab.com or your own instance)' },
      clientSecretPlaceholder: { de: 'Access Token (glpat-…)', en: 'Access token (glpat-…)' },
      clientIdDefault: 'https://gitlab.com',
    },
    steps: [
      {
        title: {
          de: 'Access Token erstellen',
          en: 'Create an access token',
        },
        instructions: {
          de: [
            'In GitLab: Profilbild → "Einstellungen" → "Zugriffstoken" (Link unten gilt für gitlab.com)',
            'Empfohlen für ein einzelnes Projekt: stattdessen ein Projekt-Access-Token unter Projekt → "Einstellungen" → "Zugriffstoken"',
            'Scope "api" zum Anlegen, Ändern und Kommentieren; "read_api" reicht, wenn nur gelesen wird',
            'Ablaufdatum setzen, Token erzeugen und sofort kopieren — er wird nur einmal angezeigt',
          ],
          en: [
            'In GitLab: avatar → "Preferences" → "Access tokens" (the link below is for gitlab.com)',
            'Recommended for a single project: a project access token instead, under project → "Settings" → "Access tokens"',
            'Scope "api" to create, change and comment; "read_api" is enough for reading only',
            'Set an expiry date, create the token and copy it immediately — it is shown only once',
          ],
        },
        link: {
          url: 'https://gitlab.com/-/user_settings/personal_access_tokens',
          label: { de: 'GitLab Access Tokens', en: 'GitLab access tokens' },
        },
      },
      {
        title: {
          de: 'Zugangsdaten eintragen',
          en: 'Enter credentials',
        },
        instructions: {
          de: [
            'Server-URL im ersten Feld: https://gitlab.com ist vorausgefüllt, bei eigener Instanz deren Basisadresse ohne /api/v4',
            'Access Token in das zweite Feld',
            '"Zugangsdaten speichern" klicken — der Status wird grün, sobald Server und Token passen',
            'Projekte gibst du in Workflows als Pfad an (gruppe/projekt, auch Untergruppen) oder als numerische ID',
          ],
          en: [
            'Server URL in the first field: https://gitlab.com is prefilled; for your own instance use its base address without /api/v4',
            'Access token in the second field',
            'Click "Save credentials" — the status turns green once server and token match',
            'Workflows address projects by path (group/project, subgroups too) or by numeric ID',
          ],
        },
      },
    ],
  },

  'ntfy': {
    credentialLabels: {
      clientIdPlaceholder: { de: 'Server-URL (leer = https://ntfy.sh)', en: 'Server URL (empty = https://ntfy.sh)' },
      clientSecretPlaceholder: { de: 'Access-Token (optional)', en: 'Access token (optional)' },
    },
    steps: [
      {
        title: {
          de: 'Server wählen',
          en: 'Choose a server',
        },
        instructions: {
          de: [
            'Ohne Eintrag sendet die Integration an den öffentlichen Dienst ntfy.sh',
            'Auf ntfy.sh kann jeder ein Topic lesen, der seinen Namen kennt — schwer erratbare Topic-Namen verwenden',
            'Eigener Server: dessen Adresse (z.B. https://ntfy.example.org) ins erste Feld',
          ],
          en: [
            'Without an entry the integration sends to the public ntfy.sh service',
            'On ntfy.sh anyone who knows a topic name can read it — use hard-to-guess topic names',
            'Own server: put its address (e.g. https://ntfy.example.org) into the first field',
          ],
        },
        link: {
          url: 'https://docs.ntfy.sh/',
          label: { de: 'ntfy-Dokumentation', en: 'ntfy documentation' },
        },
      },
      {
        title: {
          de: 'Topic abonnieren',
          en: 'Subscribe to a topic',
        },
        instructions: {
          de: [
            'ntfy-App (Android/iOS) oder Web-App öffnen',
            'Topic abonnieren, an das Workflows später senden (Buchstaben, Ziffern, - und _)',
          ],
          en: [
            'Open the ntfy app (Android/iOS) or the web app',
            'Subscribe to the topic your workflows will send to (letters, digits, - and _)',
          ],
        },
        link: {
          url: 'https://docs.ntfy.sh/subscribe/phone/',
          label: { de: 'Apps und Abonnieren', en: 'Apps and subscribing' },
        },
      },
      {
        title: {
          de: 'Access-Token (optional)',
          en: 'Access token (optional)',
        },
        instructions: {
          de: [
            'Nur nötig, wenn der Server Topics schützt (Zugriffskontrolle) oder für ein ntfy.sh-Konto',
            'Web-App: Konto → Access-Tokens → Token erstellen; auf dem eigenen Server alternativ per CLI',
            'Leer lassen für anonymes Senden — dann wird kein Authorization-Header mitgeschickt',
          ],
          en: [
            'Only needed if the server protects topics (access control) or for an ntfy.sh account',
            'Web app: Account → Access tokens → create token; on your own server alternatively via CLI',
            'Leave empty to send anonymously — no Authorization header is sent then',
          ],
        },
        code: 'ntfy token add <user>',
      },
      {
        title: {
          de: 'Verbinden',
          en: 'Connect',
        },
        instructions: {
          de: [
            '"Zugangsdaten speichern" klicken — geprüft wird mit Token das Konto (/v1/account), ohne Token die Erreichbarkeit (/v1/health)',
            'Im Workflow: Block "integration" mit Aktion "publish" (topic, message, optional title, priority, tags, click, markdown)',
          ],
          en: [
            'Click "Save credentials" — with a token the account is checked (/v1/account), without one the server health (/v1/health)',
            'In a workflow: block "integration" with action "publish" (topic, message, optional title, priority, tags, click, markdown)',
          ],
        },
      },
    ],
  },

  'feed': {
    steps: [
      {
        title: {
          de: 'Keine Zugangsdaten nötig',
          en: 'No credentials needed',
        },
        instructions: {
          de: [
            'Liest öffentliche RSS-2.0-, Atom- und RSS-1.0-Feeds — ohne Konto und ohne Schlüssel',
            'Felder leer lassen und "Zugangsdaten speichern" klicken, um die Integration als eingerichtet zu markieren (optional)',
            'Im Workflow: Block "integration" mit Aktion "fetch_feed" (url, optional since als ISO-Datum, limit Standard 20)',
            'Nur http/https, höchstens 5 MB und 5 Weiterleitungen je Abruf',
          ],
          en: [
            'Reads public RSS 2.0, Atom and RSS 1.0 feeds — no account, no key',
            'Leave the fields empty and click "Save credentials" to mark the integration as set up (optional)',
            'In a workflow: block "integration" with action "fetch_feed" (url, optional since as ISO date, limit default 20)',
            'http/https only, at most 5 MB and 5 redirects per fetch',
          ],
        },
      },
    ],
  },

  'open-meteo': {
    steps: [
      {
        title: {
          de: 'Keine Zugangsdaten nötig',
          en: 'No credentials needed',
        },
        instructions: {
          de: [
            'Open-Meteo ist für nicht-kommerzielle Nutzung frei und braucht keinen API-Key',
            'Felder leer lassen und "Zugangsdaten speichern" klicken, um die Integration als eingerichtet zu markieren (optional)',
            'Aktion "geocode" (name, count, language) liefert Koordinaten zu einem Ort',
            'Aktion "forecast" (latitude, longitude, days 1-16, hourly/daily/current, timezone Standard auto) liefert die Vorhersage',
          ],
          en: [
            'Open-Meteo is free for non-commercial use and needs no API key',
            'Leave the fields empty and click "Save credentials" to mark the integration as set up (optional)',
            'Action "geocode" (name, count, language) returns coordinates for a place',
            'Action "forecast" (latitude, longitude, days 1-16, hourly/daily/current, timezone default auto) returns the forecast',
          ],
        },
        link: {
          url: 'https://open-meteo.com/en/docs',
          label: { de: 'Open-Meteo API-Doku (Variablen)', en: 'Open-Meteo API docs (variables)' },
        },
      },
    ],
  },

  'http-api': {
    credentialLabels: {
      clientIdPlaceholder: { de: 'Basis-URL, z. B. https://api.example.com/v1', en: 'Base URL, e.g. https://api.example.com/v1' },
      clientSecretPlaceholder: { de: 'Auth-Header (optional), z. B. Authorization: Bearer …', en: 'Auth header (optional), e.g. Authorization: Bearer …' },
    },
    steps: [
      {
        title: {
          de: 'Dienst und Zugang klären',
          en: 'Find the service and its access',
        },
        instructions: {
          de: [
            'Basis-URL des Dienstes aus seiner API-Doku, nur http:// oder https://, ohne ?Parameter und ohne Benutzer:Passwort@',
            'Alles, was ein Block aufruft, bleibt unterhalb dieser URL — ein Pfad kann sie nicht verlassen',
            'Braucht der Dienst einen Schlüssel: den Header so notieren, wie die Doku ihn verlangt, z. B. "Authorization: Bearer <token>" oder "X-API-Key: <key>"',
            'Öffentliche APIs ohne Schlüssel: Auth-Header leer lassen',
          ],
          en: [
            'Base URL of the service from its API docs, only http:// or https://, without ?parameters and without user:password@',
            'Everything a block calls stays below this URL — a path cannot leave it',
            'If the service needs a key: note the header the way its docs ask for it, e.g. "Authorization: Bearer <token>" or "X-API-Key: <key>"',
            'Public APIs without a key: leave the auth header empty',
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
            'Basis-URL in das erste Feld, Auth-Header (Name: Wert) in das zweite',
            '"Zugangsdaten speichern" prüft nur das Format und sendet noch keine Anfrage',
            'Der Auth-Header wird verschlüsselt gespeichert, nie angezeigt und aus Antworten des Dienstes entfernt',
          ],
          en: [
            'Base URL in the first field, auth header (Name: Value) in the second',
            '"Save credentials" only checks the format and sends no request yet',
            'The auth header is stored encrypted, never shown, and scrubbed from the service\'s answers',
          ],
        },
      },
      {
        title: {
          de: 'Mehrere Dienste: benannte Verbindungen',
          en: 'Several services: named connections',
        },
        instructions: {
          de: [
            'Jeder weitere Dienst ist eine eigene Verbindung: unter „Verbindungen“ → „Weitere Verbindung“, Name z. B. "wetter"',
            'Im integration-Block wählt "connection" den Dienst: {"provider": "http-api", "action": "request", "connection": "wetter", "params": {"path": "forecast", "query": {"city": "Berlin"}}}',
            'Parameter von "request": method (GET/POST/PUT/PATCH/DELETE), path, query, headers, body, timeout_seconds, fail_on_error',
            'Weiterleitungen (3xx) werden nicht verfolgt; die Antwort nennt das Ziel unter "location"',
          ],
          en: [
            'Each further service is its own connection: under "Connections" → "Add connection", name e.g. "weather"',
            'In the integration block "connection" picks the service: {"provider": "http-api", "action": "request", "connection": "weather", "params": {"path": "forecast", "query": {"city": "Berlin"}}}',
            'Parameters of "request": method (GET/POST/PUT/PATCH/DELETE), path, query, headers, body, timeout_seconds, fail_on_error',
            'Redirects (3xx) are not followed; the answer names the target under "location"',
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

  'caldav': {
    credentialLabels: {
      clientIdPlaceholder: { de: 'CalDAV-URL, z.B. https://cloud.example.org/remote.php/dav', en: 'CalDAV URL, e.g. https://cloud.example.org/remote.php/dav' },
      clientSecretPlaceholder: { de: 'benutzer:app-passwort', en: 'user:app-password' },
    },
    steps: [
      {
        title: {
          de: 'App-Passwort anlegen',
          en: 'Create an app password',
        },
        instructions: {
          de: [
            'Nextcloud: Einstellungen → Sicherheit → „Neues App-Passwort erstellen“',
            'iCloud: account.apple.com → Anmeldung und Sicherheit → App-spezifische Passwörter',
            'Radicale/Baïkal: Benutzer und Passwort aus der Server-Konfiguration',
            'Posteo: Posteo-Zugangsdaten, Benutzer ist die E-Mail-Adresse',
          ],
          en: [
            'Nextcloud: Settings → Security → "Create new app password"',
            'iCloud: account.apple.com → Sign-In and Security → App-Specific Passwords',
            'Radicale/Baïkal: user and password from the server configuration',
            'Posteo: your Posteo login, the user is the e-mail address',
          ],
        },
      },
      {
        title: {
          de: 'CalDAV-URL eintragen',
          en: 'Enter the CalDAV URL',
        },
        instructions: {
          de: [
            'Nextcloud: https://<server>/remote.php/dav',
            'Radicale: http://<server>:5232/ · Posteo: https://posteo.de:8443/',
            'iCloud (ungeprüft): https://caldav.icloud.com/ verweist auf einen Partitions-Server (pNN-caldav.icloud.com) — Anfragen an einen anderen Server als den eingetragenen lehnt MySuricates ab, dann diese Adresse eintragen',
            'Weiterleitungen (z.B. /.well-known/caldav) werden nicht verfolgt: die Fehlermeldung nennt das Ziel',
          ],
          en: [
            'Nextcloud: https://<server>/remote.php/dav',
            'Radicale: http://<server>:5232/ · Posteo: https://posteo.de:8443/',
            'iCloud (untested): https://caldav.icloud.com/ points to a partition server (pNN-caldav.icloud.com) — MySuricates refuses requests to a server other than the one entered, so enter that address instead',
            'Redirects (e.g. /.well-known/caldav) are not followed: the error message names the target',
          ],
        },
      },
      {
        title: {
          de: 'Zugangsdaten speichern',
          en: 'Save the credentials',
        },
        instructions: {
          de: [
            'Erstes Feld: CalDAV-URL · zweites Feld: benutzer:app-passwort',
            '„Zugangsdaten speichern“ — der Status wird grün, sobald der Server die Anmeldung annimmt',
            'Kalender-hrefs für Workflows liefert die Action list_calendars',
          ],
          en: [
            'First field: CalDAV URL · second field: user:app-password',
            'Click "Save credentials" — the status turns green once the server accepts the login',
            'The action list_calendars returns the calendar hrefs for workflows',
          ],
        },
      },
    ],
  },

  'ical-feed': {
    credentialLabels: {
      clientIdPlaceholder: { de: 'Nicht nötig — leer lassen', en: 'Not needed — leave empty' },
      clientSecretPlaceholder: { de: 'Nicht nötig — leer lassen', en: 'Not needed — leave empty' },
    },
    steps: [
      {
        title: {
          de: 'Feed-Adresse besorgen',
          en: 'Get the feed address',
        },
        instructions: {
          de: [
            'Kalender-Abos heißen oft „Abonnieren“, „iCal“, „ICS“ oder beginnen mit webcal://',
            'Beispiele: Feiertage, Schulferien, Vereins- oder Veranstaltungskalender',
            'Private Links (z.B. „geheime Adresse“ eines Google-Kalenders) wirken wie ein Passwort — nur in eigenen Workflows verwenden',
          ],
          en: [
            'Calendar subscriptions are often labelled "Subscribe", "iCal", "ICS" or start with webcal://',
            'Examples: public holidays, school terms, club or event calendars',
            'Private links (e.g. the "secret address" of a Google calendar) act like a password — use them in your own workflows only',
          ],
        },
      },
      {
        title: {
          de: 'Aktivieren und im Workflow nutzen',
          en: 'Enable and use in a workflow',
        },
        instructions: {
          de: [
            'Felder leer lassen und speichern — es gibt keine Zugangsdaten',
            'Die Feed-URL ist Parameter der Action list_events, ein Abo pro Aufruf',
            'Serien werden nicht expandiert: sie stehen einmal mit rrule-Feld in der Liste',
          ],
          en: [
            'Leave the fields empty and save — there are no credentials',
            'The feed URL is a parameter of the list_events action, one feed per call',
            'Series are not expanded: they appear once, with an rrule field',
          ],
        },
        code: '{ "provider": "ical-feed", "action": "list_events", "params": { "url": "https://example.org/feiertage.ics", "from": "2026-10-01", "to": "2026-12-31" } }',
      },
    ],
  },

  'todoist': {
    credentialLabels: {
      clientIdPlaceholder: { de: 'Bezeichnung (optional, z.B. „Privat“)', en: 'Label (optional, e.g. "Personal")' },
      clientSecretPlaceholder: { de: 'Todoist-API-Token', en: 'Todoist API token' },
    },
    steps: [
      {
        title: {
          de: 'API-Token kopieren',
          en: 'Copy the API token',
        },
        instructions: {
          de: [
            'Todoist im Browser öffnen → Einstellungen → Integrationen',
            'Reiter "Entwickler" wählen (Link unten führt direkt dorthin)',
            'Unter "API-Token" auf "API-Token kopieren" klicken',
            'Der Token hat vollen Zugriff auf dein Konto — wie ein Passwort behandeln; "Neu generieren" macht den alten ungültig',
          ],
          en: [
            'Open Todoist in the browser → Settings → Integrations',
            'Choose the "Developer" tab (the link below goes straight there)',
            'Under "API token" click "Copy API token"',
            'The token has full access to your account — treat it like a password; "Regenerate" invalidates the old one',
          ],
        },
        link: {
          url: 'https://app.todoist.com/app/settings/integrations/developer',
          label: { de: 'Todoist-Einstellungen → Integrationen → Entwickler', en: 'Todoist settings → Integrations → Developer' },
        },
      },
      {
        title: {
          de: 'Zugangsdaten eintragen',
          en: 'Enter credentials',
        },
        instructions: {
          de: [
            'Erstes Feld: eine Bezeichnung zur Anzeige (optional)',
            'Zweites Feld: den API-Token einfügen',
            '"Zugangsdaten speichern" klicken — der Status wird grün, sobald Todoist den Token annimmt',
            'Für den Aufgaben-Export aus einem TODO wählst du anschließend das Todoist-Projekt als Ziel',
          ],
          en: [
            'First field: a label for display (optional)',
            'Second field: paste the API token',
            'Click "Save credentials" — the status turns green once Todoist accepts the token',
            'When exporting a TODO as a task you then pick the Todoist project as the target',
          ],
        },
      },
    ],
  },

  'slack': {
    credentialLabels: {
      clientIdPlaceholder: { de: 'Workspace-Name (optional)', en: 'Workspace name (optional)' },
      clientSecretPlaceholder: { de: 'Bot-Token (xoxb-…)', en: 'Bot token (xoxb-…)' },
    },
    steps: [
      {
        title: {
          de: 'Slack-App anlegen',
          en: 'Create a Slack app',
        },
        instructions: {
          de: [
            'Die Slack-App-Verwaltung öffnen (Link unten) → "Create New App" → "From scratch"',
            'Namen vergeben (z.B. "MySuricates") und den Workspace wählen',
          ],
          en: [
            'Open the Slack app management (link below) → "Create New App" → "From scratch"',
            'Pick a name (e.g. "MySuricates") and the workspace',
          ],
        },
        link: {
          url: 'https://api.slack.com/apps',
          label: { de: 'Slack-Apps verwalten', en: 'Manage Slack apps' },
        },
      },
      {
        title: {
          de: 'Bot-Scopes setzen und installieren',
          en: 'Set bot scopes and install',
        },
        instructions: {
          de: [
            '"OAuth & Permissions" → "Bot Token Scopes": chat:write, channels:read und channels:history hinzufügen',
            '(für private Kanäle zusätzlich groups:read und groups:history)',
            'Oben "Install to Workspace" klicken und zustimmen',
            'Den "Bot User OAuth Token" kopieren (beginnt mit xoxb-)',
            'Die App in jeden Zielkanal einladen: im Kanal /invite @App-Name eingeben',
          ],
          en: [
            '"OAuth & Permissions" → "Bot Token Scopes": add chat:write, channels:read and channels:history',
            '(for private channels also groups:read and groups:history)',
            'Click "Install to Workspace" at the top and allow',
            'Copy the "Bot User OAuth Token" (starts with xoxb-)',
            'Invite the app to every target channel: type /invite @app-name in the channel',
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
            'Erstes Feld: Workspace-Name zur Anzeige (optional)',
            'Zweites Feld: den Bot-Token einfügen',
            '"Zugangsdaten speichern" klicken — die erteilten Scopes übernimmt MySuricates aus der Antwort von auth.test',
          ],
          en: [
            'First field: workspace name for display (optional)',
            'Second field: paste the bot token',
            'Click "Save credentials" — MySuricates takes the granted scopes from the auth.test answer',
          ],
        },
      },
    ],
  },

  'telegram': {
    credentialLabels: {
      clientIdPlaceholder: { de: 'Bot-Name (optional)', en: 'Bot name (optional)' },
      clientSecretPlaceholder: { de: 'Bot-Token von @BotFather', en: 'Bot token from @BotFather' },
    },
    steps: [
      {
        title: {
          de: 'Bot bei @BotFather anlegen',
          en: 'Create a bot with @BotFather',
        },
        instructions: {
          de: [
            'In Telegram @BotFather öffnen (Link unten) und /newbot senden',
            'Einen Anzeigenamen und einen Benutzernamen wählen, der auf "bot" endet',
            'BotFather antwortet mit dem Token (Form 123456789:AA…) — wie ein Passwort behandeln',
          ],
          en: [
            'Open @BotFather in Telegram (link below) and send /newbot',
            'Choose a display name and a username ending in "bot"',
            'BotFather replies with the token (form 123456789:AA…) — treat it like a password',
          ],
        },
        link: {
          url: 'https://t.me/BotFather',
          label: { de: '@BotFather öffnen', en: 'Open @BotFather' },
        },
      },
      {
        title: {
          de: 'Zugangsdaten eintragen',
          en: 'Enter credentials',
        },
        instructions: {
          de: [
            'Erstes Feld: Bot-Name zur Anzeige (optional)',
            'Zweites Feld: den Bot-Token einfügen',
            '"Zugangsdaten speichern" klicken — geprüft wird per getMe',
          ],
          en: [
            'First field: bot name for display (optional)',
            'Second field: paste the bot token',
            'Click "Save credentials" — it is checked via getMe',
          ],
        },
      },
      {
        title: {
          de: 'Chat-ID herausfinden',
          en: 'Find the chat id',
        },
        instructions: {
          de: [
            'Dem Bot eine Nachricht schreiben oder ihn in die Gruppe bzw. den Kanal aufnehmen (in Kanälen als Admin)',
            'Die Aktion "get_updates" testen — die Chat-ID steht unter message.chat.id (Gruppen sind negativ)',
            'Öffentliche Kanäle gehen auch als @kanalname',
            'Hinweis: get_updates funktioniert nicht, solange für den Bot ein Webhook gesetzt ist',
          ],
          en: [
            'Send the bot a message or add it to the group or channel (as admin in channels)',
            'Test the "get_updates" action — the chat id is at message.chat.id (groups are negative)',
            'Public channels also work as @channelname',
            'Note: get_updates does not work while a webhook is set for the bot',
          ],
        },
      },
    ],
  },
};
