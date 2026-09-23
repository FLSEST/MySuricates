# Deutsch — ueberschreibt die englische Basis; fehlende Schluessel bleiben englisch.

# Schritte
M_step_welcome="Willkommen"
M_step_mode="Modus"
M_step_checks="Prüfung"
M_step_modules="Module"
M_step_ai="KI"
M_step_ports="Ports"
M_step_build="Bauen"
M_step_done="Fertig"
M_step_of="Schritt %s von %s"

# Kopf
M_header_setup="Einrichtung"
M_header_language="Sprache"
M_header_language_value="Deutsch  (ohne --lang: Englisch)"
M_header_ai="KI"
M_header_ports="Ports"
M_header_time="Dauer"
M_header_time_value="etwa %s Min."

# Tasten
M_keys_select="↑/↓ wählen · Enter bestätigen · b zurück · q beenden"
M_keys_checklist="↑/↓ bewegen · Leertaste an/aus · Enter weiter · b zurück"
M_keys_confirm="Enter = %s"
M_recommended="empfohlen"
M_confirm_default_yes="[J/n]"
M_confirm_default_no="[j/N]"
M_yes="ja"
M_no="nein"

# Modus
M_mode_title="Wie soll MySuricates laufen?"
M_mode_container="Eine Instanz — im Container"
M_mode_container_hint="nur Docker nötig"
M_mode_host="Eine Instanz — auf diesem Rechner"
M_mode_host_hint="installiert Java und Node bei Bedarf"
M_mode_staged="Gestaffelt — Dev · Test · Prod"
M_mode_staged_hint="Dev baut, Test prüft, Prod läuft"

# Befehle ausfuehren
M_run_failed="%s fehlgeschlagen (Exit %s) — letzte Zeilen:"
M_run_log="Vollständiges Log: %s"

# Willkommen / erneuter Aufruf
M_welcome_intro="Richtet MySuricates auf diesem Rechner in %s Schritten ein. b geht zurück, q beendet."
M_rerun_title="Es gibt schon eine Einrichtung"
M_rerun_change="Einstellungen von %s ändern"
M_rerun_change_hint="Port, Module, KI-Anbieter"
M_rerun_fresh="Neu einrichten"
M_rerun_fresh_hint="schreibt die Konfiguration neu"
M_rerun_quit="Beenden"
M_quit="Einrichtung abgebrochen."

# Pruefung
M_checks_system="%s · Paketmanager: %s"
M_checks_all_ok="Alle Voraussetzungen vorhanden"
M_checks_plan="Fehlt oder zu alt — wird ausgeführt:"
M_checks_install_q="Fehlende Werkzeuge jetzt installieren? (Systempakete brauchen sudo)"
M_checks_manual="Bitte von Hand installieren: %s"
M_checks_still_missing="Fehlt noch: %s — installieren und scripts/setup.sh erneut starten."
M_checks_docker_group="Docker läuft für diesen Durchgang über 'sg docker'; einmal ab- und anmelden, dann überall."
M_checks_docker_down="Docker ist installiert, läuft aber nicht — starten (Linux: sudo systemctl start docker, macOS: Docker Desktop öffnen)."
M_checks_summary_ok="Alles vorhanden — scripts/setup.sh im Terminal starten oder mit --yes."
M_checks_summary_missing="Fehlt: %s — scripts/setup.sh im Terminal oder mit --yes starten, dann wird es installiert."
M_status_ok="ok"
M_status_old="zu alt"
M_status_missing="fehlt"

# Module
M_modules_title="Welche Funktionen soll diese Instanz haben?"
M_modules_standard="Standard"
M_modules_standard_hint="Kern + Dashboard-Bericht, sonst nichts"
M_modules_research="Research"
M_modules_research_hint="+ Chains, Assistent, Insights, Memory, Audit"
M_modules_full="Voll"
M_modules_full_hint="alles, auch Webhooks, Fernzugriff, Terminal"
M_modules_custom="Eigene Auswahl…"
M_modules_custom_done="eigene Auswahl"
M_modules_custom_hint="einzelne Module und Härtung wählen"
M_modules_pick_title="Module"
M_modules_hardening_title="Härtung"
M_modules_ui_editable_q="Module später in der Oberfläche umschaltbar? (jeder mit API-Key könnte dann Netzwerk- und Host-Funktionen einschalten)"
M_modules_staged="Dev: alle Module · Test und Prod: Standard, unbeaufsichtigt nur versiegelte Workflows"

# KI
M_ai_title="KI-Anbieter"
M_ai_logged_in="installiert, angemeldet"
M_ai_logged_out="installiert, nicht angemeldet"
M_ai_missing="nicht installiert"
M_ai_choose="Welchen Anbieter sollen Workflows standardmäßig nutzen?"
M_ai_none="Keine KI-CLI gefunden. MySuricates startet trotzdem; KI-Blöcke brauchen eine davon:"
M_ai_install="%s — installieren: %s   anmelden: %s"
M_ai_login_hint="Vor dem ersten KI-Workflow bei %s anmelden: %s"
M_ai_access_q="Was soll von den KI-Anbietern dieses Rechners in den Container?"
M_ai_access_login="Nur die Anmeldung|Programme und je Anbieter die Anmeldung (empfohlen)"
M_ai_access_programs="Nur die Programme|jede Instanz meldet sich selbst an"
M_ai_access_all="Alles wie früher|ganze Anbieter-Verzeichnisse, im Container änderbar"
M_ai_providers_q="Welche Anbieter kommen in den Container?"
M_ai_access_done="KI im Container"
M_ai_token_q="Claude braucht im Container einen eigenen Token (claude setup-token, gilt ein Jahr). Jetzt erzeugen?"
M_ai_token_paste="Token einfügen (wird nicht angezeigt)"
M_ai_token_saved="Token gespeichert in %s (nur für dich lesbar)"
M_ai_token_hint="Claude im Container: einmal claude setup-token ausführen, dann CLAUDE_CODE_OAUTH_TOKEN=… nach %s (chmod 600)"

# Ports und Ordner
M_ports_title="Ports und Ordner"
M_ports_q="Port für %s (Oberfläche und API — ein Port)"
M_ports_busy="Port %s ist belegt — nehme %s"
M_projects_q="Ordner mit deinen Projekten (wird in den Container eingebunden)"
M_socket_q="Shell-Blöcke mit container_image erlauben? (bindet den Docker-Socket ein — volle Kontrolle über Docker)"

# Bauen und fertig
M_build_q="Jetzt bauen und starten?"
M_build_run="%s bauen und starten"
M_build_failed="Start von %s fehlgeschlagen — Details: %s"
M_dry_run="(Probelauf) würde ausführen: %s"
M_kept_existing="Vorhandene %s bleibt (--reconfigure schreibt sie neu)"
M_done_open="→  %s"
M_done_network="→  %s   (aus dem Netz)"
M_done_network_unknown="→  http://<Adresse dieses Rechners>:%s   (aus dem Netz; Adresse nicht ermittelbar)"
M_done_network_vpn="Aus dem Netz nur mit VPN davor (z. B. WireGuard, Tailscale) — nie offen ins Internet."
M_done_key="API-Key für den Login: %s"
M_done_manage="Start / Stopp:"
M_done_change="Ändern:"
M_done_not_started="eingerichtet, noch nicht gestartet"
M_done_key_cmd="API-Key ganz:"

# Hilfe (--help) und Fehler in den Argumenten
M_help="MySuricates-Einrichtung — von der nackten Maschine bis zum Login in einem Durchgang.

  ./scripts/setup.sh                 im Terminal: geführt, Schritt für Schritt
  ./scripts/setup.sh --yes           ohne Rückfragen: Defaults, fehlende Werkzeuge installieren, starten
  ./scripts/setup.sh --check         nur prüfen und den Plan zeigen (Exit 1, wenn etwas fehlt)

  --mode one|staged            eine Instanz (Default) oder Dev · Test · Prod
  --runtime container|host     im Container (Default, nur Docker nötig) oder auf diesem Rechner
  --preset core|research|full  Module (Default core); --modules a,b nimmt einzelne Module dazu
  --provider claude|codex|pi   Standard-KI-Anbieter (Default: der erste angemeldete)
  --port N                     Port der Instanz (Default 9090, belegte werden übersprungen)
  --bind ADDR                  Adresse (Default 127.0.0.1; 0.0.0.0 = aus dem Netz, VPN davor)
  --projects DIR               Projektordner für den Container (Default: Ordner über dem Klon)
  --docker-socket              Container: Shell-Blöcke mit container_image erlauben (volle Docker-Kontrolle)
  --ai-from-host MODUS         Container: was von den KI-Anbietern hineinkommt — login (Default), programs, all
  --ai-providers LISTE         Container: nur diese Anbieter, z. B. claude,codex (Default: alle)
  --name NAME                  Name der Instanz (Default main)
  --lang de                    Deutsch (Default Englisch)
  --reconfigure                vorhandene Instanz-Datei neu schreiben
  --preset core --force        in einer vorhandenen Einrichtung nur das Preset ändern
  --dry-run                    nichts installieren, bauen oder starten — nur zeigen und Dateien schreiben
  --show-key                   API-Key am Ende vollständig zeigen (Default: maskiert)
  --from-source                für Mitwirkende: Quellcode-Setup mit Vite (docs/entwicklung.md)
  --install / --start          ältere Schreibweise: ohne Rückfragen installieren bzw. am Ende starten

Ohne Terminal und ohne --yes/--install/--start wird nur geprüft. Jede Instanz ist eine Datei
deploy/instances/<name>.env; starten, stoppen und ansehen mit scripts/instance.sh.
Alles Weitere (Härtung, ui-editable, AUTOMATION_…-Einstellungen) gehört in diese Datei."
M_err_option="Unbekannte Option: %s (siehe --help)"
M_err_preset="Unbekanntes Preset: %s (erlaubt: %s)"
M_err_force="--force gilt nur zusammen mit --preset <%s> (siehe --help)"
M_err_mode="--mode one|staged, nicht '%s'"
M_err_runtime="--runtime container|host, nicht '%s'"
M_err_name="--name nur aus a-z, 0-9 und -"
M_err_bind="--bind braucht eine IP-Adresse"
M_err_port="--port braucht eine Zahl"
M_err_provider="Unbekannter Anbieter: %s (bekannt: %s)"
M_err_ai_from_host="Unbekannter Wert für --ai-from-host: %s (programs|login|all)"
M_err_ai_providers="Unbekannter Anbieter in --ai-providers: %s (bekannt: %s)"
M_err_module="Unbekanntes Modul: %s"
