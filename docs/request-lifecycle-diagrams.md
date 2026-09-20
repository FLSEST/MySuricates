# AutoPilot — Request Lifecycle & Security

Vom Browser bis zur Datenbank: Filter, Fehlerformat, Auth, Rate Limits

## Systemuebersicht — *Request Path*

HTTP-Anfragen laufen ueber den Vite-Proxy (Dev) oder direkt zum Spring Boot Backend, passieren Security-Filter und landen in den REST-Controllern.

```mermaid
flowchart LR
    B["Browser"] --> P["Vite Proxy /api -> :9090"]
    P --> F["ApiKeyAuthFilter"]
    F --> RLF["RateLimitFilter"]
    RLF --> C["REST Controller"]
    C --> S["Service"]
    S --> REPO["Repository (JPA)"]
    REPO --> DB["SQLite (WAL)"]
    C --> WS["STOMP /topic/**"]

    style DB fill:#2e1a1a,stroke:#30363d,color:#e6edf3
```

## Filterkette — *Vor den Controllern*

Reihenfolge: ApiKeyAuth → RateLimit → Controller. Bei Fehlkonfig oder fehlendem Key wird die Anfrage abgewiesen, bevor Business-Logik startet.

```mermaid
sequenceDiagram
    participant Client
    participant Proxy as Vite Proxy
    participant API as Spring Boot
    participant AK as ApiKeyAuthFilter
    participant RL as RateLimitFilter
    participant CTR as Controller

    Client->>Proxy: HTTP /api/v1/...
    Proxy->>API: forwarded request
    API->>AK: doFilter
    alt API-Key fehlt/ungueltig (wenn enabled)
        AK-->>Client: 401 Unauthorized
    else ok
        AK->>RL: proceed
        alt Limit ueberschritten
            RL-->>Client: 429 Too Many Requests
        else ok
            RL->>CTR: proceed
            CTR-->>Client: Response / Error
        end
    end
```

## Controller → Service → Repository — *Beispiel: /projects*

Controller sind schlank; Services kapseln Logik, Repositories sprechen SQLite.

```mermaid
sequenceDiagram
    participant Client
    participant CTR as ProjectController
    participant SVC as ProjectService
    participant REP as ProjectRepository
    participant DB as SQLite

    Client->>CTR: GET /api/v1/projects
    CTR->>SVC: findAll()
    SVC->>REP: findAll()
    REP-->>SVC: List<Project>
    SVC-->>CTR: DTOs via Mapper
    CTR-->>Client: 200 JSON
```

## Fehlerformat — *GlobalExceptionHandler*

| Fall | Status | Body |
|---|---|---|
| Validation | 400 | `{"error":"Validation failed","fieldErrors":{...}}` |
| Not Found | 404 | `{"error":"Resource not found"}` |
| Rate Limit | 429 | `{"error":"Too many requests"}` |
| Server | 500 | `{"error":"Internal server error"}` |

## Security & Rate Limit — *Konfiguration*

### API-Key

`automation.security.enabled=true`

Header: `X-API-Key`

Constant-Time Vergleich (`MessageDigest.isEqual`)

### Rate Limit

`automation.security.rate-limit.enabled` (default false)

Sliding Window, 120 req/min pro Client-IP

Header: `X-RateLimit-Remaining`

## Offene Endpunkte — *ohne API-Key*

- `GET /api/v1/system/health`
- `GET /api/v1/system/info`
- `POST /api/v1/webhook/trigger/{token}`
- `/ws/**` (SockJS/STOMP)
- `/actuator/**`
