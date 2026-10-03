import type { BlockType } from '../../types';
import blockDefaultConfig from '../../data/blockDefaultConfig.json';
import schemaSource from '../../data/blockConfigSchema.json';

/**
 * Describes the `config` object of every block type as a JSON schema, so the Monaco config editor can
 * suggest keys and values (Ctrl+Space), explain them on hover and flag typos as warnings.
 *
 * The keys, types, allowed values and texts live in `src/data/blockConfigSchema.json` — the single source
 * the backend analyzer reads as well (`ConfigKeyRules`, TODO f5153c47): the Maven build copies the file
 * onto the classpath, so editor and analyzer cannot drift apart. The texts are domain reference (source:
 * docs/WORKFLOW-GUIDE.md §2 „Block-Typ-Referenz"), not UI labels, so they sit there as {de, en} instead of
 * in the i18n files. Unknown keys only warn — the backend ignores them as well — and
 * `blockConfigSchema.test.ts` checks the shipped templates against the file.
 */

export type SchemaLocale = 'de' | 'en';
type Text = { de: string; en: string };

interface Field {
  type: 'string' | 'integer' | 'boolean' | 'array' | 'object' | Array<'string' | 'array' | 'null'>;
  doc: Text;
  /** Allowed values with an explanation each (shown next to the suggestion). */
  values?: Array<{ value: string; doc: Text }>;
  default?: unknown;
  minimum?: number;
  items?: Record<string, unknown>;
  /** Legacy value still accepted, but not suggested. */
  legacy?: string;
  /** Values are compared case-insensitively at run time (analyzer only, the editor still suggests one spelling). */
  ignoreCase?: boolean;
  /** Old name of another key, still read by the executor: valid, never suggested. */
  legacyFor?: string;
  /** The executor reads the key only when these other keys have these values (e.g. `execution_mode: shell`; `"*"` = the key is set). */
  onlyWith?: Record<string, string>;
}

/** A field, or `"#name"` for an entry of `sharedFields`. */
type FieldRef = Field | string;

interface BlockSpec {
  required?: string[];
  /** Exactly one of these keys must be set (todo-update: `id` or `updates`). */
  requiredOneOf?: string[];
  fields: Record<string, FieldRef>;
}

interface SchemaSource {
  onFailure: Field;
  commonFields: Record<string, Field>;
  ai: { blockTypes: string[]; fields: Record<string, Field> };
  sharedFields: Record<string, Field>;
  blockTypes: Record<string, BlockSpec>;
  compressOps: Array<{ op: string; doc: Text }>;
}

const SOURCE = schemaSource as unknown as SchemaSource;

function resolveField(ref: FieldRef): Field {
  if (typeof ref !== 'string') return ref;
  const shared = SOURCE.sharedFields[ref.slice(1)];
  if (!shared) throw new Error(`blockConfigSchema.json: unknown shared field ${ref}`);
  return shared;
}

const ON_FAILURE: Field = SOURCE.onFailure;
/** Keys every block accepts besides `on_failure`: notes for people, run workspace policy, output checks. */
const COMMON_FIELDS: Record<string, Field> = SOURCE.commonFields;
const AI_FIELDS: Record<string, Field> = SOURCE.ai.fields;
const SPECS: Record<string, { required?: string[]; requiredOneOf?: string[]; fields: Record<string, Field> }> = Object.fromEntries(
  Object.entries(SOURCE.blockTypes).map(([type, spec]) => [type, {
    required: spec.required,
    requiredOneOf: spec.requiredOneOf,
    fields: Object.fromEntries(Object.entries(spec.fields).map(([key, ref]) => [key, resolveField(ref)])),
  }]),
);
const COMPRESS_OPS: Array<[string, Text]> = SOURCE.compressOps.map(({ op, doc }) => [op, doc]);

/**
 * Starting config offered when a block type is picked in the add dialog (and as Ctrl+Space snippet).
 * Kept as JSON so the backend runs the same defaults through the executors (`EditorDefaultConfigTest`):
 * a default the executor does not act on — foreach `items_command` without `items_source: "command"`
 * ran zero items (TODO 2f01ab01) — fails there, not in a user's first run.
 */
export const BLOCK_DEFAULT_CONFIG: Record<BlockType, Record<string, unknown>> = blockDefaultConfig;


/** Block types whose config accepts the AI override keys `provider`/`model`/`effort`. */
function acceptsAiOverride(type: string): boolean {
  return SOURCE.ai.blockTypes.includes(type);
}

type JsonSchema = Record<string, unknown>;

function fieldSchema(field: Field, locale: SchemaLocale): JsonSchema {
  // The hover shows only this text (also over a value), so it lists the allowed values with their meaning
  const valueList = field.values?.map(({ value, doc }) => `- \`${value}\` — ${doc[locale]}`).join('\n');
  const schema: JsonSchema = {
    type: field.type,
    markdownDescription: valueList ? `${field.doc[locale]}\n\n${valueList}` : field.doc[locale],
  };
  if (field.default !== undefined) schema.default = field.default;
  if (field.minimum !== undefined) schema.minimum = field.minimum;
  if (field.items) schema.items = field.items;
  // An old key name the executor still reads: valid, never offered, struck through as being phased out
  if (field.legacyFor) {
    schema.doNotSuggest = true;
    schema.deprecationMessage = field.doc[locale];
  }
  if (field.values) {
    const values = field.values.map(({ value }) => value);
    const docs = field.values.map(({ doc }) => doc[locale]);
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
  const spec = SPECS[type];
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
  return SPECS[type]?.required ?? [];
}

/** Keys of which exactly one must be set, an empty text counting as not set (empty when the type has no such rule). */
export function requiredOneOfConfigKeys(type: string): string[] {
  return SPECS[type]?.requiredOneOf ?? [];
}

/** Allowed values of a key, if it has a fixed set (legacy aliases included). */
export function allowedConfigValues(type: string, key: string): string[] | null {
  const field = SPECS[type]?.fields[key]
    ?? (acceptsAiOverride(type) ? AI_FIELDS[key] : undefined)
    ?? (key === 'on_failure' ? ON_FAILURE : undefined);
  if (!field?.values) return null;
  return [...field.values.map(({ value }) => value), ...(field.legacy ? [field.legacy] : [])];
}

/** Every text of the schema, for the de/en completeness test. */
export function allSchemaTexts(): Text[] {
  const fields = [ON_FAILURE, ...Object.values(COMMON_FIELDS), ...Object.values(AI_FIELDS), ...Object.values(SPECS).flatMap((spec) => Object.values(spec.fields))];
  return [
    ...fields.flatMap((f) => [f.doc, ...(f.values ?? []).map(({ doc }) => doc)]),
    ...COMPRESS_OPS.map(([, d]) => d),
  ];
}

export function isKnownBlockType(type: string): type is BlockType {
  return Object.hasOwn(SPECS, type);
}

/** Block types that accept the AI override keys, as listed in the shared schema file. */
export function aiOverrideBlockTypes(): readonly string[] {
  return SOURCE.ai.blockTypes;
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
    ...(requiredOneOfConfigKeys(type).length
      // An empty text counts as not set — as in the executor and the analyzer
      ? { oneOf: requiredOneOfConfigKeys(type).map((key) => ({ required: [key], properties: { [key]: { minLength: 1 } } })) }
      : {}),
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
