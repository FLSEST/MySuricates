## Operations-Workflows

Für DevOps und System-Administration stehen folgende Workflow-Patterns zur Verfügung:

### Monitoring & Alerting
- `shell`-Block für Metriken-Abfragen (curl, API-Calls)
- `conditional`-Block für Schwellwert-Prüfungen
- Schedule-Trigger für regelmäßige Checks
- Gate-Status-Marker: `gate_status: RED` bei kritischen Werten

### Log-Analyse
- `shell`-Block zum Lesen und Filtern von Logs
- `simple`-Block für Claude-gestützte Analyse von Fehlermustern
- `agentic`-Block für tiefgehende Root-Cause-Analyse

### Deployment & Release
- Chain: Build → Test → Deploy → Verify
- `approval`-Block vor Production-Deployments
- `shell`-Block für Deploy-Kommandos und Health-Checks
- `retry`-Block für Health-Check-Polling nach Deploy

### Scheduled Tasks
- Schedule-Trigger für regelmäßige Wartungsaufgaben
- `shell`-Block für Cleanup, Backup, Log-Rotation
- `simple`-Block für tägliche Status-Reports

### Infrastruktur-Checks
- `foreach`-Block über Server-/Service-Liste
- `switch`-Block für service-spezifische Checks
- TODOs automatisch anlegen bei Problemen

### Empfohlene Variablen
- `environment`: Zielumgebung (dev, staging, production)
- `service_name`: Betroffener Service
- `threshold`: Schwellwert für Alerting
