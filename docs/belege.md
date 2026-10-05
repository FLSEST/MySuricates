# Belege: was die Plattform im Betrieb gefunden hat

Diese Datei sammelt Fälle, in denen ein Mechanismus dieses Systems etwas gefunden hat, das
der normale Entwicklungsprozess nicht gesehen hatte. Keine Demo-Beispiele — nachprüfbare
Vorfälle mit Datum, Run-Id oder Testnamen und einer Zahl.

**Warum das aufgeschrieben wird und nicht abgefragt.** Läufe werden nach 90 Tagen gelöscht
(`run-retention-days`, Stand 2026-09-19). Ein Beleg, der nur in der Datenbank steht, ist in
drei Monaten weg. Das ist die Ausnahme von der Regel, Abrufbares nicht in Markdown
einzufrieren: Hier friert nichts ein, hier wird bewahrt.

**Format je Fall.** Datum · welcher Mechanismus · was gefunden wurde · was der normale
Prozess übersehen hatte · woran man es nachprüft. Der letzte Punkt ist der wichtigste: Ein
Fall ohne Nachprüfbarkeit gehört nicht in diese Liste.

**Ehrlichkeitsregel.** Es wird benannt, *was* den Fehler aufgedeckt hat. Wenn ein Lauf
etwas fand, weil er überhaupt lief, dann steht das so da — nicht „die KI fand den Bug".
Die schwächere Formulierung ist oft die stärkere Aussage, weil sie unabhängig vom Modell
gilt.

---

## 2026-09-18 · Der Lauf schlägt die Test-Suite

**Mechanismus:** Performance-Audit-Workflow, Run `cd335b5c` (2026-09-18 21:27 UTC).

**Gefunden:** Eine Änderung am Faktenblatt lag nur in der Pool-Datei und hatte den
Live-Workflow in der Datenbank nie erreicht.

**Übersehen hatten das:** 9059 Backend-Tests, 1321 Frontend-Tests und der Audit-Selbsttest
mit 100 von 100 Prüfungen — alle grün. Alle drei prüften das **Artefakt** (die Datei im
Repository); nur der Lauf prüfte die **Wirklichkeit** (die Definition in der Datenbank).

**Nachprüfbar:** Run `cd335b5c`, Rohausgabe von Step 0.

**Aufgedeckt hat es nicht die KI-Analyse** — die benannte den Fehler nicht. Aufgedeckt hat
es, dass der echte Workflow lief und jemand seine Rohausgabe ansah.

## 2026-09-18 · Drei N+1-Muster beim ersten Ratchet-Lauf

**Mechanismus:** `RequestCostRatchetTest` — zählt je Route die SQL-Statements und
Antwort-Bytes gegen eine festgeschriebene Obergrenze.

**Gefunden:** Drei echte N+1-Muster beim allerersten Lauf, zwei davon jahrelang unbemerkt:
ein `select containers` je Workflow in `/workflows`, `/search` und `/dashboard`
(TODO `13d1e2a2`), die unbegrenzt wachsende Antwort von `/executions` (TODO `a05c9504`)
und die Eigentümer-Auflösung der Run-Listen (behoben in `9db34f3b`).

**Übersehen hatte das:** die gesamte bestehende Test-Suite. Kein Test hat je gemessen, was
eine Route *kostet* — nur, dass sie das Richtige zurückgibt.

**Nachprüfbar:** `RequestCostRatchetTest`, `BASELINE`-Konstante; TODOs `13d1e2a2`,
`a05c9504`.

## 2026-09-18 · 111 SQL-Statements in einer Menüansicht

**Mechanismus:** Betriebsmessung `GET /api/v1/system/request-metrics` — das Backend zählt
im laufenden Betrieb mit.

**Gefunden:** `GET /api/v1/schedules` braucht **111 SQL-Statements** pro Aufruf (p95
154 ms) — die höchste Statement-Zahl im System, deutlich über den 33 von `/workflows`.

**Übersehen hatte das:** der Ratchet selbst. Sein Seed ist klein (3 Projekte, 30
Workflows); eine Route, deren Kosten mit der Zeilenzahl wachsen, fällt dort nicht auf. Erst
die Messung auf echten Daten zeigte es — und dasselbe gilt für `/projects`, das im Ratchet
mit 4 Statements grün steht und real 37 braucht (TODO `6d0ab09f`).

**Nachprüfbar:** TODO `172a6a67`; Route erneut aufrufen und
`GET /api/v1/system/request-metrics` lesen.

## 2026-09-19 · Ein Gate war rot, ohne dass es auffiel

**Mechanismus:** Doku-Gate `scripts/public-readiness/doc-facts-check.sh` — vergleicht die
in der Doku genannten Größen mit dem Code.

**Gefunden:** Die Doku nannte 43 REST-Controller (real 45) und V73 als höchste Migration <!-- doc-facts-check: ignore — historische Zahl, das IST der Befund -->
(real V74), verteilt über 11 Dateien in beiden Sprachen. Zwei Controller fehlten nicht nur
als Zahl, sondern als Zeile: `DashboardWorkflowController` und
`SystemRequestMetricsController` — beide aus der Perf-Arbeit derselben Woche.

**Übersehen hatte das:** die Review der Änderungen, die diese Controller einführten. Das
Gate selbst lief nicht in der CI, war also rot, ohne dass jemand hinsah — ein Gate, das
nicht gemessen wird, ist grün.

**Nachprüfbar:** `bash scripts/public-readiness/doc-facts-check.sh` auf dem Commit vor
`a0a85490`; die Test-Suite `test-doc-facts-check.sh` stand dort bei 71 ok / 1 rot.

## 2026-09-19 · Sechs grüne Tests über einer Variable, die nie gesetzt wurde

**Mechanismus:** das Schreiben des Tests, den TODO `843be7a5` verlangte.

**Gefunden:** `injectPlatformData` setzte die Kontextvariable `request_metrics` **nie**. Die
sechs bestehenden `injectPlatformData`-Tests waren grün, weil das Fact-Sheet als Mockito-Mock
fehlte, also `null` war — die NullPointerException verschluckte ein `catch (Exception)` am
Ende der Methode, und die beiden vorher gesetzten Variablen sahen unverändert richtig aus.

**Übersehen hatte das:** `scripts/tests/test-audit-facts.sh`, das den Shell-Block mit einem
Fixture-Wert rendert. Das belegt die Vorlage, nicht die Injektion.

**Nachprüfbar:** Commit `4e3c3717`; die sechs neuen Tests in
`WorkflowContextDataServiceTest`.

## 2026-09-19 · 25 verschiedene Regel-Ids für 26 Befunde

**Mechanismus:** Auszählen der real vergebenen `ruleKey`-Werte über
`GET /api/v1/audit-findings`.

**Gefunden:** Weil fünf der sechs Audit-Prompts nur den Platzhalter `stabile-regel-id`
vorgaben, erfand das Modell je Lauf neue Schlüssel. Health-Audit: **26 Befunde, 25
verschiedene Schlüssel** — darunter `god-class-count`, `god-classes-count` und
`complexity.god-class-count` für dieselbe Sache. Performance: 28 Befunde, 27 Schlüssel.
Security: 35 Befunde, 35 Schlüssel. Jeder abweichende Schlüssel erzeugt beim nächsten Lauf
einen Wiedergänger statt eines Wiedersehens, weil der Fingerprint an der Schreibweise hängt.

**Übersehen hatte das:** der ruleKey-Katalog, der genau dagegen gebaut war — er deckte
1 von 6 Audits ab. Und das Audit Center, dem ein Wiedergänger wie ein neuer Befund aussieht.

**Nachprüfbar:** TODO `a17e2d19`; `GET /api/v1/audit-findings?size=500`, Schlüssel je
`auditKey` auszählen. Gegenprobe: `AuditRuleKeyCatalogTest`.

## 2026-09-19 · Das Gate fand den Fehler des Agenten, der es erweiterte

**Mechanismus:** die neue Vollständigkeitsprüfung der Migrationstabellen im Doku-Gate.

**Gefunden:** Beim Nachtragen von V75 hatte ein fehlerhafter Einschub die **V74-Zeile
zerschnitten** — die Tabelle sprang von V73 auf V75. Die vorhandene Prüfung auf die höchste
Nummer blieb grün, weil die höchste Nummer stimmte.

**Übersehen hatte das:** die Prüfung, die es hätte finden sollen, und der Agent, der sie
gerade erweiterte.

**Nachprüfbar:** im Stand von Commit `adf6b90d` eine V-Zeile aus `docs/datenmodell.md` entfernen und
`doc-facts-check.sh` laufen lassen — Meldung `migration-table-luecke` (seit dem Doku-Umbau im Oktober
2026 führt die Doku keine Migrationstabelle mehr).

---

## Was diese Liste nicht behauptet

Sie zählt keine Befunde, die eine KI *gemeldet* hat — davon gibt es viele, und ihr Wert
hängt am Modell. Sie zählt Fälle, in denen eine **deterministische** Messung oder ein
echter Lauf etwas zeigte, das vorher niemand sah. Das ist die engere und die belastbarere
Menge.

Wer einen Fall ergänzt: Format einhalten, Nachprüfbarkeit zuerst. Ein Fall, dessen Beleg
sich nicht mehr abrufen lässt, wird mit dem Hinweis versehen, was ihn belegte — nicht
gelöscht, aber auch nicht als abrufbar ausgegeben.
