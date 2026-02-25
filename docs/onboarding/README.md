# AutoPilot - Entwickler-Onboarding

## Willkommen!

Willkommen im **AutoPilot**-Projekt (auch bekannt als AI Automation Platform). Dieses Dokument gibt dir einen schnellen Ueberblick ueber das Projekt, seinen Zweck und die verwendeten Technologien.

---

## Projektuebersicht

AutoPilot ist eine **KI-gesteuerte Workflow-Automatisierungsplattform** mit visueller Oberflaeche. Sie ermoeglicht es, komplexe Automatisierungsablaeufe zu definieren, auszufuehren und zu ueberwachen -- gesteuert durch eine Kombination aus deterministischen Bloecken und KI-gestuetzten (agentischen) Ausfuehrungen.

### Kernfunktionen

| Funktion | Beschreibung |
|---|---|
| **Visueller Workflow-Editor** | Drag-and-Drop Block-Editor mit 11 Block-Typen (Simple, Shell, Retry, Conditional, ForEach, While, Agentic, Approval, Sub-Workflow, Skill-Capability) |
| **KI-Integration** | Claude CLI wird direkt auf dem Host ausgefuehrt fuer intelligente Entscheidungen und Code-Generierung |
| **Docker-basierte Ausfuehrung** | Isolierte Container (Python, Node.js, Go) fuer sichere Skill-Ausfuehrung |
| **Skill-System** | 16 eingebaute Skills + benutzerdefinierte Skills fuer wiederverwendbare Automatisierungsbausteine |
| **Scheduling & Webhooks** | Zeitgesteuerte (Cron) und HTTP-getriggerte Workflow-Ausfuehrungen |
| **Workflow-Templates** | 28 vorgefertigte Templates als Startpunkt fuer neue Workflows |
| **Echtzeit-Updates** | WebSocket-basierte Live-Updates waehrend der Ausfuehrung |
| **Approval Gates** | Manueller Freigabe-Mechanismus fuer kritische Schritte |
| **Workflow-Konvertierung** | Erfolgreiche agentische Laeufe koennen in deterministische Workflows umgewandelt werden |

---

## Technologie-Stack

### Backend

| Technologie | Version | Zweck |
|---|---|---|
| **Java** | 21 (LTS) | Programmiersprache mit Virtual Threads |
| **Spring Boot** | 3.2.2 | Web-Framework & Dependency Injection |
| **Spring Data JPA** | 3.2.x | Datenbankabstraktion (ORM) |
| **SQLite** | 3.44.1 | Eingebettete Datenbank (WAL-Modus) |
| **Flyway** | 9.x | Datenbank-Migrations-Management |
| **MapStruct** | 1.5.5 | Entity-DTO Mapping (Code-Generierung) |
| **Lombok** | 1.18.x | Boilerplate-Reduktion (Getter, Setter, Builder) |
| **Docker Java Client** | 3.3.4 | Container-Management via Docker API |
| **Maven** | 3.9+ | Build-Tool & Dependency-Management |

### Frontend

| Technologie | Version | Zweck |
|---|---|---|
| **React** | 18.3.1 | UI-Framework (SPA) |
| **TypeScript** | 5.3.3 | Typsichere JavaScript-Entwicklung |
| **Vite** | 5.0.12 | Build-Tool & Dev-Server |
| **TanStack Query** | 5.17.0 | Server-State Management & Caching |
| **Zustand** | 4.4.7 | Client-State Management |
| **TailwindCSS** | 3.4.1 | Utility-First CSS Framework |
| **Monaco Editor** | 4.6.0 | Code-Editor (VS Code Engine) |
| **xterm.js** | 6.0.0 | Terminal-Emulator fuer WebSocket-Terminal |
| **Cypress** | 13.17.0 | End-to-End Testing |
| **Vite PWA** | 0.17.4 | Progressive Web App Support |

### Infrastruktur

| Technologie | Zweck |
|---|---|
| **Docker** | Container-Runtime fuer Skill-Ausfuehrung |
| **Docker Compose** | Lokale Entwicklungsumgebung |
| **WebSocket** | Echtzeit-Kommunikation (Updates + Terminal) |
| **Claude CLI** | KI-Ausfuehrung auf dem Host-System |

---

## Projektstruktur (High-Level)

```
autotrigger/
├── ai-automation-backend/       # Spring Boot REST API (Port 9090)
│   ├── src/main/java/           # Java-Quellcode
│   ├── src/main/resources/      # Konfiguration & Migrationen
│   ├── src/test/                # Unit- & Integrationstests
│   ├── docker/                  # Dockerfiles fuer Agent-Container
│   └── pom.xml                  # Maven Build-Konfiguration
│
├── ai-automation-frontend/      # React SPA (Port 3000)
│   ├── src/                     # TypeScript-Quellcode
│   ├── cypress/                 # E2E-Tests
│   ├── public/                  # Statische Assets
│   └── package.json             # npm-Konfiguration
│
├── docs/                        # Dokumentation
│   └── onboarding/              # Dieses Onboarding-Paket
│
├── docker-compose.yml           # Entwicklungsumgebung
└── CLAUDE.md                    # KI-Agenten-Kontext
```

---

## Schnellstart

Fuer die vollstaendige Einrichtung siehe [SETUP.md](SETUP.md). Hier der Kurzueberblick:

```bash
# Backend starten
cd ai-automation-backend
mvn spring-boot:run

# Frontend starten (neues Terminal)
cd ai-automation-frontend
npm install && npm run dev
```

Danach ist die Anwendung unter **http://localhost:3000** erreichbar.

---

## Weitergehende Dokumentation

| Dokument | Inhalt |
|---|---|
| [SETUP.md](SETUP.md) | Schritt-fuer-Schritt Setup-Anleitung |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Architektur, Datenfluss & zentrale Konzepte |
| [CODE_WALKTHROUGH.md](CODE_WALKTHROUGH.md) | Die 10 wichtigsten Dateien erklaert |
| [GLOSSARY.md](GLOSSARY.md) | Projektspezifische Begriffe & Abkuerzungen |
| [FAQ.md](FAQ.md) | Haeufige Probleme und Loesungen |

---

## Ansprechpartner & Ressourcen

- **Projekt-Repository:** Lokales Git-Repository
- **API-Dokumentation:** Verfuegbar ueber die REST-Endpunkte unter `/api/v1`
- **System-Health:** `GET /api/v1/system/health`
- **Frontend-Dev-Server:** http://localhost:3000
- **Backend-Dev-Server:** http://localhost:9090
