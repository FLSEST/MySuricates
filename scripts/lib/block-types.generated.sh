#!/usr/bin/env bash
# generiert von BlockTypesGateListTest — nicht von Hand ändern
#
# Quelle: com.automation.service.assistant.context.BlockTypesIndexSection.BLOCK_TYPES.
# Neu erzeugen:
#   cd ai-automation-backend && ./mvnw test -Dtest=BlockTypesGateListTest -Dblock.types.write=true
#
# Die Blocktypen, die eine ausgelieferte Definition verwenden darf: eine Quelle,
# zwei Seiten (Registry im Backend, Hygiene-Gate in den Skripten).
# Eingelesen von scripts/lib/hygiene-rules.sh; reine Daten, keine Funktionen.
#
# shellcheck shell=bash

HYGIENE_POOL_BLOCK_TYPES=(
  agentic
  approval
  compress
  compress-ai
  conditional
  document
  foreach
  human-input
  integration
  retry
  shell
  simple
  skill-capability
  sub-workflow
  switch
  todo-close
  todo-create
  todo-update
  while
)
