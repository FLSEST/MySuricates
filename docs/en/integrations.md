<!-- last-sync: 2026-06-29 from: docs/integrations.md -->

# Integration / Plugin System

Plugin framework for external services (Google Calendar, Gmail, Jira, Confluence, Trello, …).
Each provider is a Spring `@Component` that implements `IntegrationProvider` and is
automatically discovered at startup — analogous to the `BlockExecutor` implementations. Via the
workflow block type `integration`, provider actions can be called in any workflow; on the
integrations page (`/integrations`) users connect their accounts via OAuth2 or API key.

> **Source of truth for actions:** The exact, currently available action list including the
> JSON schema per provider is always delivered by the live endpoint `GET /api/v1/integrations/{providerId}/actions`.
> The action listings in this document are a snapshot for orientation purposes.

---

## Architecture

| Component | Package / Path | Responsibility |
|---|---|---|
| `IntegrationProvider` | `service/integration/` | Interface: provider ID, OAuth config, actions, `executeAction()` |
| `IntegrationAction` | `service/integration/` | Record: one action (name, description, input schema, required scopes) |
| `OAuthConfig` | `service/integration/` | Record: OAuth2 endpoints, scopes, env var names, extra auth parameters |
| `IntegrationRegistry` | `service/integration/` | Collects all providers via Spring DI (`List<IntegrationProvider>` → `ConcurrentHashMap`) |
| `IntegrationOAuthService` | `service/integration/` | Generic OAuth2 flow: auth URL, callback, token refresh, encrypted storage |
| `IntegrationEncryptionService` | `service/integration/` | AES-256-GCM encryption for tokens and client credentials |
| `IntegrationBlockExecutor` | `executor/blocks/` | Block type `integration` — dispatches to provider, resolves template variables in params |
| `IntegrationController` | `controller/` | REST API under `/api/v1/integrations` |
| `IntegrationConnection` | `entity/` | Entity: stores tokens + client credentials encrypted (Flyway V55 + V56) |
| `SETUP_GUIDES` | `frontend/src/data/integrationSetupGuides.ts` | Step-by-step setup per provider (DE/EN) for the UI |
| `CalendarSourceAdapter` | `frontend/src/hooks/calendar-adapters/` | Frontend adapter that brings provider events into the central calendar view |

Data flow during a workflow call:

```
Workflow block (integration)
  → IntegrationBlockExecutor.execute()
    → IntegrationRegistry.getProvider(providerId)
    → IntegrationOAuthService.getValidAccessToken(providerId)   // refreshes the token if necessary
    → Provider.executeAction(action, resolvedParams, accessToken)
      → HTTP call against the external API
    → Result as nicely formatted JSON in StepResult.output
```

---

## The `IntegrationProvider` Interface

A provider implements this contract (`service/integration/IntegrationProvider.java`):

| Method | Required | Description |
|---|---|---|
| `String getProviderId()` | ✅ | Unique ID, e.g. `"google-calendar"` — key in registry, block config, and REST paths |
| `String getDisplayName()` | ✅ | Display name, e.g. `"Google Calendar"` |
| `String getIcon()` | ✅ | Lucide icon name for the UI, e.g. `"Calendar"` |
| `String getDescription()` | ✅ | Short description |
| `OAuthConfig getOAuthConfig()` | ✅ | OAuth2/credential configuration |
| `List<IntegrationAction> getAvailableActions()` | ✅ | Supported actions including input schema |
| `Object executeAction(String action, Map<String,Object> params, String accessToken)` | ✅ | Executes an action against the external API |
| `String fetchAccountInfo(String accessToken)` | ⬜ (default `null`) | Account info after successful authentication (JSON string) |
| `boolean requiresOAuthFlow()` | ⬜ (default `true`) | `false` = direct auth via API key/token instead of OAuth2 authorization code flow |

### `IntegrationAction` (Record)

```java
record IntegrationAction(
    String name,                   // action ID, e.g. "create_event"
    String description,            // human-readable description
    Map<String,Object> inputSchema,// JSON schema (type/properties/required) of parameters
    List<String> requiredScopes    // optional, default List.of()
)
```

The `inputSchema` is a JSON schema fragment (`type: object`, `properties`, `required`),
which the UI renders and which documents the accepted `params`.

### `OAuthConfig` (Record)

```java
record OAuthConfig(
    String authorizationUrl,            // OAuth2 authorize endpoint
    String tokenUrl,                    // OAuth2 token endpoint
    List<String> defaultScopes,         // always requested scopes
    List<String> optionalScopes,        // only added on request (default List.of())
    String clientIdEnvVar,              // env var fallback for the client ID
    String clientSecretEnvVar,          // env var fallback for the client secret
    Map<String,String> extraAuthParams  // additional authorize parameters (e.g. access_type=offline)
)
```

---

## Available Providers

| Provider | Provider ID | Auth Type | Package | Actions (snapshot) |
|---|---|---|---|---|
| Google Calendar | `google-calendar` | OAuth2 (Google) | `providers/google/` | `list_events`, `get_event`, `create_event`, `update_event`, `delete_event`, `get_free_busy` |
| Gmail | `google-gmail` | OAuth2 (Google, readonly) | `providers/google/` | `list_messages`, `get_message` |
| Jira | `jira` | OAuth2 (Atlassian 3LO) | `providers/jira/` | `list_projects`, `search_issues`, `get_issue`, `create_issue`, `update_issue`, `add_comment`, `get_transitions`, `transition_issue` |
| Confluence | `confluence` | OAuth2 (Atlassian 3LO) | `providers/confluence/` | `list_spaces`, `list_pages`, `get_page`, `search` |
| Trello | `trello` | API key + token (no OAuth flow) | `providers/trello/` | `list_boards`, `get_board_lists`, `get_list_cards`, `get_card`, `create_card`, `update_card`, `delete_card`, `add_comment`, `search` |
| Taskwarrior | `taskwarrior` | Local CLI (no OAuth, optional TASKDATA/TASKRC) | `providers/taskwarrior/` | `list_tasks`, `get_task`, `add_task`, `complete_task`, `modify_task`, `annotate_task`, `delete_task`, `list_projects`, `sync_task` |

> **Taskwarrior is a special case:** not an HTTP service, but the local `task` CLI. `executeAction()` calls `task` via `ProcessBuilder` (argument array, no `bash -c`) on the backend host — via `service/cli/TaskCommandService.java` + `util/HostProcessUtils.drain()`. Requires `task` to be installed on the host; otherwise the connection remains "Setup required". Timeout is runtime-configurable via `ExecutionSettingsService` (`task-command-timeout-seconds`). Security: filter tokens with `rc.` overrides or Taskwarrior commands (e.g. `execute`) are rejected, IDs are validated, free text is placed after `--`.

### OAuth Endpoints & Scopes per Provider

| Provider | Authorize URL | Token URL | Default Scopes | Optional Scopes | Client Env Vars |
|---|---|---|---|---|---|
| Google Calendar | `accounts.google.com/o/oauth2/v2/auth` | `oauth2.googleapis.com/token` | `calendar.events`, `userinfo.email`, `userinfo.profile` | – | `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` |
| Gmail | `accounts.google.com/o/oauth2/v2/auth` | `oauth2.googleapis.com/token` | `gmail.readonly` | – | `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` |
| Jira | `auth.atlassian.com/authorize` | `auth.atlassian.com/oauth/token` | `read:jira-work`, `read:jira-user`, `offline_access` | `write:jira-work` | `JIRA_CLIENT_ID` / `JIRA_CLIENT_SECRET` |
| Confluence | `auth.atlassian.com/authorize` | `auth.atlassian.com/oauth/token` | `read:confluence-content.all`, `read:confluence-space.summary`, `search:confluence`, `offline_access` | – | `CONFLUENCE_CLIENT_ID` / `CONFLUENCE_CLIENT_SECRET` |
| Trello | `trello.com/1/authorize` | – (not used) | `read`, `write` | – | `TRELLO_API_KEY` / `TRELLO_TOKEN` |

Notes:
- **Google** sets `extraAuthParams` `access_type=offline` + `prompt=consent` so that a
  refresh token is delivered.
- **Atlassian** (Jira/Confluence) sets `audience=api.atlassian.com` + `prompt=consent` and
  uses the 3LO flow; after the token exchange the `cloudId` is resolved to build the base URL.
  `write:jira-work` is an optional scope for Jira (only needed for writes).
- **Trello** does not use a token exchange. Client ID = API key, client secret = token; both
  are stored combined as `apiKey|token` and appended to every request as query parameters
  (`key=…&token=…`).
- **GitHub (PAT)** needs no OAuth flow: the token is validated via `GET /user` when saved and
  its scopes are taken from the `X-OAuth-Scopes` response header and stored on the connection
  (`fetchGrantedScopes`, classic tokens). Fine-grained PATs send no scope header — the
  connection then has `scopes = null` (unknown), which is **not** treated as "no scopes".
  The writing actions `create_issue`, `update_issue`, `add_comment` require `repo`; if it is
  missing from known scopes the UI shows the "permission missing" warning (advisory only,
  execution is not blocked). Least privilege: create a fine-grained PAT limited to the needed
  repositories with *Issues: Read and write* instead of a classic token with full `repo`.
  Split internally: HTTP via `GitHubHttpClient`, parameter parsing in `GitHubParams`.

---

## OAuth2 Flow (Authorization Code)

Implemented in `IntegrationOAuthService`:

1. **Store credentials** — `saveCredentials(providerId, clientId, clientSecret)`
   encrypts client ID and secret and stores them in `integration_connections`.
2. **Generate authorize URL** — `getAuthorizationUrl(providerId, redirectUri, additionalScopes)`
   - resolves the client ID (DB first, then env var),
   - generates a random `state` (32 bytes, `SecureRandom`) and stores it as CSRF protection,
   - appends `defaultScopes` + permitted `optionalScopes` as well as all `extraAuthParams`.
3. **User consents** and is redirected back to the redirect URI.
4. **Process callback** — `handleCallback(code, state, redirectUri)`
   - validates the `state` (otherwise "Invalid or expired OAuth state"),
   - exchanges the `code` for tokens via `grant_type=authorization_code`,
   - stores access token, refresh token, expiry time, granted scopes, and account info
     (all token fields encrypted) and sets the status to `CONNECTED`.
5. **Use token** — `getValidAccessToken(providerId)` returns a valid access token and
   automatically refreshes it when it expires in **less than 300 seconds**
   (`grant_type=refresh_token`). If the refresh token is missing, the status is set to `EXPIRED`.
6. **Disconnect** —
   - `disconnect(providerId)` revokes the token (for OAuth providers) and deletes all
     token fields, but **retains** client credentials → reconnect without re-entering them.
   - `deleteCredentials(providerId)` removes the entire row including client credentials.

> **Token revocation** is currently wired to the Google revoke endpoint
> (`oauth2.googleapis.com/revoke`); for other OAuth providers, revocation is therefore a best-effort no-op,
> but the local token is always deleted regardless.

### Direct Auth Flow (providers without OAuth, e.g. Trello)

For `requiresOAuthFlow() == false` the credentials themselves are the authentication:
`saveCredentials` combines them as `clientId|clientSecret`, calls `fetchAccountInfo()` for
validation, and on success sets the status directly to `CONNECTED`. There is no expiry and no
refresh — `getValidAccessToken()` returns the stored credential unchanged.

---

## Credential Storage & Encryption

- Tokens and client credentials are stored in `integration_connections` (`IntegrationConnection`),
  status enum `ConnectionStatus`: `CONNECTED`, `DISCONNECTED`, `EXPIRED`.
- Encryption: **AES/GCM/NoPadding**, 128-bit auth tag, 12-byte IV
  (`IntegrationEncryptionService`).
- The key comes from the env var `AUTOMATION_INTEGRATION_KEY` (Base64-AES key) or from the
  key file (`automation.integration.key-file`, default `~/.autotrigger/integration-encryption.key`,
  chmod 600); if neither exists, it is generated on first startup and written to the key file.
  A legacy key in `app_settings` (`integration-encryption-key`) is automatically migrated to the
  key file on startup and deleted from the DB — key and ciphertext no longer live in the same
  SQLite file. Only if the key file is not writable (e.g. container with read-only home) does the
  service fall back to the DB with a loud ERROR log.
- **Resolution order** for client ID/secret: DB first (encrypted), then env var fallback
  (`GOOGLE_CLIENT_ID` etc.) — practical for deployment scenarios without UI input.
- **GitHub repo allowlist**: app setting `github.repo-allowlist` (comma-separated `owner/repo`
  entries, `owner/*` allows a whole owner; case-insensitive). Every repo-scoped GitHub action and
  the task export validate against it — a broad PAT can no longer write to foreign repos.
  Empty/unset = legacy behavior (allow all) with a WARN log. Set via
  `PUT /api/v1/system/settings/github.repo-allowlist`.

---

## Integration Block (Workflow)

Block type `integration`. Required fields in `config`: `provider` and `action`. Optional
`params` are resolved via `ExecutionContext` before execution — string values are passed through
`resolveTemplate(...)` so that `{{ variable }}` placeholders work.

```json
{
  "type": "integration",
  "config": {
    "provider": "google-calendar",
    "action": "create_event",
    "params": {
      "summary": "{{ workflow_name }} completed",
      "startDateTime": "{{ started_at }}",
      "endDateTime": "{{ started_at }}",
      "timeZone": "Europe/Berlin"
    }
  }
}
```

Behavior:
- The result of the action is written as **nicely formatted JSON** into `StepResult.output`
  — subsequent blocks can reference it as context.
- `StepResult.metadata` contains `provider`, `action`, and `executionMode: "integration"`.
- If `provider`/`action` is missing or the API call fails, the step ends with `FAILED` and
  the error message in `output` + `metadata.error` (the workflow does not hard-abort,
  but delivers a failed step).
- Only string params are templated; numbers/booleans/arrays are passed through unchanged.

---

## REST API (`/api/v1/integrations`)

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/` | List all providers including connection status |
| `GET` | `/{providerId}` | Retrieve a single provider |
| `GET` | `/{providerId}/connect` | Generate OAuth2 authorize URL (query: `redirectUri`, optional `scopes`) |
| `GET` | `/{providerId}/callback` | OAuth2 callback — **public, no API key required** |
| `POST` | `/{providerId}/credentials` | Store client ID + secret (encrypted) |
| `DELETE` | `/{providerId}/connection` | Disconnect — delete tokens, keep credentials |
| `DELETE` | `/{providerId}/credentials` | Fully delete connection including credentials |
| `GET` | `/{providerId}/actions` | Available actions including input schema (**source of truth**) |
| `POST` | `/{providerId}/actions/{action}` | Execute an action for testing purposes |

---

## Adding a New Provider (Step by Step)

1. **Create the provider class** in `service/integration/providers/<name>/<Name>Provider.java`,
   implement `IntegrationProvider` and annotate with `@Component` → automatically registered by
   `IntegrationRegistry`.
2. **Provide metadata**: `getProviderId()` (unique, kebab-case), `getDisplayName()`,
   `getIcon()` (Lucide name), `getDescription()`.
3. **Return `getOAuthConfig()`**:
   - OAuth2 providers: authorize/token URL, default/optional scopes, env var names, and if needed
     `extraAuthParams` (e.g. `access_type=offline` for a refresh token).
   - Direct auth providers: additionally set `requiresOAuthFlow()` to `false`; interpret the two
     credential fields as key/token.
4. **Define `getAvailableActions()`** — per action: name, description, and a
   JSON schema (`type: object`, `properties`, `required`).
5. **Implement `executeAction(action, params, accessToken)`** — typically a
   `switch` over action names that calls the respective private method and hits the
   external REST API (`java.net.http.HttpClient`). Read parameters defensively via helpers like
   `requireParam` / `paramString` / `paramInt`.
6. **Optionally override `fetchAccountInfo(accessToken)`** to display the account (name/email)
   after connecting — for direct auth providers this call also serves as credential validation.
7. **Add a setup guide** in `frontend/src/data/integrationSetupGuides.ts`
   (`SETUP_GUIDES['<provider-id>']`) — steps bilingual (DE/EN), optionally links and
   credential placeholders.
8. **Optionally add a calendar adapter** in `frontend/src/hooks/calendar-adapters/` if the
   provider delivers events that should appear in the central calendar view.
9. **Write tests** (action dispatch, param validation, error paths) and verify the frontend
   at `localhost:3000`.

**No** Flyway migration or registry entry is needed — auto-discovery via
Spring DI is sufficient. Existing connections continue to use the same `integration_connections` table.

---

## Calendar Adapters (Frontend)

Adapter pattern for the central calendar page (`/calendar`). Each adapter fetches events from
a source and transforms them into `UnifiedCalendarEvent[]`. The aggregation is handled by the
hook `useCalendarAggregated`.

### `CalendarSourceAdapter` Interface (`hooks/calendar-adapters/types.ts`)

| Field | Type | Description |
|---|---|---|
| `sourceId` | `string` | Unique ID, e.g. `'google-calendar'`, `'schedules'`, `'runs'` |
| `label` | `string` | Display name in the source filter |
| `color` | `string` | Tailwind background class for calendar dots/event bars |
| `isAvailable` | `(integrations) => boolean` | Is the source active? System sources `true`; integrations check status `CONNECTED` |
| `fetchEvents` | `(from, to) => Promise<UnifiedCalendarEvent[]>` | Load + transform events for a time range (used as `queryFn`) |

### Registry (`hooks/calendar-adapters/registry.ts`)

```ts
export const CALENDAR_ADAPTERS: CalendarSourceAdapter[] = [
  googleCalendarAdapter,  // integration: google-calendar
  trelloAdapter,          // integration: trello
  schedulesAdapter,       // system source: scheduled workflows
  runsAdapter,            // system source: past runs
];
```

> **Important:** The array must be **static**. `useCalendarAggregated` calls a separate
> `useQuery` per adapter — the React Rules of Hooks require a constant number of hook calls
> per render. `isAvailable` controls the `enabled` flag, **not** whether the hook is called.

### Adding a New Calendar Adapter

1. Create a new file in `hooks/calendar-adapters/` that implements `CalendarSourceAdapter`.
2. Add the import + one line to the `CALENDAR_ADAPTERS` array in `registry.ts`.
3. The calendar page will automatically show the new source with its own filter toggle.

---

## Setup Guides (Frontend)

`frontend/src/data/integrationSetupGuides.ts` provides the UI instructions per provider:

```ts
export const SETUP_GUIDES: Record<string, SetupGuide> = {
  'google-calendar': { steps: [ /* SetupStep[] */ ], credentialLabels: { /* … */ } },
  // …
};
```

- `SetupGuide` = `{ steps: SetupStep[]; credentialLabels?: CredentialLabels }`
- `SetupStep` = `{ title: {de,en}; instructions: {de:string[], en:string[]}; link?; code? }`
- `credentialLabels` customizes the placeholder texts for the client ID/secret fields (e.g. "API Key"
  instead of "Client ID" for Trello).
- `backendBaseUrl()` determines the base URL for the redirect URI (accounts for dev port 3000
  → 9090, test → 8090, and `VITE_API_BASE_URL`).

---

## References

- Data model of the `integration_connections` table: [datenmodell.md](datenmodell.md)
- Block types and workflow structure: [WORKFLOW-GUIDE.md](WORKFLOW-GUIDE.md)
- Summary in the project instructions: [CLAUDE.md](../../CLAUDE.md) (section "Integration / Plugin System")
