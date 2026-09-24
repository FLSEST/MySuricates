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
| `OAuthConfig` | `service/integration/` | Record: OAuth2-Endpunkte (inkl. optionalem Revoke-Endpoint), Scopes, Env-Var-Namen, Extra-Auth-Parameter |
| `IntegrationRegistry` | `service/integration/` | Sammelt alle Provider via Spring DI (`List<IntegrationProvider>` → `ConcurrentHashMap`) |
| `IntegrationOAuthService` | `service/integration/` | Generischer OAuth2-Flow: Auth-URL, Callback, Token-Refresh, verschlüsselte Speicherung |
| `OAuthTokenClient` | `service/integration/` | Ausgehendes HTTP des OAuth-Flows: Code-Tausch, Refresh, Revocation (RFC 7009) |
| `OAuthStateRegistry` | `service/integration/` | CSRF-`state` laufender Autorisierungen — TTL 10 Minuten, max. 1000 Einträge |
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
| `IntegrationVerification getVerification()` | ⬜ (aus `VerifiedProviders`) | Prüfstand: `VERIFIED` = gegen den echten Dienst geprüft, `SIMULATED` = nur gegen nachgebaute Antworten |
| `String getVerifiedAgainst()` | ⬜ (aus `VerifiedProviders`) | Wogegen ein `VERIFIED`-Provider geprüft wurde, z.B. „Gitea 1.24, lokaler Container“ |
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
    Map<String,String> extraAuthParams, // zusätzliche Authorize-Parameter (z.B. access_type=offline)
    String revocationUrl                // RFC-7009-Revoke-Endpoint (null = Provider hat keinen)
)
```

`revocationUrl` ist die 8. Komponente und optional — die kürzeren Konstruktoren setzen `null`.
Für Google gibt es die Konstante `OAuthConfig.GOOGLE_REVOCATION_URL` und den Wither
`withRevocationUrl(url)`, damit eine Provider-Config bei einem Konstruktor-Aufruf bleibt.

---

## Verfügbare Provider

| Provider | Provider-ID | Auth-Typ | Paket | Actions (Momentaufnahme) | Prüfstand |
|---|---|---|---|---|---|
| Google Calendar | `google-calendar` | OAuth2 (Google) | `providers/google/` | `list_events`, `get_event`, `create_event`, `update_event`, `delete_event`, `get_free_busy` | geprüft |
| Gmail | `google-gmail` | OAuth2 (Google, readonly) | `providers/google/` | `list_messages`, `get_message` | geprüft |
| Jira | `jira` | OAuth2 (Atlassian 3LO) | `providers/jira/` | `list_projects`, `search_issues`, `get_issue`, `create_issue`, `update_issue`, `add_comment`, `get_transitions`, `transition_issue` | geprüft |
| Confluence | `confluence` | OAuth2 (Atlassian 3LO) | `providers/confluence/` | `list_spaces`, `list_pages`, `get_page`, `search` | geprüft |
| Trello | `trello` | API-Key + Token (kein OAuth-Flow) | `providers/trello/` | `list_boards`, `get_board_lists`, `get_list_cards`, `get_card`, `create_card`, `update_card`, `delete_card`, `add_comment`, `search` | geprüft |
| Taskwarrior | `taskwarrior` | Lokales CLI (kein OAuth, optional TASKDATA/TASKRC) | `providers/taskwarrior/` | `list_tasks`, `get_task`, `add_task`, `complete_task`, `modify_task`, `annotate_task`, `delete_task`, `list_projects`, `sync_task` | geprüft |
| GitHub | `github` | Personal Access Token (kein OAuth-Flow) | `providers/github/` | `list_repos`, `list_issues`, `get_issue`, `create_issue`, `update_issue`, `add_comment` | geprüft |
| Gitea / Forgejo | `gitea` | Server-URL + Zugangstoken (kein OAuth-Flow) | `providers/gitea/` | `list_repos`, `list_issues`, `get_issue`, `create_issue`, `update_issue`, `add_comment` | geprüft (lokaler Container) |
| GitLab | `gitlab` | Server-URL (Standard `https://gitlab.com`) + Access Token (kein OAuth-Flow) | `providers/gitlab/` | `list_projects`, `list_issues`, `get_issue`, `create_issue`, `update_issue`, `add_comment` | simuliert |
| CalDAV | `caldav` | URL + Benutzer:App-Passwort (Basic Auth, kein OAuth-Flow) | `providers/caldav/` | `list_calendars`, `list_events`, `create_event`, `update_event`, `delete_event` | geprüft (Radicale 3.8.0, lokaler Container) |
| iCal-Feed | `ical-feed` | keine (öffentliche Feed-URL als Parameter) | `providers/icalfeed/` | `list_events` | geprüft (öffentlicher Google-Feiertagsfeed) |

**Prüfstand:** Jeder Provider sagt über `getVerification()`, ob er gegen den echten Dienst geprüft wurde. Standard ist `SIMULATED` — ein neuer Provider ist damit ohne Zutun als „nur simuliert getestet“ markiert; die Integrationen-Seite, der Setup-Bereich und der Block-Editor zeigen dann einen Hinweis. Auf `VERIFIED` wechselt ein Provider nur durch einen Eintrag in `service/integration/VerifiedProviders` (Provider-ID → wogegen geprüft) nach einem Lauf gegen den echten Dienst — ein lokaler Container der echten Software zählt. `IntegrationVerificationGateTest` findet alle Provider per Classpath-Scan und prüft, dass jeder Eintrag einen existierenden Provider nennt.

**Task-Export (TODO → Tracker, `TaskExportCapable`):** GitHub (Issue, Target `owner/repo`), Gitea/Forgejo (Issue,
Target `owner/repo`; Tags werden als Labels übernommen, soweit es sie im Repository gibt), GitLab (Issue,
Target = Projektpfad `gruppe/projekt` oder numerische ID; Tags werden Labels), Jira (Issue vom
Typ Task, Target = Project-Key, Labels ohne Leerzeichen; Browse-URL aus den accessible-resources) und Trello
(Karte, Target = Listen-ID, `listTargets` liefert „Board / Liste"; Tags werden nicht als Labels übertragen, weil
Trello Label-IDs erwartet). Erkennung per `instanceof`, Contract-Test `TaskExportContractTest`.

> **Taskwarrior ist ein Sonderfall:** kein HTTP-Dienst, sondern das lokale `task`-CLI. `executeAction()` ruft `task` per `ProcessBuilder` (Argument-Array, kein `bash -c`) auf dem Backend-Host auf — via `service/cli/TaskCommandService.java` + `util/HostProcessUtils.drain()`. Setzt voraus, dass `task` auf dem Host installiert ist; sonst bleibt die Verbindung „Setup required". Timeout runtime-konfigurierbar über `ExecutionSettingsService` (`task-command-timeout-seconds`). Sicherheit: Filter-Token mit `rc.`-Overrides oder Taskwarrior-Kommandos (z.B. `execute`) werden abgelehnt, IDs validiert, Freitext landet hinter `--`. Intern aufgeteilt: Action-Liste in `TaskwarriorActionCatalog`, Argument-Aufbau und Validierung in `TaskwarriorArgs`.

### CalDAV und iCal-Feed

Beide teilen sich einen kleinen iCalendar-Leser/-Schreiber in `providers/ical/` (keine Fremdbibliothek):
Zeilenfaltung (75 Oktette, UTF-8-sicher), Text-Escapes, `DTSTART`/`DTEND` als UTC, mit `TZID` oder ganztägig
(`VALUE=DATE`), `DURATION`, `SUMMARY`/`DESCRIPTION`/`LOCATION`/`UID`; `RRULE` wird nur durchgereicht.
Serien expandiert MySuricates **nicht** selbst. Zeitangaben kommen im Google-Format (`{date}` bzw.
`{dateTime, timeZone}`), damit Workflows beide Kalenderquellen gleich lesen.

- **CalDAV** (`caldav`, RFC 4791): Feld 1 = CalDAV-URL (Nextcloud `https://<server>/remote.php/dav`,
  Radicale `http://<server>:5232/`), Feld 2 = `benutzer:app-passwort`; gespeichert als `url|benutzer:passwort`,
  geprüft per PROPFIND (`fetchAccountInfo`). Discovery: `current-user-principal` → `calendar-home-set` →
  Depth-1-PROPFIND. `list_events` schickt einen `calendar-query`-REPORT mit `time-range` und `<C:expand>` —
  der Server liefert Serien als Einzeltermine (mit `recurrenceId`); ohne `calendar` werden alle Kalender
  abgefragt, ein kaputter Kalender landet unter `errors` statt die anderen zu verdecken. `create_event` legt
  `<uuid>.ics` per PUT mit `If-None-Match: *` an (VTIMEZONE aus den JDK-Regeln für jede verwendete TZID),
  `update_event` holt das Objekt, ändert nur die übergebenen Eigenschaften des Serien-Masters (Teilnehmer,
  Alarme, Ausnahmen bleiben), erhöht `SEQUENCE` und schreibt mit `If-Match` (ETag aus dem Parameter oder vom
  GET) — ein zwischenzeitlich geänderter Termin ergibt 412 statt Überschreiben. `delete_event` löscht bei
  Serien die ganze Serie. Sicherheit: nur http/https, keine Zugangsdaten in der URL, Weiterleitungen werden
  nicht verfolgt (sie würden den Basic-Auth-Header mitnehmen), jede angefragte URL muss auf dem Server der
  eingetragenen URL liegen (auch hrefs aus Server-Antworten), XML-Parser ohne DOCTYPE/externe Entities (XXE),
  Antworten höchstens 10 MB, Passwort nie in Log oder Fehlermeldung. Intern: `CalDavHttpClient`,
  `CalDavXml`, `CalDavDiscovery`, `CalDavEvents`, `CalDavActionCatalog`.
  **Prüfstand:** gegen Radicale 3.8.0 im lokalen Container (alle fünf Actions inkl. Serie mit `RRULE` und
  `expand`, ETag-Konflikt, fremde Origin). Nextcloud, Posteo und iCloud sind **nicht** live geprüft; iCloud
  verweist auf einen Partitions-Server (`pNN-caldav.icloud.com`), den der Provider wegen der Origin-Regel nicht
  selbst ansteuert — dort diese Adresse eintragen.
- **iCal-Feed** (`ical-feed`): keine Zugangsdaten (`requiresCredentials() == false`), die Feed-URL ist
  Parameter von `list_events` (`url`, `from`, `to`, `limit`, `timeZone`); `webcal://` wird als https gelesen.
  Download mit 10 s Verbindungs-/20 s Gesamt-Timeout, höchstens 5 MB, nur Antworten mit `BEGIN:VCALENDAR`.
  Geliefert werden Einzeltermine, die den Zeitraum überschneiden, und Serien-Master mit `rrule` und
  `recurring: true` (erstes Vorkommen als `start`; `UNTIL` in der Vergangenheit blendet die Serie aus,
  `EXDATE` bleibt unberücksichtigt) plus ein `note`-Hinweis. Fehler nennen nur den Host, nie Pfad oder Query —
  private Feed-Links enthalten oft ein Geheimnis.

Die Kalender-Ansicht zeigt CalDAV über `caldavAdapter` (alle Kalender, Serien bereits vom Server expandiert).
Ein iCal-Feed hat keinen Adapter, weil die Feed-URL erst im Workflow feststeht.

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
  (kein `Accept`-Header, kein 401-Sonderfall), Parameter-Parsing in `JiraParams`; der
  `cloudId`-Cache pro Access-Token bleibt im Provider.
- **Trello** nutzt keinen Token-Tausch. Client-ID = API-Key, Client-Secret = Token; beide
  werden als `apiKey|token` kombiniert gespeichert und in jeden Request als Query-Parameter
  (`key=…&token=…`) gehängt. Intern aufgeteilt: HTTP über `TrelloHttpClient`, Parameter-Parsing
  in `TrelloParams`, Action-Katalog in `TrelloActionCatalog`.
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
- **Gitea / Forgejo** (ein Provider für beide, gleiche API unter `/api/v1`) braucht keinen OAuth-Flow:
  Feld 1 ist die Server-URL (nur `http`/`https`, ohne Benutzerdaten, Query oder Fragment; ein
  abschließender Schrägstrich wird entfernt), Feld 2 das Zugangstoken. Beides wird als `url|token`
  gespeichert (`ServerTokenCredential`) und beim Speichern per `GET /api/v1/user` geprüft; Header
  `Authorization: token <t>`. Scopes lassen sich mit dem Token allein nicht auslesen (`scopes = null`).
  Gitea erwartet beim Anlegen und beim Ersetzen von Labels Label-IDs: `GiteaLabels` löst Namen gegen
  die Labels des Repositories auf (unbekannte Namen: Action schlägt vor dem Schreiben fehl, Task-Export
  lässt sie weg; Zahlen gelten als IDs, so auch Organisations-Labels). Labels ändert `update_issue`
  über `PUT …/issues/{n}/labels`, weil der Edit-Endpunkt kein Label-Feld hat. Geprüft gegen Gitea 1.27.3
  und Forgejo 14.0.5 im lokalen Container.
- **GitLab** (gitlab.com oder eigene Instanz, API v4) braucht keinen OAuth-Flow: Feld 1 ist die
  Server-URL (im Formular mit `https://gitlab.com` vorausgefüllt; leer gespeichert gilt ebenfalls
  gitlab.com), Feld 2 ein Personal-, Projekt- oder Gruppen-Access-Token; Header `PRIVATE-TOKEN`.
  Validierung per `GET /api/v4/user`, die Scopes liest `fetchGrantedScopes` aus
  `GET /api/v4/personal_access_tokens/self` (ab GitLab 15.5; sonst `null`). `create_issue`,
  `update_issue` und `add_comment` verlangen `api`, Lesen geht mit `read_api` oder `api`.
  Projekte werden als Pfad (URL-kodiert zu einem Segment, `gruppe%2Fprojekt`) oder numerische ID
  angesprochen, Issues über die projektlokale IID; Bearbeiter nur als numerische Benutzer-IDs
  (`assigneeIds`). `update_issue` mit `state` open/closed setzt `state_event` reopen/close.
  Nur simuliert getestet (Anfrageformen nach der offiziellen API-Doku).

---

## OAuth2-Flow (Authorization Code)

Implementiert in `IntegrationOAuthService`:

1. **Credentials hinterlegen** — `saveCredentials(providerId, clientId, clientSecret)`
   verschlüsselt Client-ID und -Secret und legt sie in `integration_connections` ab.
2. **Authorize-URL erzeugen** — `getAuthorizationUrl(providerId, redirectUri, additionalScopes)`
   - löst die Client-ID auf (DB zuerst, dann Env-Var),
   - erzeugt einen zufälligen `state` (32 Byte, `SecureRandom`) und merkt ihn im
     `OAuthStateRegistry` als CSRF-Schutz — **TTL 10 Minuten**, höchstens **1000** offene
     Autorisierungen (darüber `409 Too many pending OAuth authorizations — retry later`,
     bewusst kein Verdrängen des ältesten Eintrags),
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
   - `disconnect(providerId)` löscht alle Token-Felder und widerruft das Token danach beim
     Provider, **behält** aber die Client-Credentials → Reconnect ohne erneute Eingabe.
   - `deleteCredentials(providerId)` entfernt die komplette Zeile inkl. Client-Credentials und
     widerruft danach.
   - Beide sind bewusst **nicht** `@Transactional`: die Zeile wird in einem kurzen
     `TransactionTemplate`-Burst geschrieben, der Revoke läuft **danach** außerhalb jeder
     Transaktion. HTTP mit 10-s-Timeout in einer Transaktion würde bei HikariCP-Pool-Size 1
     die einzige Connection so lange pinnen; „persist first" verhindert außerdem den Zustand
     „beim Provider widerrufen, lokal aber noch `CONNECTED`".

> **Token-Revocation** läuft über `OAuthConfig.revocationUrl` (RFC 7009: `POST` mit
> `token=<access-token>` im Form-Body, nicht im Query-String). Google nutzt
> `https://oauth2.googleapis.com/revoke`; Atlassian 3LO (Jira, Confluence) veröffentlicht
> keinen Revoke-Endpunkt → `revocationUrl = null`, der Aufruf wird mit Info-Log übersprungen.
> Ein fehlgeschlagener Revoke bricht das Trennen nie ab; das lokale Token wird in jedem Fall
> gelöscht.

> **Fehlermeldungen** aus Code-Tausch und Refresh enthalten nur Provider-ID und HTTP-Status
> (z.B. „Token exchange failed for google-calendar (HTTP 400)"). Der Antwort-Body des Providers
> geht ausschließlich ins Log: `GlobalExceptionHandler` gibt die Exception-Message als `409` an
> den API-Client weiter, und Token-Endpunkte zitieren im Fehlerfall gern Teile des Requests
> (inklusive Client-Secret) zurück.

### Direkt-Auth-Flow (Provider ohne OAuth, z.B. Trello)

Für `requiresOAuthFlow() == false` sind die Credentials selbst die Authentifizierung:
`saveCredentials` kombiniert sie zu `clientId|clientSecret`, ruft `fetchAccountInfo()` zur
Validierung auf und setzt bei Erfolg direkt `CONNECTED`. Es gibt keinen Ablauf und kein
Refresh — `getValidAccessToken()` gibt das gespeicherte Credential unverändert zurück.
Für einen Provider mit `requiresCredentials() == false` (Taskwarrior und iCal-Feed) gibt
`getValidAccessToken()` `null` statt einer Exception zurück, wenn keine Connection-Zeile
existiert oder die Entschlüsselung fehlschlägt — Provider ohne Pflicht-Credentials dürfen
ohne Token laufen.

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
  caldavAdapter,          // Integration: caldav
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
