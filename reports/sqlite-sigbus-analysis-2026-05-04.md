# SQLite SIGBUS-Crash Analyse — 2026-05-04

TODO: `0cb35bd3-34fa-4946-b39c-fa3a3abf6e5a`

## Symptom

JVM stirbt mit `SIGBUS (BUS_ADRERR)` im SQLite-JDBC-Native-Code. Browser sieht "Failed to fetch" / `ECONNREFUSED` während des Crashs.

## Datenpunkte

| Crash | Datum | Kontext | Stack-Top |
|---|---|---|---|
| `hs_err_pid731669.log` (in `data/logs/`) | 2026-04-27 11:12 | App-Start, `WorkflowTemplateSeeder` Commit | `NativeDB.step` → `SQLiteConnection.commit` |
| `hs_err_pid3074346.log` (in Backend-Root) | 2026-04-13 21:42 | Maven Surefire Test-Run | `NativeDB.step` → `JDBC3PreparedStatement.executeLargeUpdate` |

**Beide Crashes:**
- Identischer Offset im Native-Code: `libsqlitejdbc.so+0x3887a`
- Signal-Code `BUS_ADRERR` (= invalid physical address)
- sqlite-jdbc-Version `3.49.1.0`
- JVM `OpenJDK 21.0.10+7-Ubuntu-124.04`
- Library aus `/tmp/sqlite-…-libsqlitejdbc.so` extrahiert

## Hypothesen

### H1 — sqlite-jdbc 3.49.1.0 Bug ✅ wahrscheinlichste Ursache

`BUS_ADRERR` an einem festen Offset deutet auf einen Bug im Native-Code, nicht auf Filesystem-Probleme. Beide Crashes treffen exakt `+0x3887a`. Dieser Offset liegt im SQLite-Step-Pfad (`step()`-VDBE-Loop).

`xerial/sqlite-jdbc` hat zwischen 3.49.1 und 3.50 mehrere Memory-Safety-Fixes. 3.50.3.0 ist im lokalen Maven-Cache vorhanden.

### H2 — `/tmp` als Extraktionsort der JNI-Lib (verworfen)

`/tmp` liegt auf `/dev/nvme0n1p2` (ext4, **kein** tmpfs), 200 G frei. Damit fällt der klassische "tmpfs füllt sich → mmap fails"-Fall weg.

### H3 — Memory-mapped I/O auf der DB-Datei

SQLite kann die DB-Datei via `mmap` lesen. Wenn die mmap-Region durch parallele Writes ungültig wird (z. B. bei VACUUM oder großen Transaktionen), → SIGBUS beim Step.

Aktuelle JDBC-URL: `?journal_mode=WAL&busy_timeout=10000` — **keine** explizite mmap-Konfiguration. xerial/sqlite-jdbc default ist `mmap_size=0` (mmap aus). Damit ist diese Hypothese unwahrscheinlich, kann aber als Defense-in-Depth explizit hart abgeschaltet werden.

### H4 — Großer Seeder-Commit

`WorkflowTemplateSeeder.run()` ist `@Transactional` und committet 63 Templates in einer Tx. Das ist nicht riesig (jedes Template ein einzelner JSON-Blob, also ~500 KB Tx total). Sicher kein Out-of-Memory.

Aber: Falls das eigentliche Problem ein 3.49.1.0-Bug ist, der unter größeren Tx-Footprints auftritt, würde auch dieser Pfad ihn triggern.

## Maßnahmen (priorisiert)

### A1 — sqlite-jdbc Version-Bump 3.49.1.0 → 3.50.3.0 ✅ angewendet

`ai-automation-backend/pom.xml` auf 3.50.3.0 hochgezogen. Lokal bereits in `~/.m2/repository/`. Kein Code-Change nötig — API-kompatibel.

**Rollback-Plan:** `git revert` falls Tests bei 3.50.3.0 fehlschlagen.

### A2 — Defense-in-Depth: `mmap_size=0` (Empfehlung, nicht angewendet)

Falls A1 die Crashes nicht eliminiert, JDBC-URLs erweitern:

```yaml
url: jdbc:sqlite:./data/automation.db?journal_mode=WAL&busy_timeout=10000&mmap_size=0
```

`?mmap_size=0` ist seit sqlite-jdbc 3.7+ unterstützt und disabled die SQLite-internen mmap-Reads. Performance-Impact bei unserer DB-Größe (114 MB) vernachlässigbar.

### A3 — Seeder-Tx aufbrechen (Empfehlung, nicht angewendet)

`WorkflowTemplateSeeder.run()` aktuell mit einer `@Transactional` für alle 63 Templates. Bei Bedarf auf TransactionTemplate umstellen und pro Template einen kurzen Tx-Burst, gemäß CLAUDE.md "kurze DB-Bursts statt langer @Transactional".

Aktuell aber kein Performance-Indikator dafür — Crash kommt vermutlich vom Native-Bug, nicht vom Tx-Volumen. Falls A1 behebt → diesen Punkt nicht angreifen ("don't fix what isn't broken").

### A4 — Crash-Logs aufräumen (Empfehlung)

`hs_err_pid*.log` und `core.*` werden vom JVM bei jedem Crash erzeugt und können MB groß sein. Aktuell zwei alte Logs herumliegend. Cleanup-Schedule oder Crash-Log-Rotation als separates TODO erwägen.

## Nächste Schritte

1. ✅ **A1 angewendet** — Version-Bump committet
2. 🔜 **Beobachten** — bei Backend-Start prüfen, ob Crash wieder auftritt. 1 Woche ohne Crash → Bug erledigt.
3. ⏳ **Falls Crash wiederkommt** — A2 (`mmap_size=0`) anwenden, ggf. zusätzlich A3.
4. ⏳ **Crash-Log-Cleanup** als separates LOW-Prio TODO öffnen (Lifetime/Rotation für `hs_err_*` und `core.*`).
