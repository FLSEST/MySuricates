# Timeout-Analyse — 2026-09-12

TODO: `4778ac4c-5e49-44ad-bb34-f519e332c11f`  
Audit-Run: `a79bffec-cc1d-4f9b-8184-92a9ef4b6e4a`  
Workflow: MySuricates Performance Audit (`d09b9c24-1e6a-4e00-a1df-64b8bb9827ba`)

## Befund

Der Audit-Shell-Block meldete für die letzten 24 Stunden „25 Timeout-Einträge“.
Die Zählung ist nicht belastbar: Der verwendete Ausdruck zählt jede Logzeile mit
dem Wort `timeout`, darunter normale Konfigurationszeilen wie `Shell execute …
timeout: 300s`, nicht nur abgelaufene Befehle. Der nachfolgende LLM-Report nennt
abweichend 7 Timeouts (zuvor 90), obwohl derselbe Run im Rohmesswert 25 ausgibt.

Eine erneute lesende Auswertung der vorhandenen `mysuricates.log` für den
2026-09-12-Bestand ergibt 40 Treffer für `timeout`, davon 20 normale
`Shell execute`-Konfigurationszeilen, aber **0** Treffer für `timed out`,
`action timed out`, `TimeoutException` oder vergleichbare Fehlermeldungen.
Damit gibt es aus diesem Log keinen Beleg für 25 beziehungsweise 7 echte
Timeout-Abbrüche.

Im selben Zeitraum sind 15 Container-Start-/Reconcile-Ereignisse sichtbar; der
Audit-Run selbst protokolliert einen `SQLITE_CONSTRAINT_UNIQUE`-Fehler beim
Container-Insert. Die API-Latenzen im Run waren 2,685 ms und 1,964 ms. Der
bekannte `WorkflowMemoryService.extractKnowledge`-Pfad ist im Quelltext
vorhanden, erscheint aber nicht als Timeout-Ursache im aktuellen Log.

## Schlussfolgerung und Grenze

Der TODO-Befund ist ein Messfehler beziehungsweise eine ungeklärte Diskrepanz
zwischen Rohzähler und Report, keine nachgewiesene Timeout-Welle. Für eine
belastbare Zeitreihe braucht das Performance-Audit einen engeren Zähler, der nur
explizite Abbruch-/Timeout-Fehler klassifiziert und Konfigurationszeilen
ausnimmt; erst danach lassen sich Workflows, Blöcke oder Uhrzeiten sinnvoll
korrelieren. Es wurden keine Workflows gestartet, keine Konfiguration geändert
und keine Produktionsdaten geschrieben.
