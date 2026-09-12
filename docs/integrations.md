# Integration / Plugin System

Plugin-Framework für externe Dienste (Google Calendar, Gmail, Jira, Confluence, Trello, …).
Jeder Provider ist ein Spring `@Component`, das `IntegrationProvider` implementiert und beim
Start automatisch entdeckt wird — analog zu den `BlockExecutor`-Implementierungen. Über den
Workflow-Block-Typ `integration` lassen sich Provider-Actions in jedem Workflow aufrufen;
über die Integrationen-Seite (`/integrations`) verbindet der User seine Accounts per OAuth2
oder API-Key.

> **Source of Truth für Actions:** Die exakte, aktuell verfügbare Action-Liste samt
> JSON-Schema je Provider liefert immer der Live-Endpoint `GET /api/v1/integrations/{providerId}/actions`.
> Die Action-Aufzählungen in diesem Dokument sind eine Momentaufnahme zur Orientierung.

---

## Architektur

| Komponente | Paket / Pfad | Aufgabe |
|---|---|---|
| `IntegrationProvider` | `service/integration/` | Interface: Provider-ID, OAuth-Config, Actions, `executeAction()` |
| `IntegrationAction` | `service/integration/` | Record: eine Action (Name, Beschreibung, Input-Schema, benötigte Scopes) |
| `OAuthConfig` | `service/integration/` | Record: OAuth2-Endpunkte, Scopes, Env-Var-Namen, Extra-Auth-Parameter |
| `IntegrationRegistry` | `service/integration/` | Sammelt alle Provider via Spring DI (`List<IntegrationProvider>` → `ConcurrentHashMap`) |
| `IntegrationOAuthService` | `service/integration/` | Generischer OAuth2-Flow: Auth-URL, Callback, Token-Refresh, verschlüsselte Speicherung |
| `IntegrationEncryptionService` | `service/integration/` | AES-256-GCM-Verschlüsselung für Tokens und Client-Credentials |
| `IntegrationBlockExecutor` | `executor/blocks/` | Block-Typ `integration` — dispatcht an Provider, löst Template-Variablen in Params auf |
| `IntegrationController` | `controller/` | REST-API unter `/api/v1/integrations` |
| `IntegrationConnection` | `entity/` | Entity: speichert Tokens + Client-Credentials verschlüsselt (Flyway V55 + V56) |
| `SETUP_GUIDES` | `frontend/src/data/integrationSetupGuides.ts` | Schritt-für-Schritt-Setup je Provider (DE/EN) für die UI |
| `CalendarSourceAdapter` | `frontend/src/hooks/calendar-adapters/` | Frontend-Adapter, der Provider-Events in die zentrale Kalender-Ansicht bringt |

Der Datenfluss bei einem Workflow-Aufruf:

```
Workflow-Block (integration)
  → IntegrationBlockExecutor.execute()
    → IntegrationRegistry.getProvider(providerId)
    → IntegrationOAuthService.getValidAccessToken(providerId)   // refresht ggf. das Token
    → Provider.executeAction(action, resolvedParams, accessToken)
      → HTTP-Call gegen die externe API
    → Ergebnis als hübsch formatiertes JSON in den StepResult.output
```

---

## Das `IntegrationProvider`-Interface

Ein Provider implementiert diesen Vertrag (`service/integration/IntegrationProvider.java`):

| Methode | Pflicht | Beschreibung |
|---|---|---|
| `String getProviderId()` | ✅ | Eindeutige ID, z.B. `"google-calendar"` — Schlüssel in Registry, Block-Config und REST-Pfaden |
| `String getDisplayName()` | ✅ | Anzeigename, z.B. `"Google Calendar"` |
| `String getIcon()` | ✅ | Lucide-Icon-Name für die UI, z.B. `"Calendar"` |
| `String getDescription()` | ✅ | Kurzbeschreibung |
| `OAuthConfig getOAuthConfig()` | ✅ | OAuth2-/Credential-Konfiguration |
| `List<IntegrationAction> getAvailableActions()` | ✅ | Unterstützte Actions inkl. Input-Schema |
| `Object executeAction(String action, Map<String,Object> params, String accessToken)` | ✅ | Führt eine Action gegen die externe API aus |
| `String fetchAccountInfo(String accessToken)` | ⬜ (default `null`) | Account-Info nach erfolgreicher Authentifizierung (JSON-String) |
| `boolean requiresOAuthFlow()` | ⬜ (default `true`) | `false` = Direkt-Auth per API-Key/Token statt OAuth2-Authorization-Code-Flow |

### `IntegrationAction` (Record)

```java
record IntegrationAction(
    String name,                   // Action-ID, z.B. "create_event"
    String description,            // menschenlesbare Beschreibung
    Map<String,Object> inputSchema,// JSON-Schema (type/properties/required) der Parameter
    List<String> requiredScopes    // optional, default List.of()
)
```

Das `inputSchema` ist ein JSON-Schema-Fragment (`type: object`, `properties`, `required`),
das die UI rendert und das die akzeptierten `params` dokumentiert.

### `OAuthConfig` (Record)

```java
record OAuthConfig(
    String authorizationUrl,            // OAuth2 Authorize-Endpoint
    String tokenUrl,                    // OAuth2 Token-Endpoint
    List<String> defaultScopes,         // immer angefragte Scopes
    List<String> optionalScopes,        // nur auf Anfrage hinzufügbar (default List.of())
    String clientIdEnvVar,              // Env-Var-Fallback für die Client-ID
    String clientSecretEnvVar,          // Env-Var-Fallback für das Client-Secret
    Map<String,String> extraAuthParams  // zusätzliche Authorize-Parameter (z.B. access_type=offline)
)
```

---

## Verfügbare Provider

| Provider | Provider-ID | Auth-Typ | Paket | Actions (Momentaufnahme) |
|---|---|---|---|---|
| Google Calendar | `google-calendar` | OAuth2 (Google) | `providers/google/` | `list_events`, `get_event`, `create_event`, `update_event`, `delete_event`, `get_free_busy` |
| Gmail | `google-gmail` | OAuth2 (Google, readonly) | `providers/google/` | `list_messages`, `get_message` |
| Jira | `jira` | OAuth2 (Atlassian 3LO) | `providers/jira/` | `list_projects`, `search_issues`, `get_issue`, `create_issue`, `update_issue`, `add_comment`, `get_transitions`, `transition_issue` |
| Confluence | `confluence` | OAuth2 (Atlassian 3LO) | `providers/confluence/` | `list_spaces`, `list_pages`, `get_page`, `search` |
| Trello | `trello` | API-Key + Token (kein OAuth-Flow) | `providers/trello/` | `list_boards`, `get_board_lists`, `get_list_cards`, `get_card`, `create_card`, `update_card`, `delete_card`, `add_comment`, `search` |
| Taskwarrior | `taskwarrior` | Lokales CLI (kein OAuth, optional TASKDATA/TASKRC) | `providers/taskwarrior/` | `list_tasks`, `get_task`, `add_task`, `complete_task`, `modify_task`, `annotate_task`, `delete_task`, `list_projects`, `sync_task` |

> **Taskwarrior ist ein Sonderfall:** kein HTTP-Dienst, sondern das lokale `task`-CLI. `executeAction()` ruft `task` per `ProcessBuilder` (Argument-Array, kein `bash -c`) auf dem Backend-Host auf — via `service/cli/TaskCommandService.java` + `util/HostProcessUtils.drain()`. Setzt voraus, dass `task` auf dem Host installiert ist; sonst bleibt die Verbindung „Setup required". Timeout runtime-konfigurierbar über `ExecutionSettingsService` (`task-command-timeout-seconds`). Sicherheit: Filter-Token mit `rc.`-Overrides oder Taskwarrior-Kommandos (z.B. `execute`) werden abgelehnt, IDs validiert, Freitext landet hinter `--`.

### OAuth-Endpunkte & Scopes je Provider

| Provider | Authorize-URL | Token-URL | Default-Scopes | Optionale Scopes | Client-Env-Vars |
|---|---|---|---|---|---|
| Google Calendar | `accounts.google.com/o/oauth2/v2/auth` | `oauth2.googleapis.com/token` | `calendar.events`, `userinfo.email`, `userinfo.profile` | – | `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` |
| Gmail | `accounts.google.com/o/oauth2/v2/auth` | `oauth2.googleapis.com/token` | `gmail.readonly` | – | `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` |
| Jira | `auth.atlassian.com/authorize` | `auth.atlassian.com/oauth/token` | `read:jira-work`, `read:jira-user`, `offline_access` | `write:jira-work` | `JIRA_CLIENT_ID` / `JIRA_CLIENT_SECRET` |
| Confluence | `auth.atlassian.com/authorize` | `auth.atlassian.com/oauth/token` | `read:confluence-content.all`, `read:confluence-space.summary`, `search:confluence`, `offline_access` | – | `CONFLUENCE_CLIENT_ID` / `CONFLUENCE_CLIENT_SECRET` |
| Trello | `trello.com/1/authorize` | – (nicht genutzt) | `read`, `write` | – | `TRELLO_API_KEY` / `TRELLO_TOKEN` |

Hinweise:
- **Google** setzt `extraAuthParams` `access_type=offline` + `prompt=consent`, damit ein
  Refresh-Token ausgeliefert wird.
- **Atlassian** (Jira/Confluence) setzt `audience=api.atlassian.com` + `prompt=consent` und
  nutzt den 3LO-Flow; nach dem Token-Tausch wird die `cloudId` aufgelöst, um die Base-URL zu
  bilden. `write:jira-work` ist bei Jira ein optionaler Scope (nur nötig zum Schreiben).
  Intern aufgeteilt: Actions-Katalog in `JiraActionCatalog`, HTTP über `JiraHttpClient`
  (kein `Accept`-Header, kein 401-Sonderfall), Parameter-Parsing in `JiraParams`; die
  `cloudId`-Cache pro Access-Token bleibt im Provider.
- **Trello** nutzt keinen Token-Tausch. Client-ID = API-Key, Client-Secret = Token; beide
  werden als `apiKey|token` kombiniert gespeichert und in jeden Request als Query-Parameter
  (`key=…&token=…`) gehängt.
- **GitHub (PAT)** braucht keinen OAuth-Flow: der Token wird beim Speichern per `GET /user`
  validiert und seine Scopes aus dem Antwort-Header `X-OAuth-Scopes` an der Verbindung
  gespeichert (`fetchGrantedScopes`, klassische Tokens). Fine-grained PATs liefern keinen
  Scope-Header — die Verbindung hat dann `scopes = null` (unbekannt), was **nicht** als
  „keine Scopes" gilt. Die schreibenden Actions `create_issue`, `update_issue`, `add_comment`
  verlangen `repo`; fehlt er bei bekannten Scopes, zeigt die UI die Warnung „Berechtigung fehlt"
  (nur Hinweis, die Ausführung wird nicht blockiert). Least-Privilege: fine-grained PAT nur für
  die benötigten Repositories mit *Issues: Read and write* anlegen statt eines klassischen
  Tokens mit vollem `repo`. Intern aufgeteilt: HTTP über `GitHubHttpClient`, Parameter-Parsing
  in `GitHubParams`.

---

## OAuth2-Flow (Authorization Code)

Implementiert in `IntegrationOAuthService`:

1. **Credentials hinterlegen** — `saveCredentials(providerId, clientId, clientSecret)`
   verschlüsselt Client-ID und -Secret und legt sie in `integration_connections` ab.
2. **Authorize-URL erzeugen** — `getAuthorizationUrl(providerId, redirectUri, additionalScopes)`
   - löst die Client-ID auf (DB zuerst, dann Env-Var),
   - erzeugt einen zufälligen `state` (32 Byte, `SecureRandom`) und merkt ihn als CSRF-Schutz,
   - hängt `defaultScopes` + erlaubte `optionalScopes` sowie alle `extraAuthParams` an.
3. **User stimmt zu** und wird zur Redirect-URI zurückgeleitet.
4. **Callback verarbeiten** — `handleCallback(code, state, redirectUri)`
   - validiert den `state` (sonst „Invalid or expired OAuth state"),
   - tauscht den `code` per `grant_type=authorization_code` gegen Tokens,
   - speichert Access-Token, Refresh-Token, Ablaufzeitpunkt, gewährte Scopes und Account-Info
     (alle Token-Felder verschlüsselt) und setzt den Status auf `CONNECTED`.
5. **Token nutzen** — `getValidAccessToken(providerId)` liefert ein gültiges Access-Token und
   refresht automatisch, wenn es in **weniger als 300 Sekunden** abläuft
   (`grant_type=refresh_token`). Fehlt das Refresh-Token, wird der Status auf `EXPIRED` gesetzt.
6. **Trennen** —
   - `disconnect(providerId)` widerruft das Token (bei OAuth-Providern) und löscht alle
     Token-Felder, **behält** aber die Client-Credentials → Reconnect ohne erneute Eingabe.
   - `deleteCredentials(providerId)` entfernt die komplette Zeile inkl. Client-Credentials.

> **Token-Revocation** ist aktuell auf den Google-Revoke-Endpoint
> (`oauth2.googleapis.com/revoke`) verdrahtet; für andere OAuth-Provider ist das Widerrufen
> daher ein Best-Effort-No-Op, das lokale Token wird aber in jedem Fall gelöscht.

### Direkt-Auth-Flow (Provider ohne OAuth, z.B. Trello)

Für `requiresOAuthFlow() == false` sind die Credentials selbst die Authentifizierung:
`saveCredentials` kombiniert sie zu `clientId|clientSecret`, ruft `fetchAccountInfo()` zur
Validierung auf und setzt bei Erfolg direkt `CONNECTED`. Es gibt keinen Ablauf und kein
Refresh — `getValidAccessToken()` gibt das gespeicherte Credential unverändert zurück.

---

## Credential-Speicherung & Verschlüsselung

- Tokens und Client-Credentials liegen in `integration_connections` (`IntegrationConnection`),
  Status-Enum `ConnectionStatus`: `CONNECTED`, `DISCONNECTED`, `EXPIRED`.
- Verschlüsselung: **AES/GCM/NoPadding**, 128-Bit-Auth-Tag, 12-Byte-IV
  (`IntegrationEncryptionService`).
- Der Schlüssel kommt aus der Env-Var `AUTOMATION_INTEGRATION_KEY` (Base64-AES-Key) oder aus der
  Key-Datei (`automation.integration.key-file`, Default `~/.autotrigger/integration-encryption.key`,
  chmod 600); fehlt beides, wird er beim ersten Start generiert und in die Key-Datei geschrieben.
  Ein Legacy-Schlüssel in `app_settings` (`integration-encryption-key`) wird beim Start automatisch
  in die Key-Datei migriert und aus der DB gelöscht — Schlüssel und Ciphertext liegen damit nicht
  mehr in derselben SQLite-Datei. Nur wenn die Key-Datei nicht schreibbar ist (z. B. Container mit
  read-only Home), fällt der Service mit lautem ERROR-Log auf die DB zurück.
- **Auflösungsreihenfolge** für Client-ID/-Secret: zuerst DB (verschlüsselt), dann Env-Var-Fallback
  (`GOOGLE_CLIENT_ID` etc.) — praktisch für Deployment-Szenarien ohne UI-Eingabe.
- **GitHub-Repo-Allowlist**: App-Setting `github.repo-allowlist` (kommaseparierte `owner/repo`-Einträge,
  `owner/*` erlaubt einen ganzen Owner; Groß-/Kleinschreibung egal). Jede repo-bezogene GitHub-Action
  und der Task-Export validieren dagegen — ein breiter PAT kann damit nicht mehr in fremde Repos
  schreiben. Leer/unset = Legacy-Verhalten (alles erlaubt) mit WARN-Log. Setzen via
  `PUT /api/v1/system/settings/github.repo-allowlist`.

---

## Integration-Block (Workflow)

Block-Typ `integration`. Pflichtfelder in `config`: `provider` und `action`. Optionale
`params` werden vor der Ausführung durch den `ExecutionContext` aufgelöst — String-Werte
durchlaufen `resolveTemplate(...)`, sodass `{{ variable }}`-Platzhalter funktionieren.

```json
{
  "type": "integration",
  "config": {
    "provider": "google-calendar",
    "action": "create_event",
    "params": {
      "summary": "{{ workflow_name }} abgeschlossen",
      "startDateTime": "{{ started_at }}",
      "endDateTime": "{{ started_at }}",
      "timeZone": "Europe/Berlin"
    }
  }
}
```

Verhalten:
- Das Ergebnis der Action wird als **hübsch formatiertes JSON** in `StepResult.output`
  geschrieben — nachfolgende Blöcke können es als Kontext referenzieren.
- `StepResult.metadata` enthält `provider`, `action` und `executionMode: "integration"`.
- Fehlt `provider`/`action` oder schlägt der API-Call fehl, endet der Step mit `FAILED` und
  der Fehlermeldung in `output` + `metadata.error` (der Workflow bricht nicht hart ab,
  sondern liefert einen fehlgeschlagenen Step).
- Nur String-Params werden getemplatet; Zahlen/Booleans/Arrays werden unverändert übergeben.

---

## REST-API (`/api/v1/integrations`)

| Methode | Pfad | Zweck |
|---|---|---|
| `GET` | `/` | Alle Provider inkl. Connection-Status auflisten |
| `GET` | `/{providerId}` | Einen Provider abrufen |
| `GET` | `/{providerId}/connect` | OAuth2-Authorize-URL erzeugen (Query: `redirectUri`, optionale `scopes`) |
| `GET` | `/{providerId}/callback` | OAuth2-Callback — **public, kein API-Key nötig** |
| `POST` | `/{providerId}/credentials` | Client-ID + Secret speichern (verschlüsselt) |
| `DELETE` | `/{providerId}/connection` | Trennen — Tokens löschen, Credentials behalten |
| `DELETE` | `/{providerId}/credentials` | Verbindung samt Credentials vollständig löschen |
| `GET` | `/{providerId}/actions` | Verfügbare Actions inkl. Input-Schema (**Source of Truth**) |
| `POST` | `/{providerId}/actions/{action}` | Eine Action testweise ausführen |

---

## Neuen Provider hinzufügen (Schritt für Schritt)

1. **Provider-Klasse anlegen** in `service/integration/providers/<name>/<Name>Provider.java`,
   `IntegrationProvider` implementieren und mit `@Component` annotieren → wird automatisch von
   der `IntegrationRegistry` registriert.
2. **Metadaten** liefern: `getProviderId()` (eindeutig, kebab-case), `getDisplayName()`,
   `getIcon()` (Lucide-Name), `getDescription()`.
3. **`getOAuthConfig()`** zurückgeben:
   - OAuth2-Provider: Authorize-/Token-URL, Default-/optionale Scopes, Env-Var-Namen, ggf.
     `extraAuthParams` (z.B. `access_type=offline` für ein Refresh-Token).
   - Direkt-Auth-Provider: zusätzlich `requiresOAuthFlow()` auf `false` setzen; die beiden
     Credential-Felder als Key/Token interpretieren.
4. **`getAvailableActions()`** definieren — pro Action Name, Beschreibung und ein
   JSON-Schema (`type: object`, `properties`, `required`).
5. **`executeAction(action, params, accessToken)`** implementieren — typischerweise ein
   `switch` über die Action-Namen, das die jeweilige private Methode aufruft und gegen die
   externe REST-API geht (`java.net.http.HttpClient`). Parameter defensiv über Helfer wie
   `requireParam` / `paramString` / `paramInt` lesen.
6. **Optional `fetchAccountInfo(accessToken)`** überschreiben, um nach dem Connect den
   Account (Name/E-Mail) anzuzeigen — bei Direkt-Auth-Providern dient der Aufruf zugleich der
   Credential-Validierung.
7. **Setup-Guide** in `frontend/src/data/integrationSetupGuides.ts` ergänzen
   (`SETUP_GUIDES['<provider-id>']`) — Schritte zweisprachig (DE/EN), optional Links und
   Credential-Platzhalter.
8. **Optional Kalender-Adapter** in `frontend/src/hooks/calendar-adapters/` hinzufügen, wenn
   der Provider Events liefert, die in der zentralen Kalender-Ansicht erscheinen sollen.
9. **Tests** schreiben (Action-Dispatch, Param-Validierung, Fehlerpfade) und das Frontend
   auf `localhost:3000` verifizieren.

Es ist **keine** Flyway-Migration oder Registry-Eintrag nötig — die Auto-Discovery über
Spring-DI genügt. Bestehende Connections nutzen weiterhin dieselbe `integration_connections`-Tabelle.

---

## Kalender-Adapter (Frontend)

Adapter-Pattern für die zentrale Kalender-Seite (`/calendar`). Jeder Adapter holt Events aus
einer Quelle und transformiert sie in `UnifiedCalendarEvent[]`. Die Aggregation übernimmt der
Hook `useCalendarAggregated`.

### `CalendarSourceAdapter`-Interface (`hooks/calendar-adapters/types.ts`)

| Feld | Typ | Beschreibung |
|---|---|---|
| `sourceId` | `string` | Eindeutige ID, z.B. `'google-calendar'`, `'schedules'`, `'runs'` |
| `label` | `string` | Anzeigename im Quellen-Filter |
| `color` | `string` | Tailwind-Background-Klasse für Kalender-Punkte/Event-Streifen |
| `isAvailable` | `(integrations) => boolean` | Quelle aktiv? System-Quellen `true`; Integrationen prüfen Status `CONNECTED` |
| `fetchEvents` | `(from, to) => Promise<UnifiedCalendarEvent[]>` | Events für Zeitraum laden + transformieren (als `queryFn` genutzt) |

### Registry (`hooks/calendar-adapters/registry.ts`)

```ts
export const CALENDAR_ADAPTERS: CalendarSourceAdapter[] = [
  googleCalendarAdapter,  // Integration: google-calendar
  trelloAdapter,          // Integration: trello
  schedulesAdapter,       // System-Quelle: geplante Workflows
  runsAdapter,            // System-Quelle: vergangene Runs
];
```

> **Wichtig:** Das Array muss **statisch** sein. `useCalendarAggregated` ruft pro Adapter ein
> eigenes `useQuery` auf — die React Rules of Hooks verlangen eine konstante Anzahl Hook-Calls
> pro Render. `isAvailable` steuert das `enabled`-Flag, **nicht** ob der Hook aufgerufen wird.

### Neuen Kalender-Adapter hinzufügen

1. Neue Datei in `hooks/calendar-adapters/` anlegen, die `CalendarSourceAdapter` implementiert.
2. Import + eine Zeile im `CALENDAR_ADAPTERS`-Array in `registry.ts` ergänzen.
3. Die Kalender-Seite zeigt die neue Quelle automatisch mit eigenem Filter-Toggle.

---

## Setup-Guides (Frontend)

`frontend/src/data/integrationSetupGuides.ts` liefert die UI-Anleitung pro Provider:

```ts
export const SETUP_GUIDES: Record<string, SetupGuide> = {
  'google-calendar': { steps: [ /* SetupStep[] */ ], credentialLabels: { /* … */ } },
  // …
};
```

- `SetupGuide` = `{ steps: SetupStep[]; credentialLabels?: CredentialLabels }`
- `SetupStep` = `{ title: {de,en}; instructions: {de:string[], en:string[]}; link?; code? }`
- `credentialLabels` passt die Platzhaltertexte der Client-ID-/Secret-Felder an (z.B. „API-Key"
  statt „Client ID" bei Trello).
- `backendBaseUrl()` ermittelt die Basis-URL für die Redirect-URI (berücksichtigt Dev-Port 3000
  → 9090, Test → 8090 sowie `VITE_API_BASE_URL`).

---

## Verweise

- Datenmodell der `integration_connections`-Tabelle: [datenmodell.md](datenmodell.md)
- Block-Typen und Workflow-Aufbau: [WORKFLOW-GUIDE.md](WORKFLOW-GUIDE.md)
- Kurzfassung in den Projekt-Instruktionen: [../CLAUDE.md](../CLAUDE.md) (Abschnitt „Integration / Plugin System")
