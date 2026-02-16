# Entwicklung

## Voraussetzungen

| Tool | Version | Prüfung |
|---|---|---|
| Java (JDK) | 21+ | `java --version` |
| Maven | 3.9+ | `mvn --version` |
| Node.js | 20+ | `node --version` |
| npm | 10+ | `npm --version` |
| Docker | 24+ | `docker --version` |

Docker muss laufen, da das Backend über den Docker Socket Container erstellt und verwaltet.

## Lokales Setup

### Backend starten

```bash
cd ai-automation-backend

# Dependencies laden und kompilieren
mvn clean compile

# Starten (Port 9090)
mvn spring-boot:run

# Alternativ: JAR bauen und ausführen
mvn clean package -DskipTests
java -jar target/ai-automation-backend-0.1.0-SNAPSHOT.jar
```

Das Backend erstellt automatisch `data/automation.db` beim ersten Start und führt Flyway-Migrationen aus.

### Frontend starten

```bash
cd ai-automation-frontend

# Dependencies installieren
npm install

# Dev-Server starten (Port 3000)
npm run dev
```

Der Vite-Dev-Server proxied automatisch `/api/*` und `/ws/*` an `localhost:9090`.

### Beide parallel starten

Terminal 1 (Backend):
```bash
cd ai-automation-backend && mvn spring-boot:run
```

Terminal 2 (Frontend):
```bash
cd ai-automation-frontend && npm run dev
```

Öffnen: `http://localhost:3000`

## Build

### Backend

```bash
cd ai-automation-backend

# Kompilieren
mvn clean compile

# JAR bauen (ohne Tests)
mvn clean package -DskipTests

# JAR bauen (mit Tests)
mvn clean package

# Ergebnis: target/ai-automation-backend-0.1.0-SNAPSHOT.jar
```

### Frontend

```bash
cd ai-automation-frontend

# TypeScript-Check + Vite-Build
npm run build

# Ergebnis: dist/ (statische Dateien)
```

### Docker-Images

```bash
cd ai-automation-backend

# Backend-Image
mvn clean package -DskipTests
docker compose build backend

# Alle Agent-Images
docker compose --profile build-only build
```

## Tests

### Backend-Tests

```bash
cd ai-automation-backend

# Alle Tests ausführen
mvn test

# Einzelne Testklasse
mvn test -Dtest=ProjectControllerTest

# Tests mit Coverage
mvn test jacoco:report
```

Die Tests nutzen `spring-boot-starter-test` (JUnit 5, MockMvc) und `spring-security-test`. Jeder Controller hat eine eigene Testklasse in `src/test/java/com/automation/controller/`.

### Frontend-Tests

```bash
cd ai-automation-frontend

# TypeScript-Typprüfung
npx tsc --noEmit

# Linting
npm run lint
```

### E2E-Tests (Cypress)

Die E2E-Tests laufen mit [Cypress](https://www.cypress.io/) gegen das **echte Backend** (kein Mocking). Testdaten werden per `cy.request()` direkt über die API angelegt und gelöscht.

**Voraussetzung:** Backend (Port 9090) und Frontend (Port 3000) müssen laufen.

```bash
cd ai-automation-frontend

# Cypress interaktiv öffnen (Browser-UI)
npm run cy:open

# Cypress headless ausführen (CI-Modus)
npm run cy:run

# Alias
npm run test:e2e
```

**Teststruktur:**

```
cypress/
  support/
    e2e.ts          ← Globaler beforeEach (Locale auf Englisch setzen)
    commands.ts     ← Custom Commands (apiCreateProject, shouldShowToast, navTo, ...)
    api.ts          ← HTTP-Helpers für Testdaten-Setup (CRUD gegen Backend-API)
  e2e/
    navigation.cy.ts  ← Sidebar-Navigation, Routing, Collapse/Expand
    dashboard.cy.ts   ← Stat-Cards, Charts, Quick Actions
    projects.cy.ts    ← Projekte CRUD (Create, Search, Detail, Delete)
    workflows.cy.ts   ← Workflows CRUD, Template-Auswahl, Detail, Blocks, Skills
    skills.cy.ts      ← Skill-Katalog, Filter, Detail-Slide-Over
    schedules.cy.ts   ← Schedules CRUD, Toggle, Tabelle
    webhooks.cy.ts    ← Webhooks CRUD, Toggle, Copy-Button
    settings.cy.ts    ← Theme, Sprache, API-Key, System Health
```

**Konventionen:**
- Englische Locale wird per `localStorage` vor jedem Test erzwungen
- Selektoren nutzen `aria-label`, Button-Text, `h1`-Text, `placeholder`, `role="switch"` (keine `data-testid`)
- `before()` / `after()` Hooks sorgen für saubere Testdaten via `cy.apiCleanup()`

## Projektkonventionen

### Backend

| Konvention | Beschreibung |
|---|---|
| **Entities** | JPA-Entities mit Lombok (`@Getter`, `@Setter`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor`) |
| **DTOs** | Java Records (`record` statt Klassen) für Request- und Response-Objekte |
| **Mapper** | MapStruct für Entity ↔ DTO Konvertierung (automatische Codegenerierung) |
| **IDs** | UUID als String (`@GeneratedValue(strategy = GenerationType.UUID)`) |
| **JSON-Felder** | `@Convert(converter = JsonMapConverter.class)` für `Map<String, Object>` in TEXT-Spalten |
| **Auditing** | `@CreatedDate`, `@LastModifiedDate` über Spring Data JPA Auditing |
| **Validierung** | Jakarta Validation (`@NotBlank`, `@NotNull`, `@NotEmpty`) auf DTOs |
| **Exceptions** | `GlobalExceptionHandler` mit `@RestControllerAdvice`, einheitliches Fehlerformat |
| **Logging** | SLF4J mit `@Slf4j` (Lombok) |
| **Transaktionen** | `@Transactional` auf Service-Methoden |

### Frontend

| Konvention | Beschreibung |
|---|---|
| **Styling** | Tailwind CSS mit CSS-Variablen, keine CSS-Module oder styled-components |
| **State** | Server-State via TanStack Query, Client-State via Zustand |
| **Komponenten** | Funktionskomponenten mit benannten Exports |
| **Hooks** | Custom Hooks für jede API-Ressource |
| **Typen** | Zentral in `src/types/index.ts` definiert |
| **i18n** | Typsichere Übersetzungsobjekte (kein Runtime-Key-Lookup) |
| **Icons** | Lucide React (konsistentes Icon-Set) |
| **Forms** | Kontrollierte Komponenten mit React State |

### Annotation-Prozessoren

Das Backend nutzt drei Annotation-Prozessoren, die in der Compiler-Konfiguration (`pom.xml`) korrekt geordnet sein müssen:

1. **Lombok** — Generiert Getter, Setter, Builder, etc.
2. **MapStruct** — Generiert Mapper-Implementierungen
3. **lombok-mapstruct-binding** — Brücke zwischen Lombok und MapStruct

## Bekannte Einschränkungen

### SQLite

- **Concurrent Writes:** SQLite erlaubt nur einen Schreibzugriff gleichzeitig. Der WAL-Modus und HikariCP (max. 3 Connections) mildern dies, aber bei hoher Last können Lock-Konflikte auftreten.
- **Keine nativen Typen:** Booleans als INTEGER, Timestamps als TEXT, JSON als TEXT.
- **Kein ALTER COLUMN:** Schema-Änderungen sind eingeschränkt (nur ADD COLUMN).

### Docker-Abhängigkeit

- Das Backend benötigt Zugriff auf den Docker Socket (`/var/run/docker.sock`).
- Ohne Docker können keine Projekte gestartet (Container erstellt) und keine Workflows ausgeführt werden.
- Agent-Images müssen vorher gebaut sein (`docker compose --profile build-only build`).

### Sicherheit

- Der Docker-Socket-Mount gibt dem Backend Host-Level Docker-Zugriff. In Produktionsumgebungen sollte mindestens API-Key-Auth aktiviert sein.
- CORS ist standardmäßig offen (`*`). Für Produktion sollten spezifische Origins konfiguriert werden.
- Das Rate Limiting ist standardmäßig deaktiviert.

### Frontend

- Kein Offline-Modus (PWA-Shell wird gecacht, aber API-Daten nicht).
- Monaco Editor lädt externe Bundles (erfordert Internetverbindung beim ersten Laden).
