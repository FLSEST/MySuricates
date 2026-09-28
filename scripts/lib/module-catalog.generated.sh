#!/usr/bin/env bash
# generiert von ModuleCatalogTextsTest — nicht von Hand ändern
#
# Quelle: com.automation.modules.ModuleCatalog (Schlüssel, Stufe, Reifegrad, Default, Risiken,
# Abhängigkeiten) und die Anzeigetexte aus ai-automation-frontend/src/data/modules.<lang>.json.
# Neu erzeugen:
#   cd ai-automation-backend && ./mvnw test -Dtest=ModuleCatalogTextsTest -Dmodule.catalog.write=true
#
# Reine Daten, keine Funktionen — eingelesen von scripts/lib/module-preset.sh, das vor jedem
# Build läuft und deshalb weder ein Backend noch jq voraussetzen darf.

MODULE_CATALOG_LANGS=(
  de
  en
)

MODULE_CATALOG_KEYS=(
  dashboard-report
  goals
  chains
  assistant
  learning
  memory
  audit
  bpmn
  integrations
  http-api
  webhooks
  remote-access
  terminal
  staging
  conversion
  dialog-import
  onboarding-profiles
  skills
  debug
  factory
  wishes
  provenance
  sealing
  require-sealed-for-unattended
  require-signed-seal
  seal-immutable
  webhook-signatures
  private-network-guard
  private-network-guard-http-api
)

MODULE_CATALOG_FEATURE_KEYS=(
  dashboard-report
  goals
  chains
  assistant
  learning
  memory
  audit
  bpmn
  integrations
  http-api
  webhooks
  remote-access
  terminal
  staging
  conversion
  dialog-import
  onboarding-profiles
  skills
  debug
  factory
  wishes
)

MODULE_CATALOG_HARDENING_KEYS=(
  provenance
  sealing
  require-sealed-for-unattended
  require-signed-seal
  seal-immutable
  webhook-signatures
  private-network-guard
  private-network-guard-http-api
)

# MODULE_CATALOG_TIER
MODULE_CATALOG_TIER__dashboard_report="standard"
MODULE_CATALOG_TIER__goals="standard"
MODULE_CATALOG_TIER__chains="research"
MODULE_CATALOG_TIER__assistant="research"
MODULE_CATALOG_TIER__learning="research"
MODULE_CATALOG_TIER__memory="research"
MODULE_CATALOG_TIER__audit="research"
MODULE_CATALOG_TIER__bpmn="research"
MODULE_CATALOG_TIER__integrations="advanced"
MODULE_CATALOG_TIER__http_api="advanced"
MODULE_CATALOG_TIER__webhooks="advanced"
MODULE_CATALOG_TIER__remote_access="advanced"
MODULE_CATALOG_TIER__terminal="advanced"
MODULE_CATALOG_TIER__staging="advanced"
MODULE_CATALOG_TIER__conversion="advanced"
MODULE_CATALOG_TIER__dialog_import="advanced"
MODULE_CATALOG_TIER__onboarding_profiles="advanced"
MODULE_CATALOG_TIER__skills="advanced"
MODULE_CATALOG_TIER__debug="advanced"
MODULE_CATALOG_TIER__factory="advanced"
MODULE_CATALOG_TIER__wishes="advanced"
MODULE_CATALOG_TIER__provenance="hardening"
MODULE_CATALOG_TIER__sealing="hardening"
MODULE_CATALOG_TIER__require_sealed_for_unattended="hardening"
MODULE_CATALOG_TIER__require_signed_seal="hardening"
MODULE_CATALOG_TIER__seal_immutable="hardening"
MODULE_CATALOG_TIER__webhook_signatures="hardening"
MODULE_CATALOG_TIER__private_network_guard="hardening"
MODULE_CATALOG_TIER__private_network_guard_http_api="hardening"

# MODULE_CATALOG_MATURITY
MODULE_CATALOG_MATURITY__dashboard_report="stable"
MODULE_CATALOG_MATURITY__goals="stable"
MODULE_CATALOG_MATURITY__chains="stable"
MODULE_CATALOG_MATURITY__assistant="experimental"
MODULE_CATALOG_MATURITY__learning="experimental"
MODULE_CATALOG_MATURITY__memory="experimental"
MODULE_CATALOG_MATURITY__audit="experimental"
MODULE_CATALOG_MATURITY__bpmn="experimental"
MODULE_CATALOG_MATURITY__integrations="stable"
MODULE_CATALOG_MATURITY__http_api="experimental"
MODULE_CATALOG_MATURITY__webhooks="stable"
MODULE_CATALOG_MATURITY__remote_access="stable"
MODULE_CATALOG_MATURITY__terminal="stable"
MODULE_CATALOG_MATURITY__staging="experimental"
MODULE_CATALOG_MATURITY__conversion="legacy"
MODULE_CATALOG_MATURITY__dialog_import="legacy"
MODULE_CATALOG_MATURITY__onboarding_profiles="legacy"
MODULE_CATALOG_MATURITY__skills="legacy"
MODULE_CATALOG_MATURITY__debug="legacy"
MODULE_CATALOG_MATURITY__factory="experimental"
MODULE_CATALOG_MATURITY__wishes="experimental"
MODULE_CATALOG_MATURITY__provenance="experimental"
MODULE_CATALOG_MATURITY__sealing="experimental"
MODULE_CATALOG_MATURITY__require_sealed_for_unattended="experimental"
MODULE_CATALOG_MATURITY__require_signed_seal="experimental"
MODULE_CATALOG_MATURITY__seal_immutable="experimental"
MODULE_CATALOG_MATURITY__webhook_signatures="experimental"
MODULE_CATALOG_MATURITY__private_network_guard="experimental"
MODULE_CATALOG_MATURITY__private_network_guard_http_api="experimental"

# MODULE_CATALOG_DEFAULT
MODULE_CATALOG_DEFAULT__dashboard_report="true"
MODULE_CATALOG_DEFAULT__goals="true"
MODULE_CATALOG_DEFAULT__chains="false"
MODULE_CATALOG_DEFAULT__assistant="false"
MODULE_CATALOG_DEFAULT__learning="false"
MODULE_CATALOG_DEFAULT__memory="false"
MODULE_CATALOG_DEFAULT__audit="false"
MODULE_CATALOG_DEFAULT__bpmn="false"
MODULE_CATALOG_DEFAULT__integrations="false"
MODULE_CATALOG_DEFAULT__http_api="false"
MODULE_CATALOG_DEFAULT__webhooks="false"
MODULE_CATALOG_DEFAULT__remote_access="false"
MODULE_CATALOG_DEFAULT__terminal="false"
MODULE_CATALOG_DEFAULT__staging="false"
MODULE_CATALOG_DEFAULT__conversion="false"
MODULE_CATALOG_DEFAULT__dialog_import="false"
MODULE_CATALOG_DEFAULT__onboarding_profiles="false"
MODULE_CATALOG_DEFAULT__skills="false"
MODULE_CATALOG_DEFAULT__debug="false"
MODULE_CATALOG_DEFAULT__factory="false"
MODULE_CATALOG_DEFAULT__wishes="false"
MODULE_CATALOG_DEFAULT__provenance="true"
MODULE_CATALOG_DEFAULT__sealing="false"
MODULE_CATALOG_DEFAULT__require_sealed_for_unattended="false"
MODULE_CATALOG_DEFAULT__require_signed_seal="false"
MODULE_CATALOG_DEFAULT__seal_immutable="false"
MODULE_CATALOG_DEFAULT__webhook_signatures="false"
MODULE_CATALOG_DEFAULT__private_network_guard="true"
MODULE_CATALOG_DEFAULT__private_network_guard_http_api="false"

# MODULE_CATALOG_PROPERTY
MODULE_CATALOG_PROPERTY__dashboard_report="automation.modules.dashboard-report"
MODULE_CATALOG_PROPERTY__goals="automation.modules.goals"
MODULE_CATALOG_PROPERTY__chains="automation.modules.chains"
MODULE_CATALOG_PROPERTY__assistant="automation.modules.assistant"
MODULE_CATALOG_PROPERTY__learning="automation.modules.learning"
MODULE_CATALOG_PROPERTY__memory="automation.modules.memory"
MODULE_CATALOG_PROPERTY__audit="automation.modules.audit"
MODULE_CATALOG_PROPERTY__bpmn="automation.modules.bpmn"
MODULE_CATALOG_PROPERTY__integrations="automation.modules.integrations"
MODULE_CATALOG_PROPERTY__http_api="automation.modules.http-api"
MODULE_CATALOG_PROPERTY__webhooks="automation.modules.webhooks"
MODULE_CATALOG_PROPERTY__remote_access="automation.modules.remote-access"
MODULE_CATALOG_PROPERTY__terminal="automation.modules.terminal"
MODULE_CATALOG_PROPERTY__staging="automation.modules.staging"
MODULE_CATALOG_PROPERTY__conversion="automation.modules.conversion"
MODULE_CATALOG_PROPERTY__dialog_import="automation.modules.dialog-import"
MODULE_CATALOG_PROPERTY__onboarding_profiles="automation.modules.onboarding-profiles"
MODULE_CATALOG_PROPERTY__skills="automation.modules.skills"
MODULE_CATALOG_PROPERTY__debug="automation.modules.debug"
MODULE_CATALOG_PROPERTY__factory="automation.modules.factory"
MODULE_CATALOG_PROPERTY__wishes="automation.modules.wishes"
MODULE_CATALOG_PROPERTY__provenance="automation.hardening.provenance"
MODULE_CATALOG_PROPERTY__sealing="automation.hardening.sealing"
MODULE_CATALOG_PROPERTY__require_sealed_for_unattended="automation.hardening.require-sealed-for-unattended"
MODULE_CATALOG_PROPERTY__require_signed_seal="automation.hardening.require-signed-seal"
MODULE_CATALOG_PROPERTY__seal_immutable="automation.hardening.seal-immutable"
MODULE_CATALOG_PROPERTY__webhook_signatures="automation.hardening.webhook-signatures"
MODULE_CATALOG_PROPERTY__private_network_guard="automation.hardening.private-network-guard"
MODULE_CATALOG_PROPERTY__private_network_guard_http_api="automation.hardening.private-network-guard-http-api"

# MODULE_CATALOG_RISKS
MODULE_CATALOG_RISKS__dashboard_report=""
MODULE_CATALOG_RISKS__goals=""
MODULE_CATALOG_RISKS__chains=""
MODULE_CATALOG_RISKS__assistant=""
MODULE_CATALOG_RISKS__learning=""
MODULE_CATALOG_RISKS__memory=""
MODULE_CATALOG_RISKS__audit=""
MODULE_CATALOG_RISKS__bpmn=""
MODULE_CATALOG_RISKS__integrations="credentials network"
MODULE_CATALOG_RISKS__http_api="credentials network"
MODULE_CATALOG_RISKS__webhooks="network"
MODULE_CATALOG_RISKS__remote_access="network"
MODULE_CATALOG_RISKS__terminal="host-access"
MODULE_CATALOG_RISKS__staging="host-access network"
MODULE_CATALOG_RISKS__conversion=""
MODULE_CATALOG_RISKS__dialog_import=""
MODULE_CATALOG_RISKS__onboarding_profiles=""
MODULE_CATALOG_RISKS__skills="host-access"
MODULE_CATALOG_RISKS__debug=""
MODULE_CATALOG_RISKS__factory=""
MODULE_CATALOG_RISKS__wishes=""
MODULE_CATALOG_RISKS__provenance=""
MODULE_CATALOG_RISKS__sealing=""
MODULE_CATALOG_RISKS__require_sealed_for_unattended=""
MODULE_CATALOG_RISKS__require_signed_seal=""
MODULE_CATALOG_RISKS__seal_immutable=""
MODULE_CATALOG_RISKS__webhook_signatures=""
MODULE_CATALOG_RISKS__private_network_guard=""
MODULE_CATALOG_RISKS__private_network_guard_http_api=""

# MODULE_CATALOG_REQUIRES
MODULE_CATALOG_REQUIRES__dashboard_report=""
MODULE_CATALOG_REQUIRES__goals=""
MODULE_CATALOG_REQUIRES__chains=""
MODULE_CATALOG_REQUIRES__assistant=""
MODULE_CATALOG_REQUIRES__learning=""
MODULE_CATALOG_REQUIRES__memory=""
MODULE_CATALOG_REQUIRES__audit=""
MODULE_CATALOG_REQUIRES__bpmn=""
MODULE_CATALOG_REQUIRES__integrations=""
MODULE_CATALOG_REQUIRES__http_api="integrations"
MODULE_CATALOG_REQUIRES__webhooks=""
MODULE_CATALOG_REQUIRES__remote_access=""
MODULE_CATALOG_REQUIRES__terminal=""
MODULE_CATALOG_REQUIRES__staging=""
MODULE_CATALOG_REQUIRES__conversion=""
MODULE_CATALOG_REQUIRES__dialog_import="conversion"
MODULE_CATALOG_REQUIRES__onboarding_profiles=""
MODULE_CATALOG_REQUIRES__skills=""
MODULE_CATALOG_REQUIRES__debug=""
MODULE_CATALOG_REQUIRES__factory=""
MODULE_CATALOG_REQUIRES__wishes="chains"
MODULE_CATALOG_REQUIRES__provenance=""
MODULE_CATALOG_REQUIRES__sealing="provenance"
MODULE_CATALOG_REQUIRES__require_sealed_for_unattended="sealing"
MODULE_CATALOG_REQUIRES__require_signed_seal="sealing"
MODULE_CATALOG_REQUIRES__seal_immutable="sealing"
MODULE_CATALOG_REQUIRES__webhook_signatures="webhooks"
MODULE_CATALOG_REQUIRES__private_network_guard=""
MODULE_CATALOG_REQUIRES__private_network_guard_http_api=""

# MODULE_TEXT_NAME
MODULE_TEXT_NAME__de__dashboard_report="Dashboard-Bericht"
MODULE_TEXT_NAME__de__goals="Ziele"
MODULE_TEXT_NAME__de__chains="Chains"
MODULE_TEXT_NAME__de__assistant="Assistent"
MODULE_TEXT_NAME__de__learning="Insights & Learning"
MODULE_TEXT_NAME__de__memory="Workflow-Memory"
MODULE_TEXT_NAME__de__audit="Audit Center"
MODULE_TEXT_NAME__de__bpmn="BPMN-Export/-Import"
MODULE_TEXT_NAME__de__integrations="Integrationen"
MODULE_TEXT_NAME__de__http_api="HTTP-API-Verbindung"
MODULE_TEXT_NAME__de__webhooks="Webhooks"
MODULE_TEXT_NAME__de__remote_access="Fernzugriff"
MODULE_TEXT_NAME__de__terminal="Run-Terminal"
MODULE_TEXT_NAME__de__staging="Stable- & Test-Instanzen"
MODULE_TEXT_NAME__de__conversion="Run → Workflow-Konvertierung"
MODULE_TEXT_NAME__de__dialog_import="Dialog-Import"
MODULE_TEXT_NAME__de__onboarding_profiles="Onboarding-Profile"
MODULE_TEXT_NAME__de__skills="Skills"
MODULE_TEXT_NAME__de__debug="Workflow-Debugger"
MODULE_TEXT_NAME__de__factory="Workflow-Factory"
MODULE_TEXT_NAME__de__wishes="Wünsche"
MODULE_TEXT_NAME__de__provenance="Definitions-Hash & Run-Herkunft"
MODULE_TEXT_NAME__de__sealing="Versiegelte Workflows"
MODULE_TEXT_NAME__de__require_sealed_for_unattended="Unbeaufsichtigt nur versiegelt"
MODULE_TEXT_NAME__de__require_signed_seal="Siegel müssen signiert sein"
MODULE_TEXT_NAME__de__seal_immutable="Entsiegeln gesperrt"
MODULE_TEXT_NAME__de__webhook_signatures="Webhook-Signaturen (HMAC)"
MODULE_TEXT_NAME__de__private_network_guard="Feeds: keine Abrufe ins lokale Netz"
MODULE_TEXT_NAME__de__private_network_guard_http_api="HTTP-API: keine Abrufe ins lokale Netz"
MODULE_TEXT_NAME__en__dashboard_report="Dashboard report"
MODULE_TEXT_NAME__en__goals="Goals"
MODULE_TEXT_NAME__en__chains="Chains"
MODULE_TEXT_NAME__en__assistant="Assistant"
MODULE_TEXT_NAME__en__learning="Insights & learning"
MODULE_TEXT_NAME__en__memory="Workflow memory"
MODULE_TEXT_NAME__en__audit="Audit center"
MODULE_TEXT_NAME__en__bpmn="BPMN export/import"
MODULE_TEXT_NAME__en__integrations="Integrations"
MODULE_TEXT_NAME__en__http_api="HTTP API connection"
MODULE_TEXT_NAME__en__webhooks="Webhooks"
MODULE_TEXT_NAME__en__remote_access="Remote access"
MODULE_TEXT_NAME__en__terminal="Run terminal"
MODULE_TEXT_NAME__en__staging="Stable & test instances"
MODULE_TEXT_NAME__en__conversion="Run → workflow conversion"
MODULE_TEXT_NAME__en__dialog_import="Dialog import"
MODULE_TEXT_NAME__en__onboarding_profiles="Onboarding profiles"
MODULE_TEXT_NAME__en__skills="Skills"
MODULE_TEXT_NAME__en__debug="Workflow debugger"
MODULE_TEXT_NAME__en__factory="Workflow factory"
MODULE_TEXT_NAME__en__wishes="Wishes"
MODULE_TEXT_NAME__en__provenance="Definition hash & run provenance"
MODULE_TEXT_NAME__en__sealing="Sealed workflows"
MODULE_TEXT_NAME__en__require_sealed_for_unattended="Unattended runs only when sealed"
MODULE_TEXT_NAME__en__require_signed_seal="Seals must be signed"
MODULE_TEXT_NAME__en__seal_immutable="Unsealing locked"
MODULE_TEXT_NAME__en__webhook_signatures="Webhook signatures (HMAC)"
MODULE_TEXT_NAME__en__private_network_guard="Feeds: no fetches into the local network"
MODULE_TEXT_NAME__en__private_network_guard_http_api="HTTP API: no fetches into the local network"

# MODULE_TEXT_DESC
MODULE_TEXT_DESC__de__dashboard_report="Block-Typ, mit dem ein Workflow das Dashboard-Ergebnis aus Config-Feldern füllt, plus der KI-Vorschlag für diese Felder im Editor."
MODULE_TEXT_DESC__de__goals="Ziele mit Projekten, Arbeitspaketen und Meilensteinen, die Einordnung von TODOs (Größe, Aufwand, Frage/Entscheidung), Workflows am Ziel und der Ziel-Wächter. Aus: Ziel-Seiten und Planung verschwinden, die Daten bleiben erhalten."
MODULE_TEXT_DESC__de__chains="Verkettet mehrere Workflows zu einer Pipeline, die nacheinander läuft."
MODULE_TEXT_DESC__de__assistant="Chat-Assistent und Seitenvorschläge, die Plattform-Objekte lesen und ändern können."
MODULE_TEXT_DESC__de__learning="Auswertungen zu Runs, Empfehlungen und Statistiken je Block-Typ."
MODULE_TEXT_DESC__de__memory="Wissen aus abgeschlossenen Runs wird gesammelt und in spätere Runs eingespeist."
MODULE_TEXT_DESC__de__audit="Befunde aus Audit-Workflows mit Berichten und Übernahme in TODOs."
MODULE_TEXT_DESC__de__bpmn="Exportiert Workflows als BPMN und liest MySuricates-BPMN-Exporte wieder ein."
MODULE_TEXT_DESC__de__integrations="Externe Dienste (GitHub, Jira, Trello, Google, Confluence, Taskwarrior) per OAuth, dazu Integrations-Blöcke, TODO-Tracker-Export und Kalender."
MODULE_TEXT_DESC__de__http_api="Ausgehende HTTP-Aufrufe aus Integrations-Blöcken an selbst eingetragene Dienste (Basis-URL + optionaler Auth-Header, mehrere Dienste als benannte Verbindungen)."
MODULE_TEXT_DESC__de__webhooks="Eingehende Webhook-Trigger, die Workflows von außen starten."
MODULE_TEXT_DESC__de__remote_access="Zugriff auf diese Instanz aus der Ferne über Tailscale."
MODULE_TEXT_DESC__de__terminal="Interaktives Terminal in den Container eines Runs über WebSocket."
MODULE_TEXT_DESC__de__staging="Stable- und Test-Instanz in Docker, samt Abgleich der Definitionen zwischen den Instanzen."
MODULE_TEXT_DESC__de__conversion="Macht aus einem abgeschlossenen Run einen wiederverwendbaren Workflow."
MODULE_TEXT_DESC__de__dialog_import="Importiert einen Chat-Dialog und wandelt ihn in einen Workflow um."
MODULE_TEXT_DESC__de__onboarding_profiles="Onboarding-Profile für Projekte."
MODULE_TEXT_DESC__de__skills="Skill-Pakete und der Block-Typ skill-capability."
MODULE_TEXT_DESC__de__debug="Listet Workflows, deren letzter Lauf gescheitert ist – mit Diagnose und Neustart."
MODULE_TEXT_DESC__de__factory="Erzeugt Workflows aus einer Beschreibung und repariert fehlgeschlagene selbsttätig."
MODULE_TEXT_DESC__de__wishes="Zerlegt frei formulierte Ziele in Workflows und Chains."
MODULE_TEXT_DESC__de__provenance="Jeder Run speichert einen SHA-256-Hash der Workflow-Definition, deren Version und die Auslösequelle."
MODULE_TEXT_DESC__de__sealing="Versiegelte Definitionen lehnen Änderungen ab und starten nicht, wenn sie verändert wurden."
MODULE_TEXT_DESC__de__require_sealed_for_unattended="Unbeaufsichtigte Runs (Schedules, Webhooks, Trigger, Auto-Reparatur, Wünsche und die davon gestarteten Chains oder Sub-Workflows) starten nur versiegelte Workflows."
MODULE_TEXT_DESC__de__require_signed_seal="Versiegelte Workflows brauchen eine gültige Signatur (Schlüssel außerhalb der Datenbank). Ein Siegel ohne oder mit falscher Signatur lehnt seine Runs ab."
MODULE_TEXT_DESC__de__seal_immutable="Entsiegeln ist über die Oberfläche und die API nicht mehr möglich; ein Siegel löst nur, wer die Startkonfiguration ändern kann."
MODULE_TEXT_DESC__de__webhook_signatures="Signatur-Secret je Webhook (HMAC-SHA256 im Header X-Signature); ein gespeichertes Secret gilt immer, der Schalter regelt nur Setzen und Entfernen."
MODULE_TEXT_DESC__de__private_network_guard="iCal-Feed und RSS/Atom-Feed rufen nur öffentliche Adressen ab: Ziele auf localhost, in privaten Netzen, Link-Local (samt Cloud-Metadaten 169.254.169.254), 0.0.0.0/:: oder Multicast werden abgelehnt – geprüft werden die aufgelösten IP-Adressen, auch bei jeder Weiterleitung. Ausschalten gibt lokale Feeds bewusst frei."
MODULE_TEXT_DESC__de__private_network_guard_http_api="Dieselbe Adressprüfung für die HTTP-API-Verbindung. Standardmäßig aus, weil Dienste im eigenen Netz ein häufiges Ziel der HTTP-API sind; eingeschaltet lehnt sie auch diese ab."
MODULE_TEXT_DESC__en__dashboard_report="Block type that lets a workflow fill the dashboard result from config fields, plus the editor's AI proposal for those fields."
MODULE_TEXT_DESC__en__goals="Goals with projects, work packages and milestones, TODO planning (size, effort, question/decision), workflows on a goal and the goal guard. Off: goal pages and planning disappear, the data is kept."
MODULE_TEXT_DESC__en__chains="Chains several workflows into a pipeline that runs one after another."
MODULE_TEXT_DESC__en__assistant="Chat assistant and page suggestions that can read and edit platform objects."
MODULE_TEXT_DESC__en__learning="Run insights, recommendations and statistics per block type."
MODULE_TEXT_DESC__en__memory="Knowledge collected from finished runs and injected into later runs."
MODULE_TEXT_DESC__en__audit="Findings from audit workflows, with reports and promotion to TODOs."
MODULE_TEXT_DESC__en__bpmn="Exports workflows as BPMN and reads MySuricates BPMN exports back in."
MODULE_TEXT_DESC__en__integrations="External services (GitHub, Jira, Trello, Google, Confluence, Taskwarrior) via OAuth, plus integration blocks, TODO tracker export and calendar."
MODULE_TEXT_DESC__en__http_api="Outbound HTTP calls from integration blocks to services you enter yourself (base URL + optional auth header, several services as named connections)."
MODULE_TEXT_DESC__en__webhooks="Inbound webhook triggers that start workflows from outside."
MODULE_TEXT_DESC__en__remote_access="Remote access to this instance via Tailscale."
MODULE_TEXT_DESC__en__terminal="Interactive terminal into a run's container over WebSocket."
MODULE_TEXT_DESC__en__staging="Stable and test instances in Docker, including definition sync between instances."
MODULE_TEXT_DESC__en__conversion="Turns a finished run into a reusable workflow."
MODULE_TEXT_DESC__en__dialog_import="Imports a chat dialog and converts it into a workflow."
MODULE_TEXT_DESC__en__onboarding_profiles="Onboarding profiles for projects."
MODULE_TEXT_DESC__en__skills="Skill packages and the skill-capability block type."
MODULE_TEXT_DESC__en__debug="Lists workflows whose last run failed – with diagnosis and restart."
MODULE_TEXT_DESC__en__factory="Generates workflows from a description and repairs failing ones on its own."
MODULE_TEXT_DESC__en__wishes="Decomposes free-text goals into workflows and chains."
MODULE_TEXT_DESC__en__provenance="Every run records a SHA-256 hash of the workflow definition, its version and the trigger source."
MODULE_TEXT_DESC__en__sealing="Sealed definitions reject edits and refuse to run once they have been tampered with."
MODULE_TEXT_DESC__en__require_sealed_for_unattended="Unattended runs (schedules, webhooks, triggers, auto-repair, wishes and the chains or sub-workflows they start) only start sealed workflows."
MODULE_TEXT_DESC__en__require_signed_seal="Sealed workflows need a valid signature (key outside the database). A seal without one, or with a wrong one, refuses its runs."
MODULE_TEXT_DESC__en__seal_immutable="Unsealing is no longer possible through the UI or the API; a seal is released only by someone who can change the startup configuration."
MODULE_TEXT_DESC__en__webhook_signatures="Per-webhook signing secret (HMAC-SHA256 in the X-Signature header); a stored secret is always enforced, the switch only gates setting and removing it."
MODULE_TEXT_DESC__en__private_network_guard="iCal feed and RSS/Atom feed fetch public addresses only: targets on localhost, in private networks, link-local (including cloud metadata 169.254.169.254), 0.0.0.0/:: or multicast are refused — the resolved IP addresses are checked, on every redirect too. Turning it off allows local feeds on purpose."
MODULE_TEXT_DESC__en__private_network_guard_http_api="The same address check for the HTTP API connection. Off by default, because services in your own network are a common HTTP API target; turned on, it refuses them as well."
