#!/usr/bin/env bash
# generiert von PoolSanitizerKeysTest — nicht von Hand ändern
#
# Quelle: com.automation.service.pool.PoolExportSanitizer (Instanz-Referenzschlüssel).
# Neu erzeugen:
#   cd ai-automation-backend && ./mvnw test -Dtest=PoolSanitizerKeysTest -Dpool.sanitizer.write=true
#
# Was der Export entfernt, beanstandet das Gate im Bestand: eine Quelle, zwei Seiten.
# Eingelesen von scripts/lib/hygiene-rules.sh; reine Daten, keine Funktionen.
#
# Verglichen wird auf beiden Seiten normalisiert (klein, ohne '_' und '-'), siehe
# PoolExportSanitizer.normalize und normalize_key in scripts/ci/pool-neutrality.sh.
# Auch eine Schreibweise, die hier nicht wortwoertlich steht (project-id), zaehlt.
#
# shellcheck shell=bash

HYGIENE_POOL_INSTANCE_KEYS=(
  "projectId" "project_id"
  "workflowId" "workflow_id"
  "runId" "run_id"
  "scheduleId" "schedule_id"
  "chainId" "chain_id"
  "executionId" "execution_id"
  "instanceId" "instance_id"
  "todoId" "todo_id"
  "sourceRunId" "source_run_id"
  "lastFixRunId" "last_fix_run_id"
)
