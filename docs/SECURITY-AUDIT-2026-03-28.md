# Security Audit — MySuricates v0.6.1

**Datum:** 2026-03-28
**Scope:** OWASP Top 10, npm audit, Maven Dependencies
**Kontext:** Lokal laufende AI-Workflow-Plattform, Tailscale VPN, Single-User, API-Key Auth

---

## Zusammenfassung

| Schweregrad | Anzahl |
|---|---|
| CRITICAL | 1 |
| HIGH | 5 |
| MEDIUM | 4 |
| LOW | 2 |
| npm audit (Dev-only) | 12 (kein Prod-Risiko) |

**Nicht gefunden (korrekt implementiert):**
- Keine SQL-Injection (alle Queries parameterisiert)
- Keine Jackson Default-Typing (sichere Deserialisierung)
- Keine Java-Serialisierung (kein ObjectInputStream)
- Kein XXE (kein XML-Parser im Einsatz)
- Keine hardcoded Secrets in Sourcecode
- Kein Swagger/OpenAPI exponiert
- Kein Docker privileged mode
- Constant-time API-Key-Vergleich (MessageDigest.isEqual)
- Webhook-Token mit 256-Bit Entropie (SecureRandom)
- Container-Hardening: --cap-drop ALL, --security-opt no-new-privileges
- Frontend: Kein dangerouslySetInnerHTML, kein eval(), kein innerHTML
- CORS korrekt restriktiv (nur localhost:3000/5173)

---

## CRITICAL

### CRIT-1: Security im docker-compose.yml standardmaessig deaktiviert

**Datei:** `ai-automation-backend/docker-compose.yml:16-17`

```yaml
- AUTOMATION_SECURITY_ENABLED=false
- AUTOMATION_API_KEY=
```

Wer `docker compose up` ausfuehrt, startet eine Instanz **ohne Auth** mit vollem Docker-Socket-Zugriff. Ein Angreifer im gleichen Netzwerk kann beliebige Workflows (inkl. Shell-Blocks) ausfuehren.

**Fix:** Default umdrehen:
```yaml
- AUTOMATION_SECURITY_ENABLED=true
- AUTOMATION_API_KEY=changeme  # MUSS ueberschrieben werden
```

---

## HIGH

### HIGH-1: STOMP-WebSocket erlaubt alle Origins ohne Authentifizierung

**Datei:** `WebSocketConfig.java:22`, `SecurityConfig.java:35`

```java
.setAllowedOriginPatterns("*")  // WebSocketConfig
.requestMatchers("/ws/**").permitAll()  // SecurityConfig
```

Der STOMP-Endpoint `/ws` ist komplett unauthentifiziert — `WebSocketAuthInterceptor` schuetzt nur `/ws/terminal/**`. Jede Webseite im lokalen Browser kann sich per WebSocket verbinden und Live-Execution-Logs (inkl. Shell-Output mit potenziellen Secrets) mitlesen.

**Fix:**
1. `WebSocketAuthInterceptor` auch fuer den STOMP-Endpoint registrieren
2. `setAllowedOriginPatterns` auf die konfigurierten CORS-Origins beschraenken

---

### HIGH-2: API-Key als URL-Query-Parameter bei WebSocket-Verbindungen

**Dateien:** `WebSocketAuthInterceptor.java:40`, `TerminalPanel.tsx:80`

```java
apiKey = servletRequest.getServletRequest().getParameter("apiKey");
```
```typescript
const wsUrl = `...?apiKey=${encodeURIComponent(apiKey)}`;
```

API-Key erscheint in Browser-History, Server-Logs, Referrer-Header und DevTools.

**Fix:** Short-lived Token-Flow: Client holt einmaligen Token via authentifiziertem REST-Endpoint, nutzt diesen fuer WebSocket-Handshake.

---

### HIGH-3: Rate-Limiting standardmaessig deaktiviert

**Datei:** `application.yml:75`

```yaml
rate-limit:
  enabled: ${AUTOMATION_RATE_LIMIT_ENABLED:false}
```

Ohne Rate-Limiting kein Schutz gegen Brute-Force auf API-Key oder Webhook-Tokens.

**Fix:** Default auf `true` setzen (120 rpm ist ein sinnvoller Default).

---

### HIGH-4: `mount_docker_socket` ist unkontrollierter Container-Escape

**Dateien:** `ShellBlockExecutor.java:45`, `ContainerShellService.java:114-116`

```java
Boolean.TRUE.equals(config.get("mount_docker_socket"))
```

Jeder Workflow mit `"mount_docker_socket": true` erhaelt `/var/run/docker.sock` — das ist voller Host-Root-Zugriff. Keine Server-seitige Freigabe-Pruefung.

**Fix:** Server-seitiges Flag `automation.execution.allow-docker-socket-mount: false` (Default: false). Nur wenn explizit aktiviert, wird `mount_docker_socket` im Workflow honoured. Jede Nutzung als SECURITY-Event loggen.

---

### HIGH-5: CliSession uebergibt Prompt als CLI-Argument statt stdin

**Datei:** `CliSession.java:158-161`

```java
List<String> cmd = new ArrayList<>(List.of("docker", "exec", containerId,
        "claude", "-p", command, ...));
```

Im Gegensatz zu `HostClaudeService` (das korrekt stdin nutzt) uebergibt `CliSession` den Prompt als `-p`-Argument. Ein Prompt der mit `--` beginnt (z.B. `--dangerously-skip-permissions`) wird als CLI-Flag interpretiert.

**Fix:** Wie `HostClaudeService` den Prompt via stdin pipen:
```java
List<String> cmd = new ArrayList<>(List.of("docker", "exec", "-i", containerId,
        "claude", "-p", "--output-format", "stream-json", "--verbose"));
// Prompt via stdin schreiben
proc.getOutputStream().write(command.getBytes(StandardCharsets.UTF_8));
proc.getOutputStream().close();
```

---

## MEDIUM

### MED-1: Kein Rate-Limit auf Webhook-Trigger-Endpoint

**Dateien:** `SecurityConfig.java:37`, `WebhookController.java:77`

`/api/v1/webhook/trigger/**` ist `permitAll()` und hat kein Rate-Limit. Ermoeglicht unbegrenzte Workflow-Ausfuehrungen bei bekanntem Token.

**Fix:** Per-IP Rate-Limit (10 req/min) auf Webhook-Trigger-Endpoint, unabhaengig vom globalen Rate-Limit-Flag.

---

### MED-2: Kein Audit-Log fuer Requests ohne API-Key

**Datei:** `ApiKeyAuthFilter.java:56-65`

Falsche Keys werden geloggt, aber Requests **ohne** Key an geschuetzte Endpoints erzeugen keinen Audit-Eintrag. Probing-Angriffe bleiben unsichtbar.

**Fix:** `else`-Branch der keinen Key an geschuetztem Endpoint logged.

---

### MED-3: Hardcoded Developer-Username in Dockerfile

**Datei:** `Dockerfile.stable:3-6`

```dockerfile
ARG HOST_USER=flo
ARG HOST_HOME=/home/flo
```

Personenbezogene Defaults in Build-Artefakten.

**Fix:** Defaults entfernen (`ARG HOST_USER` ohne Default), compose uebergibt `${USER}`.

---

### MED-4: Keine Content-Security-Policy Header

**Datei:** `SecurityConfig.java`

Kein CSP-Header konfiguriert. Falls `rehype-raw` jemals zum Markdown-Rendering hinzugefuegt wird, gibt es kein Sicherheitsnetz gegen XSS.

**Fix:**
```java
.headers(headers -> headers
    .contentSecurityPolicy(csp -> csp.policyDirectives(
        "default-src 'self'; script-src 'self'; connect-src 'self' ws://localhost:* wss://localhost:*; style-src 'self' 'unsafe-inline'; img-src 'self' data:"))
    .frameOptions(fo -> fo.deny()))
```

---

## LOW

### LOW-1: Partial Webhook-Token in Logs

**Datei:** `WebhookService.java:71-72`

Erste 8 Zeichen des Tokens werden bei INFO geloggt. Praktisch nicht ausnutzbar (48 Bit von 256 Bit), aber bei Log-Shipping unnoetig.

**Fix:** Token-Fragment aus Log entfernen.

---

### LOW-2: `open-in-view: true` mit Single-Connection SQLite

**Datei:** `application.yml:16`

Hibernate-Session bleibt waehrend gesamter HTTP-Serialisierung offen. Mit pool-size=1 kann das unter Last zu Latenz-Spikes fuehren.

**Fix:** `open-in-view: false`, alle Service-Methoden mit `@Transactional` annotieren.

---

## npm audit Bewertung

| Paket | CVE-Typ | Wo genutzt | Prod-Risiko |
|---|---|---|---|
| DOMPurify <=3.3.1 | mutation-XSS | Peer-Dep, nicht in `/src` importiert | Keins |
| rollup <4.59.0 | Arbitrary File Write | Build-Tool (vite) | Keins (nur Build-Zeit) |
| flatted <=3.4.1 | Prototype Pollution | ESLint-Transitive | Keins (Dev-only) |
| undici 7.x | HTTP Smuggling, DoS | jsdom/vitest | Keins (Test-only) |
| picomatch <=2.3.1 | ReDoS, Method Injection | Build-Tools | Keins (nur Build-Zeit) |
| minimatch 5.x | ReDoS | filelist (Dev) | Keins |
| brace-expansion | DoS (Memory) | ESLint-Transitive | Keins |
| serialize-javascript | RCE, DoS | workbox-build (PWA) | Keins (Build-only) |

**Empfehlung:** `npm audit fix` fuer Hygiene ausfuehren. Das behebt 10 von 12 ohne Breaking Changes. Die verbleibenden 2 (serialize-javascript via vite-plugin-pwa) erfordern `npm audit fix --force` mit Breaking Change auf vite-plugin-pwa 0.19.x.

---

## Maven Dependencies

Spring Boot 3.4.13 mit Spring Security 6.4.13 — aktuell und ohne bekannte CVEs. Jackson 2.18.5, SQLite JDBC 3.49.1.0, Flyway 10.20.1 ebenfalls aktuell. Keine auffaelligen Dependency-Risiken.

---

## Priorisierte Fix-Reihenfolge

1. **CRIT-1** — docker-compose.yml Security-Default umdrehen (5 min)
2. **HIGH-1** — STOMP-WebSocket Auth + Origin-Restriction (30 min)
3. **HIGH-3** — Rate-Limiting default=true (5 min)
4. **HIGH-4** — mount_docker_socket Server-Flag (30 min)
5. **HIGH-5** — CliSession Prompt via stdin (15 min)
6. **HIGH-2** — WebSocket Token-Flow (1-2h, groesseres Refactoring)
7. **MED-1 bis MED-4** — Mittelfristig
8. **npm audit fix** — Routine-Hygiene
