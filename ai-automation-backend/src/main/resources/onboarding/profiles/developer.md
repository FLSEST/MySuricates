## Entwickler-Workflows

Für Code-Projekte stehen folgende Workflow-Patterns zur Verfügung:

### Code-Analyse & Review
- **Code-Review Workflows** analysieren Änderungen auf Bugs, Security-Issues und Code-Qualität
- Nutze `shell`-Block für `git diff` + `simple`-Block für Claude-Analyse
- Für tiefgehende Analyse: `agentic`-Block mit Goal "Review code changes"

### Test-Pipelines
- `shell`-Block zum Ausführen von Tests (`mvn test`, `npm test`, etc.)
- `retry`-Block bei flaky Tests mit `max_retries: 3`
- `conditional`-Block für branch-abhängige Test-Suiten

### Auto-Fix & Quality Gates
- `agentic`-Block mit Goal "Fix lint errors" oder "Fix failing tests"
- Gate-Status-Marker in Block-Output: `gate_status: GREEN|YELLOW|RED`
- `approval`-Block vor dem Anwenden von automatischen Fixes

### CI/CD Patterns
- `shell`-Block für Build-Kommandos
- `foreach`-Block für Multi-Modul-Builds
- Chain mehrere Workflows: Build → Test → Deploy

### Empfohlene Variablen
- `branch`: Git-Branch für kontextabhängige Aktionen
- `base_dir`: Projektverzeichnis
- `test_filter`: Spezifische Tests ausführen
