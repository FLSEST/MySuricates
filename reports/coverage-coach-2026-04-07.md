### Coverage-Coach — 2026-04-07T00:00:00Z

**Status**: 45.5% Datei-Coverage (200/440 Sources haben Tests)
**Trend**: -3.6% seit letztem Run | -0.3% seit letzter Woche
**Letzter Rat befolgt?**: Ja: `CreateWorkflowRequest` hat jetzt eine Testdatei mit 16 `@Test`

Die Datei-Coverage ist trotz befolgtem Rat gefallen, weil der Nenner seit dem Report vom 2026-04-05 von 407 auf 440 Backend-Sources gestiegen ist, waehrend die Zahl abgedeckter Dateien bei 200 blieb.

### Fortschritt
```text
Ziel:    ████████████████████░░░░░░░░░░ 60% (Milestone 1)
Aktuell: ███████████████████████░░░░░░░ 45.5%
```

### Methoden-Level
- `ExecutionDefaults`: 10 `@Test` vs 25 public Methoden (40.0%)
- `CliSession`: 7 `@Test` vs 15 public Methoden (46.7%)

### NAECHSTER SCHRITT (der wichtigste)
**Klasse**: `ApproveTodosRequest`
**Warum diese**: Kleinste aktuell ungetestete Backend-Klasse ohne offenes TODO. 3 LOC, 1 public Record-Komponente, reiner Quick-Win im selben Paket wie `CreateWorkflowRequestTest`; der Test ist in wenigen Minuten fertig und bringt sofort wieder positives Momentum.
**Geschaetzter Gewinn**: +0.23% (201/440 = 45.7%)
**Referenz-Test**: `CreateWorkflowRequestTest.java`
**Aufwand**: klein

### Roadmap (naechste 5)
| # | Klasse | LOC | Gewinn | Kumuliert |
|---|--------|-----|--------|----------|
| 1 | ApproveTodosRequest | 3 | +0.23% | 45.7% |
| 2 | UpdateMemoryRequest | 6 | +0.23% | 45.9% |
| 3 | ApproveExecutionRequest | 7 | +0.23% | 46.1% |
| 4 | ExecuteCommandRequest | 7 | +0.23% | 46.4% |
| 5 | ExecuteWorkflowRequest | 7 | +0.23% | 46.6% |

**Prognose**: Bei ~0.23%/Tag erreichen wir 60% in rund 9 Wochen.

### todo_item (IMMER GENAU EINS)

todo_item: ApproveTodosRequest testen | HIGH | backend,testing | 3 LOC, 1 public Methode. Geschaetzter Gewinn: +0.23%. Referenz-Test: CreateWorkflowRequestTest.java. Getter/Record-Semantik sowie equals/hashCode/toString fuer den boolean-Flag `approved` absichern.

gate_status: YELLOW
gate_category: test-coverage
gate_description: 45.5%, Trend -3.6%, Naechster Schritt: ApproveTodosRequest
