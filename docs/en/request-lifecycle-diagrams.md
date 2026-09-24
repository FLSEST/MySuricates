<!-- Generated from docs/request-lifecycle-diagrams.md and docs/i18n/request-lifecycle-diagrams.en.json by scripts/docs-diagrams.py — do not edit by hand. -->

# AutoPilot — Request Lifecycle & Security

From the browser to the database: filters, error format, auth, rate limits

## System Overview — *Request Path*

HTTP requests go through the Vite proxy (dev) or straight to the Spring Boot backend, pass the security filters and end up in the REST controllers.

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

## Filter Chain — *Before the Controllers*

Order: ApiKeyAuth → RateLimit → Controller. With a misconfiguration or a missing key, the request is rejected before any business logic runs.

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
    alt API key missing/invalid (if enabled)
        AK-->>Client: 401 Unauthorized
    else ok
        AK->>RL: proceed
        alt limit exceeded
            RL-->>Client: 429 Too Many Requests
        else ok
            RL->>CTR: proceed
            CTR-->>Client: Response / Error
        end
    end
```

## Controller → Service → Repository — *Example: /projects*

Controllers are thin; services encapsulate the logic, repositories talk to SQLite.

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
    SVC-->>CTR: DTOs via mapper
    CTR-->>Client: 200 JSON
```

## Error Format — *GlobalExceptionHandler*

| Case | Status | Body |
|---|---|---|
| Validation | 400 | `{"error":"Validation failed","fieldErrors":{...}}` |
| Not Found | 404 | `{"error":"Resource not found"}` |
| Rate Limit | 429 | `{"error":"Too many requests"}` |
| Server | 500 | `{"error":"Internal server error"}` |

## Security & Rate Limit — *Configuration*

### API Key

`automation.security.enabled=true`

Header: `X-API-Key`

Constant-time comparison (`MessageDigest.isEqual`)

### Rate Limit

`automation.security.rate-limit.enabled` (default false)

Sliding window, 120 req/min per client IP

Header: `X-RateLimit-Remaining`

## Open Endpoints — *Without API Key*

- `GET /api/v1/system/health`
- `GET /api/v1/system/info`
- `POST /api/v1/webhook/trigger/{token}`
- `/ws/**` (SockJS/STOMP)
- `/actuator/**`
