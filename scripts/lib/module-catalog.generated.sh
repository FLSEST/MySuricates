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
  chains
  assistant
  learning
  memory
  audit
  bpmn
  integrations
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
  webhook-signatures
)

MODULE_CATALOG_FEATURE_KEYS=(
  chains
  assistant
  learning
  memory
  audit
  bpmn
  integrations
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
  webhook-signatures
)

declare -gA MODULE_CATALOG_TIER=(
  [chains]="research"
  [assistant]="research"
  [learning]="research"
  [memory]="research"
  [audit]="research"
  [bpmn]="research"
  [integrations]="advanced"
  [webhooks]="advanced"
  [remote-access]="advanced"
  [terminal]="advanced"
  [staging]="advanced"
  [conversion]="advanced"
  [dialog-import]="advanced"
  [onboarding-profiles]="advanced"
  [skills]="advanced"
  [debug]="advanced"
  [factory]="advanced"
  [wishes]="advanced"
  [provenance]="hardening"
  [sealing]="hardening"
  [require-sealed-for-unattended]="hardening"
  [webhook-signatures]="hardening"
)

declare -gA MODULE_CATALOG_MATURITY=(
  [chains]="stable"
  [assistant]="experimental"
  [learning]="experimental"
  [memory]="experimental"
  [audit]="experimental"
  [bpmn]="experimental"
  [integrations]="stable"
  [webhooks]="stable"
  [remote-access]="stable"
  [terminal]="stable"
  [staging]="experimental"
  [conversion]="legacy"
  [dialog-import]="legacy"
  [onboarding-profiles]="legacy"
  [skills]="legacy"
  [debug]="legacy"
  [factory]="experimental"
  [wishes]="experimental"
  [provenance]="experimental"
  [sealing]="experimental"
  [require-sealed-for-unattended]="experimental"
  [webhook-signatures]="experimental"
)

declare -gA MODULE_CATALOG_DEFAULT=(
  [chains]="false"
  [assistant]="false"
  [learning]="false"
  [memory]="false"
  [audit]="false"
  [bpmn]="false"
  [integrations]="false"
  [webhooks]="false"
  [remote-access]="false"
  [terminal]="false"
  [staging]="false"
  [conversion]="false"
  [dialog-import]="false"
  [onboarding-profiles]="false"
  [skills]="false"
  [debug]="false"
  [factory]="false"
  [wishes]="false"
  [provenance]="true"
  [sealing]="false"
  [require-sealed-for-unattended]="false"
  [webhook-signatures]="false"
)

declare -gA MODULE_CATALOG_PROPERTY=(
  [chains]="automation.modules.chains"
  [assistant]="automation.modules.assistant"
  [learning]="automation.modules.learning"
  [memory]="automation.modules.memory"
  [audit]="automation.modules.audit"
  [bpmn]="automation.modules.bpmn"
  [integrations]="automation.modules.integrations"
  [webhooks]="automation.modules.webhooks"
  [remote-access]="automation.modules.remote-access"
  [terminal]="automation.modules.terminal"
  [staging]="automation.modules.staging"
  [conversion]="automation.modules.conversion"
  [dialog-import]="automation.modules.dialog-import"
  [onboarding-profiles]="automation.modules.onboarding-profiles"
  [skills]="automation.modules.skills"
  [debug]="automation.modules.debug"
  [factory]="automation.modules.factory"
  [wishes]="automation.modules.wishes"
  [provenance]="automation.hardening.provenance"
  [sealing]="automation.hardening.sealing"
  [require-sealed-for-unattended]="automation.hardening.require-sealed-for-unattended"
  [webhook-signatures]="automation.hardening.webhook-signatures"
)

declare -gA MODULE_CATALOG_RISKS=(
  [chains]=""
  [assistant]=""
  [learning]=""
  [memory]=""
  [audit]=""
  [bpmn]=""
  [integrations]="credentials network"
  [webhooks]="network"
  [remote-access]="network"
  [terminal]="host-access"
  [staging]="host-access network"
  [conversion]=""
  [dialog-import]=""
  [onboarding-profiles]=""
  [skills]="host-access"
  [debug]=""
  [factory]=""
  [wishes]=""
  [provenance]=""
  [sealing]=""
  [require-sealed-for-unattended]=""
  [webhook-signatures]=""
)

declare -gA MODULE_CATALOG_REQUIRES=(
  [chains]=""
  [assistant]=""
  [learning]=""
  [memory]=""
  [audit]=""
  [bpmn]=""
  [integrations]=""
  [webhooks]=""
  [remote-access]=""
  [terminal]=""
  [staging]=""
  [conversion]=""
  [dialog-import]="conversion"
  [onboarding-profiles]=""
  [skills]=""
  [debug]=""
  [factory]=""
  [wishes]="chains"
  [provenance]=""
  [sealing]="provenance"
  [require-sealed-for-unattended]="sealing"
  [webhook-signatures]="webhooks"
)

declare -gA MODULE_TEXT_NAME=(
  # de
  [de:chains]="Chains"
  [de:assistant]="Assistent"
  [de:learning]="Insights & Learning"
  [de:memory]="Workflow-Memory"
  [de:audit]="Audit Center"
  [de:bpmn]="BPMN-Export/-Import"
  [de:integrations]="Integrationen"
  [de:webhooks]="Webhooks"
  [de:remote-access]="Fernzugriff"
  [de:terminal]="Run-Terminal"
  [de:staging]="Stable- & Test-Instanzen"
  [de:conversion]="Run → Workflow-Konvertierung"
  [de:dialog-import]="Dialog-Import"
  [de:onboarding-profiles]="Onboarding-Profile"
  [de:skills]="Skills"
  [de:debug]="Workflow-Debugger"
  [de:factory]="Workflow-Factory"
  [de:wishes]="Wünsche"
  [de:provenance]="Definitions-Hash & Run-Herkunft"
  [de:sealing]="Versiegelte Workflows"
  [de:require-sealed-for-unattended]="Unbeaufsichtigt nur versiegelt"
  [de:webhook-signatures]="Webhook-Signaturen (HMAC)"
  # en
  [en:chains]="Chains"
  [en:assistant]="Assistant"
  [en:learning]="Insights & learning"
  [en:memory]="Workflow memory"
  [en:audit]="Audit center"
  [en:bpmn]="BPMN export/import"
  [en:integrations]="Integrations"
  [en:webhooks]="Webhooks"
  [en:remote-access]="Remote access"
  [en:terminal]="Run terminal"
  [en:staging]="Stable & test instances"
  [en:conversion]="Run → workflow conversion"
  [en:dialog-import]="Dialog import"
  [en:onboarding-profiles]="Onboarding profiles"
  [en:skills]="Skills"
  [en:debug]="Workflow debugger"
  [en:factory]="Workflow factory"
  [en:wishes]="Wishes"
  [en:provenance]="Definition hash & run provenance"
  [en:sealing]="Sealed workflows"
  [en:require-sealed-for-unattended]="Unattended runs only when sealed"
  [en:webhook-signatures]="Webhook signatures (HMAC)"
)

declare -gA MODULE_TEXT_DESC=(
  # de
  [de:chains]="Verkettet mehrere Workflows zu einer Pipeline, die nacheinander läuft."
  [de:assistant]="Chat-Assistent und Seitenvorschläge, die Plattform-Objekte lesen und ändern können."
  [de:learning]="Auswertungen zu Runs, Empfehlungen und Statistiken je Block-Typ."
  [de:memory]="Wissen aus abgeschlossenen Runs wird gesammelt und in spätere Runs eingespeist."
  [de:audit]="Befunde aus Audit-Workflows mit Berichten und Übernahme in TODOs."
  [de:bpmn]="Exportiert Workflows als BPMN und liest MySuricates-BPMN-Exporte wieder ein."
  [de:integrations]="Externe Dienste (GitHub, Jira, Trello, Google, Confluence, Taskwarrior) per OAuth, dazu Integrations-Blöcke, TODO-Tracker-Export und Kalender."
  [de:webhooks]="Eingehende Webhook-Trigger, die Workflows von außen starten."
  [de:remote-access]="Zugriff auf diese Instanz aus der Ferne über Tailscale."
  [de:terminal]="Interaktives Terminal in den Container eines Runs über WebSocket."
  [de:staging]="Stable- und Test-Instanz in Docker, samt Abgleich der Definitionen zwischen den Instanzen."
  [de:conversion]="Macht aus einem abgeschlossenen Run einen wiederverwendbaren Workflow."
  [de:dialog-import]="Importiert einen Chat-Dialog und wandelt ihn in einen Workflow um."
  [de:onboarding-profiles]="Onboarding-Profile für Projekte."
  [de:skills]="Skill-Pakete und der Block-Typ skill-capability."
  [de:debug]="Führt einen Workflow Schritt für Schritt aus, zum Nachvollziehen."
  [de:factory]="Erzeugt Workflows aus einer Beschreibung und repariert fehlgeschlagene selbsttätig."
  [de:wishes]="Zerlegt frei formulierte Ziele in Workflows und Chains."
  [de:provenance]="Jeder Run speichert einen SHA-256-Hash der Workflow-Definition, deren Version und die Auslösequelle."
  [de:sealing]="Versiegelte Definitionen lehnen Änderungen ab und starten nicht, wenn sie verändert wurden."
  [de:require-sealed-for-unattended]="Unbeaufsichtigte Runs (Schedules, Webhooks, Trigger, Auto-Reparatur, Wünsche und die davon gestarteten Chains oder Sub-Workflows) starten nur versiegelte Workflows."
  [de:webhook-signatures]="Signatur-Secret je Webhook (HMAC-SHA256 im Header X-Signature); ein gespeichertes Secret gilt immer, der Schalter regelt nur Setzen und Entfernen."
  # en
  [en:chains]="Chains several workflows into a pipeline that runs one after another."
  [en:assistant]="Chat assistant and page suggestions that can read and edit platform objects."
  [en:learning]="Run insights, recommendations and statistics per block type."
  [en:memory]="Knowledge collected from finished runs and injected into later runs."
  [en:audit]="Findings from audit workflows, with reports and promotion to TODOs."
  [en:bpmn]="Exports workflows as BPMN and reads MySuricates BPMN exports back in."
  [en:integrations]="External services (GitHub, Jira, Trello, Google, Confluence, Taskwarrior) via OAuth, plus integration blocks, TODO tracker export and calendar."
  [en:webhooks]="Inbound webhook triggers that start workflows from outside."
  [en:remote-access]="Remote access to this instance via Tailscale."
  [en:terminal]="Interactive terminal into a run's container over WebSocket."
  [en:staging]="Stable and test instances in Docker, including definition sync between instances."
  [en:conversion]="Turns a finished run into a reusable workflow."
  [en:dialog-import]="Imports a chat dialog and converts it into a workflow."
  [en:onboarding-profiles]="Onboarding profiles for projects."
  [en:skills]="Skill packages and the skill-capability block type."
  [en:debug]="Runs a workflow step by step so you can follow what happens."
  [en:factory]="Generates workflows from a description and repairs failing ones on its own."
  [en:wishes]="Decomposes free-text goals into workflows and chains."
  [en:provenance]="Every run records a SHA-256 hash of the workflow definition, its version and the trigger source."
  [en:sealing]="Sealed definitions reject edits and refuse to run once they have been tampered with."
  [en:require-sealed-for-unattended]="Unattended runs (schedules, webhooks, triggers, auto-repair, wishes and the chains or sub-workflows they start) only start sealed workflows."
  [en:webhook-signatures]="Per-webhook signing secret (HMAC-SHA256 in the X-Signature header); a stored secret is always enforced, the switch only gates setting and removing it."
)
