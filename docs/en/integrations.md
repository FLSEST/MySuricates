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
| `OAuthConfig` | `service/integration/` | Record: OAuth2 endpoints (including the optional revoke endpoint), scopes, env var names, extra auth parameters |
| `IntegrationRegistry` | `service/integration/` | Collects all providers via Spring DI (`List<IntegrationProvider>` → `ConcurrentHashMap`) |
| `IntegrationOAuthService` | `service/integration/` | Generic OAuth2 flow: auth URL, callback, token refresh, encrypted storage |
| `OAuthTokenClient` | `service/integration/` | Outbound HTTP of the OAuth flow: code exchange, refresh, revocation (RFC 7009) |
| `OAuthStateRegistry` | `service/integration/` | CSRF `state` of running authorizations — 10-minute TTL, max. 1000 entries |
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
| `IntegrationVerification getVerification()` | ⬜ (from `VerifiedProviders`) | Verification: `VERIFIED` = checked against the real service, `SIMULATED` = only against simulated responses |
| `String getVerifiedAgainst()` | ⬜ (from `VerifiedProviders`) | What a `VERIFIED` provider was checked against, e.g. "Gitea 1.24, local container" |
| `boolean requiresOAuthFlow()` | ⬜ (default `true`) | `false` = direct auth via API key/token instead of OAuth2 authorization code flow |
| `boolean requiresCredentials()` | ⬜ (default `true`) | `false` = connecting also works with empty credential fields (e.g. Taskwarrior, iCal feed, ntfy) — see [Direct Auth Flow](#direct-auth-flow-providers-without-oauth-eg-trello) |
| `boolean connectionOptional()` | ⬜ (default `false`) | `true` = block and action run without any stored connection (token `null`); implies `requiresCredentials() == false` |
| `String fetchGrantedScopes(String accessToken)` | ⬜ (default `null`) | With direct auth: the scopes the token actually grants, `null` = unknown |
| `void validateCredentialInput(String, String)` | ⬜ (default: accept everything) | Checks the two credential fields before they are stored, `IllegalArgumentException` → `400` |
| `ModuleCatalog requiredModule()` | ⬜ (default `null`) | Module needed on top of `integrations`; while it is off, the provider counts as absent |

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
    Map<String,String> extraAuthParams, // additional authorize parameters (e.g. access_type=offline)
    String revocationUrl                // RFC 7009 revoke endpoint (null = provider has none)
)
```

`revocationUrl` is the 8th component and optional. Besides the full constructor the record has
three shorter ones (`OAuthConfig.java`): without `revocationUrl`; additionally without
`optionalScopes`; additionally without `extraAuthParams` — the missing values become `null` or
empty. For Google there is the constant `OAuthConfig.GOOGLE_REVOCATION_URL` and the method
`withRevocationUrl(url)`, which returns a copy with the revoke endpoint set (a "wither"), so a
provider config stays a single constructor call.

---

## Available Providers

| Provider | Provider ID | Auth Type | Package | Actions (snapshot) | Verification |
|---|---|---|---|---|---|
| Google Calendar | `google-calendar` | OAuth2 (Google) | `providers/google/` | `list_events`, `get_event`, `create_event`, `update_event`, `delete_event`, `get_free_busy` | verified |
| Gmail | `google-gmail` | OAuth2 (Google, readonly) | `providers/google/` | `list_messages`, `get_message` | verified |
| Jira | `jira` | OAuth2 (Atlassian 3LO, see below) | `providers/jira/` | `list_projects`, `search_issues`, `get_issue`, `create_issue`, `update_issue`, `add_comment`, `get_transitions`, `transition_issue` | verified |
| Confluence | `confluence` | OAuth2 (Atlassian 3LO) | `providers/confluence/` | `list_spaces`, `list_pages`, `get_page`, `search` | verified |
| Trello | `trello` | API key + token (no OAuth flow) | `providers/trello/` | `list_boards`, `get_board_lists`, `get_list_cards`, `get_card`, `create_card`, `update_card`, `delete_card`, `add_comment`, `search` | verified |
| Taskwarrior | `taskwarrior` | Local CLI (no OAuth, optional TASKDATA/TASKRC) | `providers/taskwarrior/` | `list_tasks`, `get_task`, `add_task`, `complete_task`, `modify_task`, `annotate_task`, `delete_task`, `list_projects`, `sync_task` | verified |
| GitHub | `github` | Personal Access Token (PAT, no OAuth flow) | `providers/github/` | `list_repos`, `list_issues`, `get_issue`, `create_issue`, `update_issue`, `add_comment` | verified |
| Gitea / Forgejo | `gitea` | Server URL + access token (no OAuth flow) | `providers/gitea/` | `list_repos`, `list_issues`, `get_issue`, `create_issue`, `update_issue`, `add_comment` | verified (local container) |
| GitLab | `gitlab` | Server URL (default `https://gitlab.com`) + access token (no OAuth flow) | `providers/gitlab/` | `list_projects`, `list_issues`, `get_issue`, `create_issue`, `update_issue`, `add_comment` | simulated |
| CalDAV | `caldav` | URL + user:app-password (Basic auth, no OAuth flow) | `providers/caldav/` | `list_calendars`, `list_events`, `create_event`, `update_event`, `delete_event` | verified (Radicale 3.8.0, local container) |
| iCal feed | `ical-feed` | none (public feed URL as parameter) | `providers/icalfeed/` | `list_events` | verified (public Google holiday feed) |
| ntfy | `ntfy` | Server URL + optional access token (no OAuth flow, both optional) | `providers/ntfy/` | `publish` | verified |
| RSS/Atom | `feed` | none (public feeds) | `providers/feed/` | `fetch_feed` | verified |
| Open-Meteo | `open-meteo` | none (free API without key) | `providers/openmeteo/` | `forecast`, `geocode` | verified |
| Todoist | `todoist` | API token (no OAuth flow) | `providers/todoist/` | `list_projects`, `list_tasks`, `get_task`, `create_task`, `update_task`, `close_task`, `add_comment` | simulated only |
| Slack | `slack` | Bot token `xoxb-…` (no OAuth flow) | `providers/slack/` | `post_message`, `list_channels`, `get_channel_history` | simulated only |
| Telegram | `telegram` | Bot token from @BotFather (no OAuth) | `providers/telegram/` | `send_message`, `get_updates` | simulated only |
| HTTP API | `http-api` | Base URL + optional auth header (no OAuth flow); **own module `http-api`** | `providers/httpapi/` | `request` | verified (httpbin.org) |

**Verification:** every provider states via `getVerification()` whether it was checked against the real service. The default is `SIMULATED` — a new provider is marked "simulated tests only" without any extra step, and the integrations page, the setup area and the block editor show a notice. A provider moves to `VERIFIED` only through an entry in `service/integration/VerifiedProviders` (provider id → what it was checked against) after a run against the real service — a local container of the real software counts. `IntegrationVerificationGateTest` finds all providers by classpath scan and checks that every entry names an existing provider.

**Task export (TODO → tracker):** MySuricates keeps its own task list (TODOs, page `/todos`, API
`/api/v1/todos`). A TODO can be transferred to an external tracker as an issue or card
(`POST /api/v1/todos/{id}/export`). Providers that additionally implement the interface
`TaskExportCapable` can do this: `createTask(input, accessToken)` creates the item,
`listTargets(accessToken)` optionally returns the selectable targets. Supported: GitHub (issue,
target `owner/repo`), Gitea/Forgejo (issue, target `owner/repo`; tags become labels where the
repository has them), GitLab (issue, target = project path `group/project` or numeric ID; tags
become labels), Jira (issue of type Task, target = project key, labels without spaces; browse URL
from the accessible resources), Trello (card, target = list ID, `listTargets` returns "Board / List";
tags are not sent as labels because Trello expects label IDs) and Todoist (task, target = project
ID, `listTargets` returns the projects; tags become label names, link
`app.todoist.com/app/task/<id>`). The platform detects such providers via `instanceof`, there is no
list to maintain; contract test `TaskExportContractTest`.

> **Taskwarrior is a special case:** not an HTTP service, but the local `task` CLI. `executeAction()` calls `task` via `ProcessBuilder` (argument array, no `bash -c`) on the backend host — via `service/cli/TaskCommandService.java` + `util/HostProcessUtils.drain()`. Requires `task` to be installed on the host. Without a stored connection it runs on the host's default paths (page: "No sign-in – needs `task`"); the optional setup checks `task _version` — if that fails, the connection stays disconnected and the page shows "Setup required". Timeout is runtime-configurable via `ExecutionSettingsService` (`task-command-timeout-seconds`). Security: filter tokens with `rc.` overrides or Taskwarrior commands (e.g. `execute`) are rejected, IDs are validated, free text is placed after `--`. Split internally: action list in `TaskwarriorActionCatalog`, argument building and validation in `TaskwarriorArgs`.

### CalDAV and iCal feed

Both share a small iCalendar reader/writer in `providers/ical/` (no third-party library): line folding
(75 octets, UTF-8 safe), text escapes, `DTSTART`/`DTEND` as UTC, with `TZID` or all-day (`VALUE=DATE`),
`DURATION`, `SUMMARY`/`DESCRIPTION`/`LOCATION`/`UID`; `RRULE` is only passed through. MySuricates does
**not** expand series itself. Times use the Google shape (`{date}` or `{dateTime, timeZone}`), so workflows
read both calendar sources the same way.

- **CalDAV** (`caldav`, RFC 4791): field 1 = CalDAV URL (Nextcloud `https://<server>/remote.php/dav`,
  Radicale `http://<server>:5232/`), field 2 = `user:app-password`; stored as `url|user:password`, checked by
  a PROPFIND (`fetchAccountInfo`). Discovery: `current-user-principal` → `calendar-home-set` → Depth-1
  PROPFIND. `list_events` sends a `calendar-query` REPORT with `time-range` and `<C:expand>` — the server
  returns series as single occurrences (with `recurrenceId`); without `calendar` all calendars are queried, and
  a broken calendar ends up under `errors` instead of hiding the others. `create_event` PUTs `<uuid>.ics` with
  `If-None-Match: *` (VTIMEZONE from the JDK rules for every TZID used), `update_event` fetches the object,
  changes only the given properties of the series master (attendees, alarms, exceptions stay), bumps
  `SEQUENCE` and writes with `If-Match` (ETag from the parameter or from the GET) — an event changed in the
  meantime yields 412 instead of being overwritten. `delete_event` removes the whole series for a series.
  Security: http/https only, no credentials in the URL, redirects are not followed (they would carry the
  Basic-Auth header), every requested URL must be on the server of the entered URL (hrefs from server
  responses too), XML parser without DOCTYPE/external entities (XXE), responses at most 10 MB, the password
  never appears in logs or error messages. Internals: `CalDavHttpClient`, `CalDavXml`, `CalDavDiscovery`,
  `CalDavEvents`, `CalDavActionCatalog`.
  **Verification:** against Radicale 3.8.0 in a local container (all five actions incl. a series with `RRULE`
  and `expand`, ETag conflict, foreign origin). Nextcloud, Posteo and iCloud are **not** checked live; iCloud
  points to a partition server (`pNN-caldav.icloud.com`) that the provider does not follow because of the
  origin rule — enter that address there.
- **iCal feed** (`ical-feed`): no credentials (`requiresCredentials() == false`), the feed URL is a parameter
  of `list_events` (`url`, `from`, `to`, `limit`, `timeZone`); `webcal://` is read as https. Download with a
  10 s connect / 20 s total timeout, at most 5 MB, only responses containing `BEGIN:VCALENDAR`. It returns single
  events overlapping the window and series masters with `rrule` and `recurring: true` (first occurrence as
  `start`; an `UNTIL` in the past hides the series, `EXDATE` is ignored) plus a `note`. Errors name the host
  only, never path or query — private feed links often contain a secret.

The calendar view shows CalDAV through `caldavAdapter` (all calendars, series already expanded by the
server). An iCal feed has no adapter because the feed URL is only known inside a workflow.
### HTTP API Connection (`http-api`)

General connection to an HTTP service you enter yourself. Own feature module
**`http-api`** (tier `advanced`, risks network + credentials, requires `integrations`), on in no
preset except `full`: `AUTOMATION_MODULES_HTTP_API=true` or `automation.modules.http-api: true`.
While the module is off, `IntegrationRegistry` hides the provider (list, lookup, block), and
`executeAction` refuses on its own as well — both checked per call, so a runtime switch takes
effect at once.

- **Credentials:** field 1 = base URL (only `http`/`https`, host required, no `user:pass@`, no
  `?`/`#`, no `.`/`..` segments, no `|`), field 2 = auth header `Name: Value` (e.g.
  `Authorization: Bearer …`, `X-API-Key: …`) or empty. Checked on save
  (`validateCredentialInput` → `400`) without any network call; the account shown is
  `host/base-path`.
- **Several services** = several [named connections](#several-connections-per-provider), chosen in
  the block via `"connection": "weather"`.
- **Action `request`**, parameters: `method` (`GET`/`POST`/`PUT`/`PATCH`/`DELETE`, default `GET`),
  `path` (relative to the base URL), `query` (object, a list repeats the name), `headers`
  (object), `body` (object/array → `application/json`, text → `text/plain`; not with `GET`,
  at most 1 MiB), `timeout_seconds` (1–120, default 30), `fail_on_error` (default `true`:
  status ≥ 400 fails the step; `false` returns it as the result).
- **Result:** `status`, `ok`, `content_type`, `body` (JSON parsed, else text cut to 100,000
  characters), `truncated`; for 3xx also `location` and `note`. At most 1 MiB of the answer is read.

**Security decisions:**

- *The path stays below the base URL:* rejected are scheme prefixes (`https:`, `javascript:` …),
  `//host`, backslash, `?`/`#` (query only via `query`), whitespace/control characters and every
  segment that — also percent-decoded (`%2e%2e`, `%2f`) — is `.` or `..` or contains a slash. The
  final URI is additionally checked against scheme, host, port and base-path prefix.
- *No automatic redirects* (`Redirect.NEVER`): a 3xx comes back with `location`, the auth header
  never goes to another target.
- *Header injection:* names must be HTTP tokens, values without CR/LF/control characters; the
  connection's auth header cannot be overridden in the block, hop-by-hop and framing headers
  (`Host`, `Content-Length`, `Transfer-Encoding`, `Connection`, `Proxy-Authorization` …) are
  blocked.
- *Never hand out the auth header:* stored encrypted, not in logs (only method, host and path are
  logged), and its value is replaced by `***` in the answer, `location` and error messages — a
  service that mirrors request headers does not reveal it.
- *Deliberately open:* targets in your own network (localhost, private addresses) are not blocked —
  the operator enters the base URL, and such services are often exactly the target. Whoever does
  not want that keeps the module off.

### OAuth Endpoints & Scopes per Provider

The table covers the providers with an OAuth2 flow (and Trello, which uses its fields for key and
token). Providers with a token instead of an OAuth flow follow under
[Providers with a token instead of an OAuth flow](#providers-with-a-token-instead-of-an-oauth-flow).

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
  uses the 3LO flow — Atlassian's name for OAuth 2.0 "three-legged": user, app and Atlassian take
  part, and the user consents in the browser. Such a token is not tied to an address like
  `company.atlassian.net`; after the token exchange the provider therefore asks
  `api.atlassian.com/oauth/token/accessible-resources` for the `cloudId`, the ID of the granted
  Atlassian site. The base URL is built from it, for Jira
  `https://api.atlassian.com/ex/jira/<cloudId>/rest/api/3` (`JiraProvider.buildBaseUrl`).
  `write:jira-work` is an optional scope for Jira (only needed for writes).
  Split internally: action catalog in `JiraActionCatalog`, HTTP via `JiraHttpClient` (no
  `Accept` header, no 401 special case), parameter parsing in `JiraParams`; the per-token
  `cloudId` cache stays on the provider.
- **Trello** does not use a token exchange. Client ID = API key, client secret = token; both
  are stored combined as `apiKey|token` and appended to every request as query parameters
  (`key=…&token=…`). Split internally: HTTP via `TrelloHttpClient`, parameter parsing in
  `TrelloParams`, action catalog in `TrelloActionCatalog`.

### Providers with a token instead of an OAuth flow

- **GitHub (PAT = Personal Access Token)** needs no OAuth flow: the token is validated via `GET /user` when saved and
  its scopes are taken from the `X-OAuth-Scopes` response header and stored on the connection
  (`fetchGrantedScopes`, classic tokens). Fine-grained PATs send no scope header — the
  connection then has `scopes = null` (unknown), which is **not** treated as "no scopes".
  The writing actions `create_issue`, `update_issue`, `add_comment` require `repo`; if it is
  missing from known scopes the UI shows the "permission missing" warning (advisory only,
  execution is not blocked). Least privilege: create a fine-grained PAT limited to the needed
  repositories with *Issues: Read and write* instead of a classic token with full `repo`.
  Split internally: HTTP via `GitHubHttpClient`, parameter parsing in `GitHubParams`.
- **Gitea / Forgejo** (one provider for both, same API under `/api/v1`) needs no OAuth flow:
  field 1 is the server URL (`http`/`https` only, no user info, query or fragment; a trailing
  slash is dropped), field 2 the access token. Both are stored as `url|token`
  (`ServerTokenCredential`) and checked via `GET /api/v1/user` when saved; header
  `Authorization: token <t>`. The token alone cannot read its own scopes (`scopes = null`).
  Gitea expects label IDs when creating an issue or replacing its labels: `GiteaLabels` resolves
  names against the repository's labels (unknown names: the action fails before writing, the task
  export drops them; numbers count as IDs, which also covers organisation labels). `update_issue`
  changes labels via `PUT …/issues/{n}/labels` because the edit endpoint has no labels field.
  Checked against Gitea 1.27.3 and Forgejo 14.0.5 in a local container.
- **GitLab** (gitlab.com or self-managed, API v4) needs no OAuth flow: field 1 is the server URL
  (prefilled with `https://gitlab.com` in the form; an empty stored value also means gitlab.com),
  field 2 a personal, project or group access token; header `PRIVATE-TOKEN`. Validation via
  `GET /api/v4/user`; `fetchGrantedScopes` reads the scopes from
  `GET /api/v4/personal_access_tokens/self` (GitLab 15.5+; otherwise `null`). `create_issue`,
  `update_issue` and `add_comment` require `api`; reading works with `read_api` or `api`.
  Projects are addressed by path (URL-encoded into one segment, `group%2Fproject`) or numeric ID,
  issues by their project-local IID; assignees only as numeric user IDs (`assigneeIds`).
  `update_issue` with `state` open/closed sets `state_event` reopen/close.
  Simulated tests only (request shapes follow the official API docs).

### Providers without mandatory credentials

- **ntfy** sends push notifications as a JSON `POST` to the server root (topic in the body).
  Field 1 = server URL (empty ⇒ `https://ntfy.sh`, `http`/`https` only), field 2 = access token
  (empty ⇒ anonymous, then no `Authorization` header). `requiresCredentials() == false`: without a
  saved connection it publishes anonymously to ntfy.sh. `fetchAccountInfo` checks
  `GET /v1/account` with a token, `GET /v1/health` without one. Topic names: letters, digits, `-`,
  `_`, at most 64 characters; `priority` 1–5 or `min`/`low`/`default`/`high`/`max`. Note: topics on
  ntfy.sh can be read by anyone who knows the name. Internally: `NtfyParams` (parsing, validation),
  `NtfyHttpClient` (HTTP, 401/403 → token hint).
- **RSS/Atom** (`feed`) reads RSS 2.0, Atom 1.0 and RSS 1.0 (RDF). `fetch_feed` returns per entry
  title, link, date (ISO 8601, UTC; RFC 822 and ISO values are normalised), summary (HTML removed,
  cut to 500 characters), author and id/guid — newest first, `limit` default 20 (at most 200); with
  `since`, older and undated entries are dropped. Fetching uses http/https only, on every redirect
  too (at most 5), responses of at most 5 MB, timeouts 10 s/20 s. The XML parser rejects any DOCTYPE
  declaration (no XXE, no entity expansion). Internally: `FeedFetcher` (HTTP limits), `FeedParser`,
  `FeedDates`, `FeedText` (HTML → text).
- **Open-Meteo** (`open-meteo`): `forecast` against `api.open-meteo.com/v1/forecast` (latitude
  −90…90, longitude −180…180, `days` 1–16 → `forecast_days`, variables for `hourly`/`daily`/`current`
  with a default selection, an empty value leaves the block out; `timezone` default `auto`; optional
  `temperature_unit`, `wind_speed_unit`, `precipitation_unit`) and `geocode` against
  `geocoding-api.open-meteo.com/v1/search` (`name`, `count` 1–100, `language` default `de`; no match
  gives `results: []`). Parameters are checked before any request, variable names only
  `[a-z0-9_]`. Responses pass through unchanged; API errors carry the response's `reason`.
  Internally: `OpenMeteoParams`, `OpenMeteoHttpClient` (base URLs via constructor for tests).
- **Todoist (API token)** uses the unified **Todoist API v1** (`https://api.todoist.com/api/v1`); the former
  REST API v2 has been shut down. The personal token (Settings → Integrations → Developer) is validated via
  `GET /user` when saved; only name and e-mail are kept from the answer (it also contains the token). Lists are
  paginated (`results` + `next_cursor`, `limit` 1–200), `list_tasks` takes either `project_id` (`GET /tasks`) or a
  Todoist filter (`GET /tasks/filter?query=…`). Since v1 errors are JSON (`error`, `error_tag`,
  `error_extra.retry_after`); only `error` and `error_tag` reach the message. Todoist tokens have no scopes
  (`scopes = null`). Split into `TodoistHttpClient`, `TodoistParams`, `TodoistActionCatalog`.
- **Slack (bot token)** calls the Web API (`https://slack.com/api/<method>`) with the bot token of an app
  installed in the workspace: `chat.postMessage` (JSON), `conversations.list` and `conversations.history` (cursor
  via `response_metadata.next_cursor`). Slack reports most errors with HTTP 200 and `{"ok":false,"error":…}` —
  the client treats that as a failure (`invalid_auth` and friends → token hint, `missing_scope` names the
  missing scope, 429 with `Retry-After`). Validation uses `auth.test`, the scopes come from its `x-oauth-scopes`
  header; `post_message` needs `chat:write`, `list_channels` `channels:read`, `get_channel_history`
  `channels:history`. Split into `SlackHttpClient`, `SlackParams`, `SlackActionCatalog`.
- **Telegram (bot token)** uses the Bot API (`https://api.telegram.org/bot<token>/<method>`, as of Bot API 10.3):
  `sendMessage` (`chat_id` as a number or `@channel`, text 1–4096 characters, `parse_mode` MarkdownV2/HTML) and
  `getUpdates` (short poll with `timeout=0`; clashes with a configured webhook → 409). Validation uses `getMe`.
  Errors come as `{"ok":false,"error_code":…,"description":…}`, 429 with `parameters.retry_after`. Because the
  token sits in the URL path, `TelegramHttpClient` checks its format before any URI is built, rethrows transport
  errors without their cause and replaces the token with `***` in every message.

---

## OAuth2 Flow (Authorization Code)

Implemented in `IntegrationOAuthService`:

1. **Store credentials** — `saveCredentials(providerId, clientId, clientSecret)`
   encrypts client ID and secret and stores them in `integration_connections`.
2. **Generate authorize URL** — `getAuthorizationUrl(providerId, redirectUri, additionalScopes)`
   - resolves the client ID (DB first, then env var),
   - generates a random `state` (32 bytes, `SecureRandom`) and stores it in the
     `OAuthStateRegistry` as CSRF protection — **10-minute TTL**, at most **1000** open
     authorizations (beyond that `409 Too many pending OAuth authorizations — retry later`;
     deliberately no eviction of the oldest entry),
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
   - `disconnect(providerId)` deletes all token fields and then revokes the token at the
     provider, but **retains** client credentials → reconnect without re-entering them.
   - `deleteCredentials(providerId)` removes the entire row including client credentials and
     revokes afterwards.
   - Both are deliberately **not** `@Transactional`: the row is written in a short
     `TransactionTemplate` burst and the revoke runs **afterwards**, outside any transaction.
     HTTP with a 10 s timeout inside a transaction would pin the single HikariCP connection
     (pool-size 1) for that whole time; "persist first" also rules out the state "revoked at the
     provider but still `CONNECTED` locally".

> **Token revocation** goes through `OAuthConfig.revocationUrl` (RFC 7009: `POST` with
> `token=<access-token>` in the form body, never in the query string). Google uses
> `https://oauth2.googleapis.com/revoke`; Atlassian 3LO (Jira, Confluence) publishes no revoke
> endpoint → `revocationUrl = null` and the call is skipped with an info log. A failed revoke
> never aborts the disconnect; the local token is deleted regardless.

> **Error messages** from code exchange and refresh carry only the provider ID and the HTTP
> status (e.g. "Token exchange failed for google-calendar (HTTP 400)"). The provider's response
> body goes to the log only: `GlobalExceptionHandler` passes the exception message on to the API
> client as a `409`, and token endpoints happily quote parts of the request (client secret
> included) back at us on failure.

### Direct Auth Flow (providers without OAuth, e.g. Trello)

For `requiresOAuthFlow() == false` the credentials themselves are the authentication:
`saveCredentials` combines them as `clientId|clientSecret`, calls `fetchAccountInfo()` for
validation, and on success sets the status directly to `CONNECTED`. There is no expiry and no
refresh — `getValidAccessToken()` returns the stored credential unchanged.
For a provider with `requiresCredentials() == false` (Taskwarrior, the iCal feed, ntfy, RSS/Atom,
Open-Meteo, the HTTP API),
`getValidAccessToken()` returns `null` instead of throwing when no connection row exists or
decryption fails — providers that need no credentials are allowed to run without a token.

Which of them actually **work** with `null` is what `connectionOptional()` says:
Open-Meteo, RSS/Atom, the iCal feed, ntfy and Taskwarrior — not the HTTP API, which needs its
stored base URL. `GET /api/v1/integrations` carries the field; the integrations page shows such a
provider without a stored connection as "Ready – no sign-in needed" instead of "Setup required"
and offers the setup as optional. A stored connection that is not connected (Taskwarrior's CLI
check failed, or it was disconnected) still blocks the provider — then it stays "Setup required".
Older backends without the field: the frontend derives it from `requiresCredentials` and the same
list (`components/integrations/providerReadiness.ts`, pinned by `ConnectionOptionalGateTest`).

### Several Connections per Provider

A provider can have several **named connections** (since database migration V86) — for example a
private and a work account at Google, or two GitHub tokens. Each connection is its own row in
`integration_connections`, unique by `(provider_id, connection_name)`.

- **Name:** 1–40 characters of lower-case letters, digits and dashes, starting with a letter or
  digit (`IntegrationConnectionNames`). Without a name (or blank) `default` applies — the
  connection that was the only one before V86; existing rows carry that name. An invalid name is
  rejected with `400 INVALID_ARGUMENT` before anything is read or written.
- **Service:** Every method of `IntegrationOAuthService` exists with and without a connection
  name; the variant without a name delegates with `default`. `listConnections(providerId)`
  returns all connections, `default` first.
- **OAuth:** The name travels in the server-side `state` (`OAuthStateRegistry`), not in the URL —
  the redirect URI at the provider stays the same for every connection. A named connection
  without its own app credentials signs in with the client ID/secret of the `default`
  connection (then the env var): a second account needs no second OAuth app.
- **Direct auth (PAT/API key):** Every connection needs its own credentials; there is no fallback
  to `default`.
- **Disconnect/delete** affects only the named connection.
- **UI:** On the Integrations page the "Connections" section lists all connections of the
  selected provider; "Add connection" asks for the name and leads through the same
  setup/credentials path. `default` stays managed through the provider card.

---

## Credential Storage & Encryption

- Tokens and client credentials are stored in `integration_connections` (`IntegrationConnection`),
  status enum `ConnectionStatus`: `CONNECTED`, `DISCONNECTED`, `EXPIRED`. The labels "Setup
  required" and "Ready – no sign-in needed" on the integrations page are not further states; the UI
  derives them from the status and `connectionOptional`
  ([Direct Auth Flow](#direct-auth-flow-providers-without-oauth-eg-trello)).
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
`resolveTemplate(...)` so that `{{ variable }}` placeholders work. Optionally `connection`
selects a named connection of the provider (placeholders allowed, default `default`, see
[Several Connections per Provider](#several-connections-per-provider)).

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
- `StepResult.metadata` contains `provider`, `connection`, `action`, and `executionMode: "integration"`.
- An invalid `connection` name (or a non-string) ends the step `FAILED` without contacting the
  provider.
- If `provider`/`action` is missing or the API call fails, the step ends with `FAILED` and
  the error message in `output` + `metadata.error` (the workflow does not hard-abort,
  but delivers a failed step).
- Only string params are templated; numbers/booleans/arrays are passed through unchanged.

---

## REST API (`/api/v1/integrations`)

Every endpoint that concerns one connection (`connect`, `credentials`, `connection`, `actions`,
`task-targets`) takes the optional query parameter `connection=<name>`; without it `default`
is meant.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/` | List all providers including connection status (status = `default` connection, plus `connections[]` and `requiresOAuthFlow`) |
| `GET` | `/{providerId}/connections` | All connections of a provider (name, status, `configured`, account, since, scopes), `default` first |
| `GET` | `/{providerId}` | Retrieve a single provider |
| `GET` | `/{providerId}/connect` | Generate OAuth2 authorize URL (query: `redirectUri`, optional `scopes`) |
| `GET` | `/{providerId}/callback` | OAuth2 callback — **public, no API key required** |
| `POST` | `/{providerId}/credentials` | Store client ID + secret (encrypted) |
| `DELETE` | `/{providerId}/connection` | Disconnect — delete tokens, keep credentials |
| `DELETE` | `/{providerId}/credentials` | Fully delete connection including credentials |
| `GET` | `/{providerId}/actions` | Available actions including input schema (**source of truth**) |
| `POST` | `/{providerId}/actions/{action}` | Execute an action for testing purposes |
| `GET` | `/{providerId}/task-targets` | Export targets of a task-capable provider (repos, projects, lists) |

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
   external REST API (`java.net.http.HttpClient`). Read parameters defensively: there is no
   shared helper class, each provider brings small static helpers of its own (`requireParam` /
   `paramString` / `paramInt`, e.g. in `providers/jira/JiraParams.java`). Calendar providers can
   use `providers/ical/CalendarParams`, whose helpers are public.
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
  caldavAdapter,          // integration: caldav
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
