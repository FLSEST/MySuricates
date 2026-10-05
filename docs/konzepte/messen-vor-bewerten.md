<!-- concept-source: d7e7e28cf7209b4369ad33467bb4fe781a178baa -->

# Deterministisch messen vor KI-Bewertung

```mermaid
flowchart LR
  A["Daten / Code"] --> B["Messskript"]
  B --> C["Faktenblatt"]
  C --> D["KI-Einordnung"]
  D --> E["Deterministisches Gate"]
  E --> F["Bericht"]
```

## Wofür taugt das?

„Messen vor Bewerten“ trennt Datenerhebung, Einordnung und Prüfung. Zuerst sammelt ein deterministischer Schritt Fakten mit Shell-Befehlen, optional in einem Container. Der Schritt protokolliert Eingabe, Ausgabe, Laufzeit, Exit-Code und Ausführungsmodus; mit aktiviertem Prüfmodus wird der Befehl zusätzlich mit `set -e -o pipefail` ausgeführt. Erst danach bewertet ein KI-Schritt die vorliegenden Befunde statt frei zu raten. Abschließend kann ein Gate-Status aus der Ausgabe gelesen werden, etwa GREEN, YELLOW oder RED samt Kategorie und Beschreibung. Sinnvoll ist das zum Beispiel bei technischen Reviews: Tests, Dateistatistiken oder Sicherheitsprüfungen werden zuerst gemessen; die KI fasst anschließend Risiken und nächste Schritte zusammen.

## Vorteile

Der Mechanismus spart Modellarbeit dort, wo einfache Werkzeuge reichen. Messungen liefern konkrete Artefakte wie Exit-Codes, Dauer und Ausgaben, die später überprüfbar bleiben. Container-Ausführung kann die Umgebung klarer machen, ohne die Bewertung selbst zu vermischen. Gates schaffen eine knappe maschinenlesbare Entscheidungsschicht über längeren Ausgaben. Dadurch wird die KI stärker als Interpretin von Befunden genutzt, nicht als Quelle der Rohdaten.

## Nachteile und Kosten

Shell ist nicht kostenlos: Befehle brauchen Zeit, Ressourcen, Pflege und passende Werkzeuge. Ergebnisse sind nur so gut wie Messskripte, Datenqualität und aktuelle Umgebung; veränderte Daten können andere Resultate erzeugen. Daten zu Verifikationsabdeckung und kausalem Nutzen fehlen.

## Im Code

- [ShellBlockExecutor.java](../../ai-automation-backend/src/main/java/com/automation/executor/blocks/ShellBlockExecutor.java)
- [GateStatusOutputParser.java](../../ai-automation-backend/src/main/java/com/automation/service/runoutput/GateStatusOutputParser.java)
