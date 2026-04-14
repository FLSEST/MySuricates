# Integration / Plugin System

Plugin-Framework für externe Dienste (Google Calendar, Trello, Jira, ...). Jeder Provider ist ein Spring `@Component` das `IntegrationProvider` implementiert — auto-discovered wie `BlockExecutor`.

## Architektur

- `IntegrationProvider` Interface (`service/integration/`) — definiert Provider-ID, OAuth-Config, Actions, `executeAction()`, optional `fetchAccountInfo()` und `requiresOAuthFlow()`
- `IntegrationRegistry` — sammelt alle Provider via Spring DI (`List<IntegrationProvider>` → `ConcurrentHashMap`)
- `IntegrationOAuthService` — generischer OAuth2-Flow (Auth-URL, Callback, Token-Refresh, verschlüsselte Speicherung in DB)
- `IntegrationEncryptionService` — AES-256-GCM Verschlüsselung für Tokens und Client-Credentials
- `IntegrationBlockExecutor` — Block-Typ `integration` für Workflows, dispatcht an Provider
- `IntegrationController` (`/api/v1/integrations`) — REST API für Provider-Liste, OAuth-Flow, Credential-Speicherung, Action-Ausführung
- `IntegrationConnection` Entity (Flyway V55 + V56) — speichert OAuth-Tokens und Client-Credentials verschlüsselt

## Verfügbare Provider

| Provider | Provider-ID | Paket | Actions |
|----------|------------|-------|---------|
| Google Calendar | `google-calendar` | `providers/google/` | list_events, get_event, create_event, update_event, delete_event, get_free_busy |
| Gmail | `google-gmail` | `providers/google/` | list_messages, get_message |
| Jira | `jira` | `providers/jira/` | list_projects, search_issues, get_issue, create_issue, update_issue, add_comment, get_transitions, transition_issue |
| Confluence | `confluence` | `providers/confluence/` | list_spaces, list_pages, get_page, search |
| Trello | `trello` | `providers/trello/` | list_boards, get_board_lists, get_list_cards, get_card, create_card, update_card, delete_card, add_comment, search |

## Integration Block Config (Workflow)

```json
{
  "type": "integration",
  "config": {
    "provider": "google-calendar",
    "action": "create_event",
    "params": {
      "summary": "{{ workflow_name }} abgeschlossen",
      "startDateTime": "{{ started_at }}",
      "endDateTime": "{{ started_at }}"
    }
  }
}
```

## Neuen Provider hinzufügen

1. Java-Klasse in `service/integration/providers/<name>/` erstellen, `IntegrationProvider` implementieren
2. `@Component` annotieren → wird automatisch von `IntegrationRegistry` registriert
3. OAuth-Config, Actions und `executeAction()` implementieren
4. Setup Guide in `frontend/src/data/integrationSetupGuides.ts` hinzufügen
5. Optional: Kalender-Adapter in `frontend/src/hooks/calendar-adapters/` für Kalender-Integration

## Kalender-Adapter (Frontend)

Adapter-Pattern für die zentrale Kalender-Seite (`/calendar`). Jeder Adapter holt Events aus einer Quelle und transformiert sie in `UnifiedCalendarEvent[]`:
- `hooks/calendar-adapters/types.ts` — `CalendarSourceAdapter` Interface mit JSDoc
- `hooks/calendar-adapters/registry.ts` — statisches Adapter-Array (CALENDAR_ADAPTERS)
- Neuer Adapter = neue Datei + eine Zeile in `registry.ts`
- Aktive Adapter: `googleCalendarAdapter`, `trelloAdapter`, `schedulesAdapter`, `runsAdapter`

## Credential-Speicherung

Client-Credentials (Client ID + Secret) werden über die Integrationen-Seite (`/integrations`) eingegeben und AES-256-GCM-verschlüsselt in `integration_connections` gespeichert. Env-Var-Fallback (`GOOGLE_CLIENT_ID` etc.) für Deployment-Szenarien. OAuth-Tokens (Access + Refresh) werden ebenfalls verschlüsselt gespeichert, automatischer Token-Refresh 5 Minuten vor Ablauf.
