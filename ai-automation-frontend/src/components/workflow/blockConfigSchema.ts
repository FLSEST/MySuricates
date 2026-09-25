import { AI_BLOCK_TYPES, type BlockType } from '../../types';
import blockDefaultConfig from '../../data/blockDefaultConfig.json';

/**
 * Describes the `config` object of every block type as a JSON schema, so the Monaco config editor can
 * suggest keys and values (Ctrl+Space), explain them on hover and flag typos as warnings.
 *
 * Source of the texts: docs/WORKFLOW-GUIDE.md §2 „Block-Typ-Referenz". The texts are domain reference,
 * not UI labels, so they live here as {de, en} instead of in the i18n files. Unknown keys only warn —
 * the backend ignores them as well — and `blockConfigSchema.test.ts` checks the shipped templates
 * against this file, so a key the backend reads but this file lacks shows up there first.
 */

export type SchemaLocale = 'de' | 'en';
type Text = { de: string; en: string };

interface Field {
  type: 'string' | 'integer' | 'boolean' | 'array' | 'object' | Array<'string' | 'array' | 'null'>;
  doc: Text;
  /** Allowed values with an explanation each (shown next to the suggestion). */
  values?: Array<[value: string, doc: Text]>;
  default?: unknown;
  minimum?: number;
  items?: Record<string, unknown>;
  /** Legacy value still accepted, but not suggested. */
  legacy?: string;
}

interface BlockSpec {
  required?: string[];
  fields: Record<string, Field>;
}

const s = (de: string, en: string): Text => ({ de, en });
const STRING_LIST = { type: 'string' };

// ---- keys several block types share -------------------------------------------------------------

const ON_FAILURE: Field = {
  type: 'string',
  default: 'stop',
  doc: s(
    'Was passiert, wenn dieser Block fehlschlägt (Status FAILED).',
    'What happens when this block fails (status FAILED).',
  ),
  values: [
    ['stop', s('Workflow beenden, der Run wird FAILED (Standard).', 'Stop the workflow, the run becomes FAILED (default).')],
    ['continue', s(
      'Mit dem nächsten Block weitermachen. Der Block selbst bleibt FAILED.',
      'Carry on with the next block. The block itself stays FAILED.',
    )],
  ],
};

/** Keys every block accepts besides `on_failure`: notes for people, and the run workspace policy. */
const COMMON_FIELDS: Record<string, Field> = {
  label: { type: 'string', doc: s('Anzeigename des Schritts, nur zur Beschreibung. Bei der Ausführung ohne Wirkung.', 'Display label of the step, description only. No effect on execution.') },
  description: { type: 'string', doc: s('Notiz zum Schritt, nur zur Beschreibung. Bei der Ausführung ohne Wirkung.', 'Note on the step, description only. No effect on execution.') },
  workspace: {
    type: 'object',
    doc: s(
      'Arbeitsverzeichnis des Runs — wirkt nur am **ersten** Block. `{"mode": "isolated"}` lässt den Run in einem eigenen Git-Klon laufen '
        + '(weitere Schlüssel: `source`, `source_path`, `branch`, `install_npm`, `additional_sources`).',
      'Working directory of the run — only honoured on the **first** block. `{"mode": "isolated"}` runs it in its own Git clone '
        + '(more keys: `source`, `source_path`, `branch`, `install_npm`, `additional_sources`).',
    ),
  },
};

const SHELL_ESCAPE: Field = {
  type: 'boolean',
  default: false,
  doc: s(
    'Alle `{{ }}`-Platzhalter POSIX-sicher in Single-Quotes setzen. Platzhalter dann **ohne** eigene Quotes schreiben.',
    'Put every `{{ }}` placeholder in POSIX-safe single quotes. Then write placeholders **without** your own quotes.',
  ),
};

const timeout = (fallback: number | null): Field => ({
  type: 'integer',
  minimum: 1,
  ...(fallback != null ? { default: fallback } : {}),
  doc: fallback != null
    ? s(`Timeout in Sekunden (Standard ${fallback}).`, `Timeout in seconds (default ${fallback}).`)
    : s(
      'Timeout in Sekunden für den KI-Aufruf. Ohne Angabe gilt `automation.host-claude.timeout-seconds`.',
      'Timeout in seconds for the AI call. Unset ⇒ `automation.host-claude.timeout-seconds` applies.',
    ),
});

const AI_FIELDS: Record<string, Field> = {
  provider: {
    type: 'string',
    doc: s(
      'KI-Anbieter nur für diesen Block. Vorrang: Block > Workflow > System-Standard.',
      'AI provider for this block only. Precedence: block > workflow > system default.',
    ),
    values: [
      ['CLAUDE', s('Claude CLI', 'Claude CLI')],
      ['PI', s('pi CLI (Modell als `<backend>/<model>`)', 'pi CLI (model as `<backend>/<model>`)')],
      ['CODEX', s('Codex CLI (nur lesend)', 'Codex CLI (read-only)')],
    ],
  },
  model: {
    type: 'string',
    doc: s(
      'Modell nur für diesen Block: Alias `opus`/`sonnet`/`haiku`/`fable`, eine konkrete ID oder bei pi `<backend>/<model>`. '
        + 'Setzt der Block einen anderen `provider` als der Workflow, wird das Workflow-Modell nicht übernommen.',
      'Model for this block only: alias `opus`/`sonnet`/`haiku`/`fable`, a concrete id, or `<backend>/<model>` for pi. '
        + 'If the block sets a different `provider` than the workflow, the workflow model is not inherited.',
    ),
  },
  effort: {
    type: 'string',
    doc: s(
      'Denkaufwand nur für diesen Block. Ohne Angabe gilt der Workflow- bzw. Anbieter-Standard. Codex ignoriert ihn.',
      'Reasoning effort for this block only. Unset ⇒ workflow or provider default. Codex ignores it.',
    ),
    values: [
      ['low', s('Wenig Denkaufwand, schnell und günstig.', 'Little reasoning, fast and cheap.')],
      ['medium', s('Mittlerer Denkaufwand.', 'Medium reasoning.')],
      ['high', s('Hoher Denkaufwand.', 'High reasoning.')],
      ['xhigh', s('Sehr hoher Denkaufwand, am teuersten.', 'Very high reasoning, most expensive.')],
    ],
  },
};

const EXECUTION_MODE: Field = {
  type: 'string',
  default: 'ai',
  legacy: 'claude',
  doc: s(
    'Wie Bedingung und Befehl laufen: als KI-Prompt oder als Shell-Befehl.',
    'How condition and command run: as an AI prompt or as a shell command.',
  ),
  values: [
    ['ai', s('Als KI-Prompt über den Anbieter aus `provider` (Standard).', 'As an AI prompt via the provider from `provider` (default).')],
    ['shell', s('Als Shell-Befehl. Kein KI-Aufruf, keine Tokens.', 'As a shell command. No AI call, no tokens.')],
  ],
};

const VARIABLE_NAME = (fallback: string | null): Field => ({
  type: 'string',
  ...(fallback ? { default: fallback } : {}),
  doc: s(
    `Kontext-Variable, in die das Ergebnis geschrieben wird${fallback ? ` (Standard \`${fallback}\`)` : ''}. Danach als \`{{ name }}\` nutzbar.`,
    `Context variable that receives the result${fallback ? ` (default \`${fallback}\`)` : ''}. Usable afterwards as \`{{ name }}\`.`,
  ),
});

// ---- per block type -----------------------------------------------------------------------------

const SPECS: Record<BlockType, BlockSpec> = {
  simple: {
    required: ['command'],
    fields: {
      command: { type: 'string', doc: s('Der Prompt, den die KI bekommt. Platzhalter wie `{{ previous_output }}` werden aufgelöst.', 'The prompt sent to the AI. Placeholders like `{{ previous_output }}` are resolved.') },
      timeout_seconds: timeout(null),
    },
  },
  shell: {
    required: ['command'],
    fields: {
      command: { type: 'string', doc: s('Shell-Befehl(e). Tipp: mit `set -euo pipefail` beginnen.', 'Shell command(s). Tip: start with `set -euo pipefail`.') },
      timeout_seconds: timeout(300),
      shell_escape_variables: SHELL_ESCAPE,
      container_image: { type: 'string', doc: s('Docker-Image, in dem der Befehl läuft. Ohne Angabe läuft er auf dem Host.', 'Docker image to run the command in. Unset ⇒ runs on the host.') },
      mount_docker_socket: { type: 'boolean', default: false, doc: s('Docker-Socket in den Container mounten (nur mit `container_image`).', 'Mount the Docker socket into the container (only with `container_image`).') },
      mounts: { type: 'array', items: STRING_LIST, doc: s('Zusätzliche Volumes `host:container[:ro|rw]` (nur mit `container_image`).', 'Extra volumes `host:container[:ro|rw]` (only with `container_image`).') },
      checked_command: { type: 'boolean', default: false, doc: s('Leitet aus dem Exit-Code ein Gate ab (`gate_status`) und stellt `set -e -o pipefail` voran.', 'Derives a gate (`gate_status`) from the exit code and prepends `set -e -o pipefail`.') },
      check_category: { type: 'string', default: 'build-check', doc: s('Gate-Kategorie für `checked_command`, falls das Skript selbst keine `gate_category` ausgibt.', 'Gate category for `checked_command` if the script prints no `gate_category` itself.') },
    },
  },
  agentic: {
    required: ['goal'],
    fields: {
      goal: { type: 'string', doc: s('Was der Agent erreichen soll.', 'What the agent should achieve.') },
      success_criteria: { type: 'string', doc: s('Woran der Agent erkennt, dass er fertig ist.', 'How the agent recognises that it is done.') },
      max_iterations: { type: 'integer', minimum: 1, default: 10, doc: s('Harte Obergrenze für Reason/Act-Runden (Standard 10).', 'Hard cap on reason/act rounds (default 10).') },
      iteration_timeout_seconds: { type: 'integer', minimum: 1, default: 300, doc: s('Timeout pro Runde in Sekunden (Standard 300).', 'Timeout per round in seconds (default 300).') },
      total_timeout_seconds: { type: 'integer', minimum: 1, default: 1800, doc: s('Timeout für den ganzen Block in Sekunden (Standard 1800).', 'Timeout for the whole block in seconds (default 1800).') },
      max_total_tokens: { type: 'integer', minimum: 1, default: 200000, doc: s('Token-Budget über alle Runden, Input + Output (Standard 200 000).', 'Token budget across all rounds, input + output (default 200,000).') },
      max_repeated_actions: { type: 'integer', minimum: 1, default: 3, doc: s('Abbruch, wenn dieselbe Aktion so oft hintereinander kommt.', 'Stop when the same action repeats this many times.') },
      require_approval: { type: 'boolean', default: false, doc: s('Vor jeder Aktion eine Freigabe einholen.', 'Ask for approval before every action.') },
      timeout_seconds: timeout(null),
    },
  },
  retry: {
    required: ['command'],
    fields: {
      command: { type: 'string', doc: s('Befehl, der bei Fehler wiederholt wird (Prompt oder Shell, je nach `execution_mode`).', 'Command retried on failure (prompt or shell, depending on `execution_mode`).') },
      max_retries: { type: 'integer', minimum: 0, default: 3, doc: s('Maximale Zahl der Wiederholungen.', 'Maximum number of retries.') },
      delay: { type: 'integer', minimum: 0, default: 5, doc: s('Pause zwischen den Versuchen in Sekunden.', 'Pause between attempts in seconds.') },
      exponential_backoff: { type: 'boolean', default: false, doc: s('Pause bei jedem Versuch verdoppeln.', 'Double the pause on every attempt.') },
      execution_mode: EXECUTION_MODE,
      shell_escape_variables: SHELL_ESCAPE,
      timeout_seconds: timeout(300),
    },
  },
  conditional: {
    required: ['condition'],
    fields: {
      condition: { type: 'string', doc: s('Bedingung. Shell: Exit-Code 0 = wahr. KI: Antwort TRUE/FALSE.', 'Condition. Shell: exit code 0 = true. AI: answer TRUE/FALSE.') },
      then_command: { type: 'string', doc: s('Befehl, wenn die Bedingung wahr ist.', 'Command when the condition is true.') },
      else_command: { type: 'string', doc: s('Befehl, wenn die Bedingung falsch ist.', 'Command when the condition is false.') },
      execution_mode: EXECUTION_MODE,
      shell_escape_variables: SHELL_ESCAPE,
      timeout_seconds: timeout(300),
    },
  },
  switch: {
    required: ['expression', 'cases'],
    fields: {
      expression: { type: 'string', doc: s('Ausdruck, dessen Wert mit den Cases verglichen wird (`${var}` oder `{{ var }}`).', 'Expression whose value is matched against the cases (`${var}` or `{{ var }}`).') },
      cases: {
        type: 'array',
        doc: s('Fälle mit `value` (Alternativen mit `|`, z. B. `"YELLOW|RED"`) und `blocks`.', 'Cases with `value` (alternatives with `|`, e.g. `"YELLOW|RED"`) and `blocks`.'),
        items: { $ref: '#/definitions/switchCase' },
      },
      default: { type: 'array', items: { $ref: '#/definitions/nestedBlock' }, doc: s('Blöcke, wenn kein Case passt.', 'Blocks when no case matches.') },
    },
  },
  foreach: {
    required: ['command'],
    fields: {
      command: { type: 'string', doc: s('Befehl pro Item. Das Item steht als `{{ <variable_name> }}` bereit.', 'Command per item. The item is available as `{{ <variable_name> }}`.') },
      variable_name: { type: 'string', default: 'item', doc: s('Name der Schleifenvariable (Standard `item`).', 'Name of the loop variable (default `item`).') },
      items_source: {
        type: 'string', default: 'list',
        doc: s('Woher die Items kommen.', 'Where the items come from.'),
        values: [
          ['list', s('Aus `items` (Standard).', 'From `items` (default).')],
          ['command', s('Aus der Ausgabe von `items_command`, ein Item pro Zeile.', 'From the output of `items_command`, one item per line.')],
        ],
      },
      items_command: { type: 'string', doc: s('Shell-Befehl, der die Items ausgibt (eine Zeile = ein Item). Läuft immer als Shell.', 'Shell command printing the items (one line = one item). Always runs as shell.') },
      items: { type: 'array', doc: s('Feste Liste von Items.', 'Fixed list of items.') },
      execution_mode: EXECUTION_MODE,
      shell_escape_variables: SHELL_ESCAPE,
      timeout_seconds: timeout(300),
      continue_on_error: { type: 'boolean', default: false, doc: s('Fehlgeschlagene Items lassen den Block nicht scheitern, er endet COMPLETED.', 'Failed items do not fail the block, it ends COMPLETED.') },
    },
  },
  while: {
    required: ['command'],
    fields: {
      command: { type: 'string', doc: s('Befehl pro Durchlauf.', 'Command per iteration.') },
      condition_command: { type: 'string', doc: s('Bedingung vor jedem Durchlauf. Shell: Exit 0 = weiter. KI: "false"/"0"/leer = stopp.', 'Condition before every iteration. Shell: exit 0 = continue. AI: "false"/"0"/empty = stop.') },
      max_iterations: { type: 'integer', minimum: 1, default: 100, doc: s('Maximale Zahl der Durchläufe.', 'Maximum number of iterations.') },
      execution_mode: EXECUTION_MODE,
      shell_escape_variables: SHELL_ESCAPE,
      timeout_seconds: timeout(300),
    },
  },
  approval: {
    fields: {
      question: { type: 'string', default: 'Approval required', doc: s('Freigabe-Frage (Markdown, Platzhalter werden aufgelöst).', 'Approval question (Markdown, placeholders are resolved).') },
      options: { type: 'array', items: STRING_LIST, default: ['Yes', 'Yes always', 'No'], doc: s('Erlaubte Antworten.', 'Allowed answers.') },
      reject_options: { type: 'array', items: STRING_LIST, default: ['No', 'Nein'], doc: s('Antworten, die als Ablehnung gelten und `on_no` auslösen.', 'Answers that count as rejection and trigger `on_no`.') },
      variable_name: VARIABLE_NAME('approval_answer'),
      timeout_minutes: { type: 'integer', minimum: 1, default: 30, doc: s('Wartezeit in Minuten.', 'Waiting time in minutes.') },
      mode: {
        type: 'string', default: 'ask',
        doc: s('Interaktiv fragen oder automatisch antworten.', 'Ask interactively or answer automatically.'),
        values: [
          ['ask', s('Wartet auf eine Antwort im UI (Standard).', 'Waits for an answer in the UI (default).')],
          ['auto', s('Antwortet sofort mit `auto_answer`.', 'Answers immediately with `auto_answer`.')],
        ],
      },
      auto_answer: { type: ['string', 'null'], doc: s('Antwort im Modus `auto`.', 'Answer in mode `auto`.') },
      on_no: {
        type: 'string', default: 'continue',
        doc: s('Verhalten bei Ablehnung (siehe `reject_options`).', 'Behaviour on rejection (see `reject_options`).'),
        values: [
          ['continue', s('Weiter, Block COMPLETED (Standard).', 'Continue, block COMPLETED (default).')],
          ['fail', s('Block FAILED.', 'Block FAILED.')],
          ['skip', s('Block SKIPPED.', 'Block SKIPPED.')],
        ],
      },
    },
  },
  'human-input': {
    fields: {
      prompt: { type: 'string', default: 'Please provide input:', doc: s('Frage an den Benutzer.', 'Question to the user.') },
      input_type: {
        type: 'string', default: 'text',
        doc: s('Art der Eingabe.', 'Kind of input.'),
        values: [
          ['text', s('Freitext (Standard).', 'Free text (default).')],
          ['textarea', s('Mehrzeiliger Text.', 'Multi-line text.')],
          ['number', s('Zahlenfeld.', 'Number field.')],
          ['select', s('Auswahl aus `options`.', 'Choice from `options`.')],
          ['checkbox', s('Kontrollkästchen, Antwort `"true"` oder `"false"`.', 'Checkbox, answer `"true"` or `"false"`.')],
          ['date', s('Datum, Antwort als `JJJJ-MM-TT`.', 'Date, answer as `YYYY-MM-DD`.')],
        ],
      },
      variable_name: VARIABLE_NAME('user_input'),
      options: { type: 'array', items: STRING_LIST, doc: s('Auswahlmöglichkeiten für `select`.', 'Choices for `select`.') },
      default_value: { type: 'string', doc: s('Standardwert, u. a. bei Timeout.', 'Default value, e.g. on timeout.') },
      required: { type: 'boolean', default: true, doc: s('Eingabe erforderlich.', 'Input required.') },
      validation_pattern: { type: 'string', doc: s('Regex, gegen den die Eingabe geprüft wird.', 'Regex the input is validated against.') },
      timeout_minutes: { type: 'integer', minimum: 1, default: 30, doc: s('Wartezeit in Minuten.', 'Waiting time in minutes.') },
      use_default_on_timeout: { type: 'boolean', default: true, doc: s('Bei Timeout `default_value` nehmen statt zu scheitern.', 'On timeout use `default_value` instead of failing.') },
      fields: { type: 'array', doc: s('Formular mit mehreren Feldern (`variable_name`, `label`, `input_type`, `required`, …).', 'Form with several fields (`variable_name`, `label`, `input_type`, `required`, …).') },
    },
  },
  'sub-workflow': {
    required: ['workflow_id'],
    fields: {
      workflow_id: { type: 'string', doc: s('ID des Workflows, der als Schritt läuft (max. 5 Ebenen tief).', 'ID of the workflow to run as a step (max. 5 levels deep).') },
    },
  },
  'skill-capability': {
    required: ['capability'],
    fields: {
      capability: { type: 'string', doc: s('Name der Skill-Fähigkeit, z. B. `review_code`. Ihr Skill muss in den `skillIds` des Blocks stehen (neben `config`), Workflow-Skills zählen hier nicht.', 'Name of the skill capability, e.g. `review_code`. Its skill must be in the block\'s `skillIds` (next to `config`); workflow skills do not count here.') },
      input: { type: 'string', doc: s('Eingabe für die Fähigkeit, z. B. `{{ previous_output }}`.', 'Input for the capability, e.g. `{{ previous_output }}`.') },
      params: { type: 'object', doc: s('Zusätzliche Parameter.', 'Additional parameters.') },
      allow_writes: { type: 'boolean', default: false, doc: s('Schreibzugriff aufs Repo erlauben. Pflicht für Fähigkeiten mit `writes: true`.', 'Allow writing to the repo. Required for capabilities with `writes: true`.') },
      timeout_seconds: timeout(null),
    },
  },
  integration: {
    required: ['provider', 'action'],
    fields: {
      provider: { type: 'string', doc: s('Integrations-Anbieter, z. B. `google-calendar` (nicht der KI-Anbieter).', 'Integration provider, e.g. `google-calendar` (not the AI provider).') },
      action: { type: 'string', doc: s('Aktion des Anbieters.', 'Action of the provider.') },
      params: { type: 'object', doc: s('Parameter der Aktion.', 'Parameters of the action.') },
      connection: { type: 'string', default: 'default', doc: s('Benannte Verbindung des Anbieters, z. B. `arbeit` — wenn mehrere Konten oder Dienste desselben Anbieters eingerichtet sind (Seite Integrationen). Ohne Angabe gilt `default`.', 'Named connection of the provider, e.g. `work` — when several accounts or services of the same provider are set up (Integrations page). Without it, `default` is used.') },
    },
  },
  document: {
    required: ['file_path'],
    fields: {
      file_path: { type: 'string', doc: s('Datei (PDF, DOCX, XLSX, HTML, TXT, …), absolut oder relativ zum Projekt.', 'File (PDF, DOCX, XLSX, HTML, TXT, …), absolute or relative to the project.') },
      variable_name: VARIABLE_NAME(null),
      fail_on_empty: { type: 'boolean', default: false, doc: s('Scheitern, wenn weniger als `min_characters` Text herauskommt.', 'Fail if less than `min_characters` of text comes out.') },
      min_characters: { type: 'integer', minimum: 0, default: 1, doc: s('Schwelle für `fail_on_empty`.', 'Threshold for `fail_on_empty`.') },
    },
  },
  'todo-create': {
    required: ['title'],
    fields: {
      title: { type: 'string', doc: s('Titel des TODOs.', 'Title of the TODO.') },
      description: { type: 'string', doc: s('Beschreibung (Markdown).', 'Description (Markdown).') },
      priority: {
        type: 'string', default: 'MEDIUM', doc: s('Priorität.', 'Priority.'),
        values: [
          ['CRITICAL', s('Kritisch', 'Critical')], ['HIGH', s('Hoch, blockierend', 'High, blocking')],
          ['MEDIUM', s('Mittel (Standard)', 'Medium (default)')], ['LOW', s('Niedrig', 'Low')],
        ],
      },
      tags: { type: ['array', 'string'], items: STRING_LIST, doc: s('Tags als Liste oder kommagetrennt.', 'Tags as a list or comma-separated.') },
      project_id: { type: 'string', doc: s('Ziel-Projekt. Standard: Projekt des Workflows.', 'Target project. Default: the workflow project.') },
      source: {
        type: 'string', default: 'WORKFLOW', doc: s('Herkunft des TODOs.', 'Origin of the TODO.'),
        values: [['WORKFLOW', s('Workflow (Standard)', 'Workflow (default)')], ['AI', s('KI', 'AI')], ['USER', s('Benutzer', 'User')]],
      },
      source_agent: { type: 'string', default: 'workflow', doc: s('Bezeichnung des erzeugenden Agenten.', 'Label of the creating agent.') },
      variable_name: VARIABLE_NAME('todo_id'),
    },
  },
  'todo-update': {
    fields: {
      id: { type: 'string', doc: s('ID des TODOs, z. B. `{{ todo_id }}`. Entweder `id` oder `updates`.', 'ID of the TODO, e.g. `{{ todo_id }}`. Either `id` or `updates`.') },
      title: { type: 'string', doc: s('Neuer Titel (nur gesetzte, nicht leere Felder werden geändert).', 'New title (only set, non-empty fields are changed).') },
      description: { type: 'string', doc: s('Neue Beschreibung.', 'New description.') },
      priority: { type: 'string', doc: s('Neue Priorität.', 'New priority.'), values: [['CRITICAL', s('Kritisch', 'Critical')], ['HIGH', s('Hoch', 'High')], ['MEDIUM', s('Mittel', 'Medium')], ['LOW', s('Niedrig', 'Low')]] },
      tags: { type: ['array', 'string'], items: STRING_LIST, doc: s('Neue Tags.', 'New tags.') },
      updates: { type: ['array', 'string'], doc: s('Viele TODOs des Lauf-Projekts auf einmal: JSON-Liste, meist `{{ block.<shell>.output }}` (ist die ganze Ausgabe kein JSON, gilt ihre letzte Zeile). Je Eintrag `id`, optional `status` (OPEN/IN_PROGRESS/DONE/BACKLOG), `add_tags`, `remove_tags`, `note`, `require_tags`, `require_status`. Höchstens 50; ein unpassender Eintrag wird mit Grund übersprungen.', 'Many TODOs of the run project at once: a JSON list, usually `{{ block.<shell>.output }}` (if the whole output is no JSON, its last line counts). Per entry `id`, optional `status` (OPEN/IN_PROGRESS/DONE/BACKLOG), `add_tags`, `remove_tags`, `note`, `require_tags`, `require_status`. At most 50; an entry that does not fit is skipped with a reason.') },
    },
  },
  'todo-close': {
    required: ['id'],
    fields: {
      id: { type: 'string', doc: s('ID des TODOs, das auf DONE gesetzt wird, z. B. `{{ todo_id }}`.', 'ID of the TODO set to DONE, e.g. `{{ todo_id }}`.') },
    },
  },
  compress: {
    required: ['source', 'stages'],
    fields: {
      source: { type: 'string', doc: s('Name eines früheren Blocks oder `"previous"`.', 'Name of an earlier block or `"previous"`.') },
      stages: { type: 'array', items: { $ref: '#/definitions/compressStage' }, doc: s('Stufen, die der Reihe nach laufen. JSON-Stufen vor Textstufen.', 'Stages run in order. JSON stages before text stages.') },
      max_chars: { type: 'integer', minimum: 1, default: 20000, doc: s('Harte Obergrenze inkl. Kopfzeilen.', 'Hard limit including header lines.') },
      timeout_seconds: timeout(30),
    },
  },
  'compress-ai': {
    required: ['source'],
    fields: {
      source: { type: 'string', doc: s('Name eines früheren Blocks oder `"previous"`.', 'Name of an earlier block or `"previous"`.') },
      instruction: { type: 'string', doc: s('Anweisung zur Verdichtung (Platzhalter werden **nicht** aufgelöst).', 'Compression instruction (placeholders are **not** resolved).') },
      max_chars: { type: 'integer', minimum: 500, default: 4000, doc: s('Zielgröße, mindestens 500.', 'Target size, at least 500.') },
      timeout_seconds: timeout(null),
    },
  },
  'dashboard-report': {
    required: ['status'],
    fields: {
      status: { type: 'string', doc: s('Ampel des Runs (Platzhalter erlaubt, z. B. `{{ block.<name>.output }}` eines Blocks, der GREEN, YELLOW oder RED ausgibt).', 'Traffic light of the run (placeholders allowed, e.g. `{{ block.<name>.output }}` of a block printing GREEN, YELLOW or RED).'), values: [['GREEN', s('Grün', 'Green')], ['YELLOW', s('Gelb', 'Yellow')], ['RED', s('Rot', 'Red')]] },
      title: { type: 'string', doc: s('Titel, höchstens 80 Zeichen.', 'Title, at most 80 characters.') },
      summary: { type: 'string', doc: s('Zusammenfassung, höchstens 500 Zeichen.', 'Summary, at most 500 characters.') },
      kpis: { type: 'array', items: { $ref: '#/definitions/kpi' }, doc: s('Bis zu 6 Kennzahlen `{ label, value, trend? }`.', 'Up to 6 KPIs `{ label, value, trend? }`.') },
      items: { type: 'array', items: STRING_LIST, doc: s('Bis zu 10 Einträge, je höchstens 200 Zeichen.', 'Up to 10 entries, each at most 200 characters.') },
    },
  },
};

/**
 * Starting config offered when a block type is picked in the add dialog (and as Ctrl+Space snippet).
 * Kept as JSON so the backend runs the same defaults through the executors (`EditorDefaultConfigTest`):
 * a default the executor does not act on — foreach `items_command` without `items_source: "command"`
 * ran zero items (TODO 2f01ab01) — fails there, not in a user's first run.
 */
export const BLOCK_DEFAULT_CONFIG: Record<BlockType, Record<string, unknown>> = blockDefaultConfig;

const COMPRESS_OPS: Array<[string, Text]> = [
  ['text.normalize', s('ANSI, CR, Leerraum am Zeilenende und Leerzeilen-Folgen entfernen.', 'Strip ANSI, CR, trailing whitespace and blank-line runs.')],
  ['text.truncate', s('Kürzen an Zeilengrenzen: `max_chars`, `keep` (`head`/`tail`/`head_tail`).', 'Cut at line boundaries: `max_chars`, `keep` (`head`/`tail`/`head_tail`).')],
  ['lines.filter', s('Zeilen behalten/entfernen: `include`, `exclude` (Regex-Listen), `before`, `after`.', 'Keep/remove lines: `include`, `exclude` (regex lists), `before`, `after`.')],
  ['lines.replace', s('Regex-Ersetzung je Zeile: `pattern`, `replacement`.', 'Regex replacement per line: `pattern`, `replacement`.')],
  ['lines.dedupe', s('Wiederholungen entfernen: `count`, `consecutive_only`.', 'Remove repeats: `count`, `consecutive_only`.')],
  ['lines.head', s('Erste `lines` Zeilen.', 'First `lines` lines.')],
  ['lines.tail', s('Letzte `lines` Zeilen.', 'Last `lines` lines.')],
  ['markdown.section', s('Nur passende Abschnitte: `headings` (Regex), `level`.', 'Only matching sections: `headings` (regex), `level`.')],
  ['json.select', s('Nur diese Felder: `paths`.', 'Only these fields: `paths`.')],
  ['json.limit', s('Array kürzen: `path`, `count`.', 'Shorten an array: `path`, `count`.')],
  ['json.sort', s('Array sortieren: `path`, `by`, `order`.', 'Sort an array: `path`, `by`, `order`.')],
  ['json.to_lines', s('Ein Eintrag pro Zeile: `path`, `template` (`{title} — {url}`).', 'One entry per line: `path`, `template` (`{title} — {url}`).')],
];

/** Block types whose config accepts the AI override keys `provider`/`model`/`effort`. */
function acceptsAiOverride(type: string): boolean {
  return (AI_BLOCK_TYPES as readonly string[]).includes(type);
}

type JsonSchema = Record<string, unknown>;

function fieldSchema(field: Field, locale: SchemaLocale): JsonSchema {
  // The hover shows only this text (also over a value), so it lists the allowed values with their meaning
  const valueList = field.values?.map(([v, d]) => `- \`${v}\` — ${d[locale]}`).join('\n');
  const schema: JsonSchema = {
    type: field.type,
    markdownDescription: valueList ? `${field.doc[locale]}\n\n${valueList}` : field.doc[locale],
  };
  if (field.default !== undefined) schema.default = field.default;
  if (field.minimum !== undefined) schema.minimum = field.minimum;
  if (field.items) schema.items = field.items;
  if (field.values) {
    const values = field.values.map(([v]) => v);
    const docs = field.values.map(([, d]) => d[locale]);
    if (field.legacy) {
      // Still accepted, never suggested — `anyOf` keeps the legacy value valid without listing it
      schema.anyOf = [
        { enum: values, markdownEnumDescriptions: docs },
        { const: field.legacy, doNotSuggest: true },
      ];
    } else {
      schema.enum = values;
      schema.markdownEnumDescriptions = docs;
    }
  }
  return schema;
}

function propertiesFor(type: string, locale: SchemaLocale): Record<string, JsonSchema> {
  const spec = SPECS[type as BlockType];
  const fields: Record<string, Field> = { ...(spec?.fields ?? {}) };
  if (acceptsAiOverride(type)) {
    for (const [key, field] of Object.entries(AI_FIELDS)) fields[key] ??= field;
  }
  for (const [key, field] of Object.entries(COMMON_FIELDS)) fields[key] ??= field;
  fields.on_failure = ON_FAILURE;
  return Object.fromEntries(Object.entries(fields).map(([k, f]) => [k, fieldSchema(f, locale)]));
}

/** Config keys a block type knows (type-specific, AI override where it applies, `on_failure`). */
export function knownConfigKeys(type: string): string[] {
  return Object.keys(propertiesFor(type, 'en'));
}

/** Keys that must be present for the block to run. */
export function requiredConfigKeys(type: string): string[] {
  return SPECS[type as BlockType]?.required ?? [];
}

/** Allowed values of a key, if it has a fixed set (legacy aliases included). */
export function allowedConfigValues(type: string, key: string): string[] | null {
  const field = SPECS[type as BlockType]?.fields[key]
    ?? (acceptsAiOverride(type) ? AI_FIELDS[key] : undefined)
    ?? (key === 'on_failure' ? ON_FAILURE : undefined);
  if (!field?.values) return null;
  return [...field.values.map(([v]) => v), ...(field.legacy ? [field.legacy] : [])];
}

/** Every text of the schema, for the de/en completeness test. */
export function allSchemaTexts(): Text[] {
  const fields = [ON_FAILURE, ...Object.values(COMMON_FIELDS), ...Object.values(AI_FIELDS), ...Object.values(SPECS).flatMap((spec) => Object.values(spec.fields))];
  return [
    ...fields.flatMap((f) => [f.doc, ...(f.values ?? []).map(([, d]) => d)]),
    ...COMPRESS_OPS.map(([, d]) => d),
  ];
}

export function isKnownBlockType(type: string): type is BlockType {
  return type in SPECS;
}

/**
 * JSON schema for the `config` object of a block type. Unknown types get the common keys only, so a
 * type only the server knows still edits without warnings about its own keys.
 */
export function buildBlockConfigSchema(type: string, locale: SchemaLocale): JsonSchema {
  const known = isKnownBlockType(type);
  const nestedTypes = ['shell', 'simple'] as const;
  return {
    $schema: 'http://json-schema.org/draft-07/schema#',
    type: 'object',
    properties: propertiesFor(type, locale),
    required: requiredConfigKeys(type),
    // Unknown keys are ignored by the backend — the editor only warns, it never blocks saving
    additionalProperties: !known,
    ...(known ? { defaultSnippets: [{ label: type, body: BLOCK_DEFAULT_CONFIG[type] }] } : {}),
    definitions: {
      nestedBlock: {
        type: 'object',
        required: ['type', 'config'],
        properties: {
          type: {
            enum: [...nestedTypes],
            markdownEnumDescriptions: nestedTypes.map((t) => (locale === 'de' ? `Block vom Typ \`${t}\`` : `Block of type \`${t}\``)),
            markdownDescription: locale === 'de'
              ? 'In `switch` sind nur `shell` und `simple` erlaubt.'
              : 'Only `shell` and `simple` are allowed inside `switch`.',
          },
          name: {
            type: 'string',
            markdownDescription: locale === 'de'
              ? 'Macht die Ausgabe als `{{ block.<name>.output }}` verfügbar.'
              : 'Makes the output available as `{{ block.<name>.output }}`.',
          },
          config: { type: 'object' },
        },
        allOf: nestedTypes.map((t) => ({
          if: { properties: { type: { const: t } } },
          then: {
            properties: {
              config: {
                type: 'object',
                properties: propertiesFor(t, locale),
                required: requiredConfigKeys(t),
                additionalProperties: false,
              },
            },
          },
        })),
      },
      switchCase: {
        type: 'object',
        required: ['value', 'blocks'],
        properties: {
          value: {
            type: 'string',
            markdownDescription: locale === 'de'
              ? 'Vergleichswert; Alternativen mit `|` trennen, z. B. `"YELLOW|RED"`.'
              : 'Value to match; separate alternatives with `|`, e.g. `"YELLOW|RED"`.',
          },
          blocks: { type: 'array', items: { $ref: '#/definitions/nestedBlock' } },
        },
        additionalProperties: false,
      },
      compressStage: {
        type: 'object',
        required: ['op'],
        properties: {
          op: {
            enum: COMPRESS_OPS.map(([op]) => op),
            markdownEnumDescriptions: COMPRESS_OPS.map(([, d]) => d[locale]),
          },
        },
      },
      kpi: {
        type: 'object',
        required: ['label', 'value'],
        properties: {
          label: { type: 'string', maxLength: 40 },
          value: { type: 'string', maxLength: 40 },
          trend: { enum: ['UP', 'DOWN', 'FLAT'] },
        },
        additionalProperties: false,
      },
    },
  };
}
