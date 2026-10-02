import { describe, it, expect } from 'vitest';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { AI_BLOCK_TYPES, BLOCK_TYPES } from '../../../types';
import {
  BLOCK_DEFAULT_CONFIG,
  aiOverrideBlockTypes,
  allSchemaTexts,
  allowedConfigValues,
  buildBlockConfigSchema,
  knownConfigKeys,
  requiredConfigKeys,
  requiredOneOfConfigKeys,
} from '../blockConfigSchema';

const REPO = resolve(__dirname, '../../../../..');
const DEFINITION_DIRS = [
  'ai-automation-backend/src/main/resources/templates/builtin',
  'ai-automation-backend/src/main/resources/templates/blueprints',
  'workflow-pool',
];

/**
 * Keys in shipped definitions that no executor reads — the editor rightly warns about them there.
 * Listed so this guard stays strict for everything else; fixing the definition removes the entry.
 * github-project-bootstrap, saas-feature-implement and saas-spec-to-buildplan are fixed. Currently
 * empty: saas-feature-loop nested a sub-workflow in a foreach, which no block type can express yet,
 * and was taken out of the pool until it can.
 */
const KNOWN_INEFFECTIVE = new Set<string>([]);

type Block = { type: string; config?: Record<string, unknown> };

function jsonFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return jsonFiles(path);
    return name.endsWith('.json') ? [path] : [];
  });
}

/**
 * The shipped JSON files under dir: what git tracks there (TODO ca858a23). A maintainer keeps
 * private pool files beside them, ignored via .git/info/exclude — read by a glob, they made this
 * guard red on that checkout and green in a fresh worktree. Without git (a source archive) every
 * JSON file counts, and so it does when git lists none there — an empty list would pass silently.
 */
function shippedJsonFiles(dir: string, root = REPO): string[] {
  let listed: string;
  try {
    listed = execFileSync('git', ['ls-files', '-z', '--', dir], {
      cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'],
    });
  } catch {
    return jsonFiles(join(root, dir));
  }
  const tracked = listed.split('\0')
    .filter((rel) => rel.endsWith('.json'))
    .map((rel) => join(root, rel))
    .filter((path) => existsSync(path))
    .sort();
  return tracked.length > 0 ? tracked : jsonFiles(join(root, dir));
}

/** Every top-level block of the shipped definitions, with its origin for readable failures. */
function shippedBlocks(): Array<{ where: string; block: Block }> {
  const found: Array<{ where: string; block: Block }> = [];
  for (const dir of DEFINITION_DIRS) {
    for (const file of shippedJsonFiles(dir)) {
      let parsed: unknown;
      try { parsed = JSON.parse(readFileSync(file, 'utf8')); } catch { continue; }
      const blocks = (parsed as { blocks?: unknown })?.blocks;
      if (!Array.isArray(blocks)) continue;
      blocks.forEach((block: Block, i) => {
        if (block && typeof block.type === 'string') found.push({ where: `${file.slice(REPO.length + 1)} #${i}`, block });
      });
    }
  }
  return found;
}

describe('blockConfigSchema', () => {
  it('describes every block type and offers a default config for it', () => {
    for (const type of BLOCK_TYPES) {
      expect(knownConfigKeys(type).length, type).toBeGreaterThan(1);
      expect(BLOCK_DEFAULT_CONFIG[type], type).toBeDefined();
      for (const key of requiredConfigKeys(type)) {
        expect(BLOCK_DEFAULT_CONFIG[type], `${type} default lacks required ${key}`).toHaveProperty(key);
      }
    }
  });

  // The executor reads items_command only with items_source "command" — without it a new foreach
  // block ran zero items (TODO 2f01ab01). The backend runs this default in EditorDefaultConfigTest.
  it('lets the foreach default take its items from items_command', () => {
    expect(BLOCK_DEFAULT_CONFIG.foreach).toMatchObject({ items_source: 'command', items_command: '' });
    expect(allowedConfigValues('foreach', 'items_source')).toContain('command');
  });

  it('explains every key and value in German and English', () => {
    for (const text of allSchemaTexts()) {
      expect(text.de.trim(), JSON.stringify(text)).not.toBe('');
      expect(text.en.trim(), JSON.stringify(text)).not.toBe('');
    }
  });

  it('suggests stop/continue for on_failure on every block type, with a hover text per value', () => {
    for (const type of BLOCK_TYPES) {
      const onFailure = (buildBlockConfigSchema(type, 'de').properties as Record<string, Record<string, unknown>>).on_failure!;
      expect(onFailure.enum, type).toEqual(['stop', 'continue']);
      expect((onFailure.markdownEnumDescriptions as string[]).length).toBe(2);
      expect(onFailure.markdownDescription).toMatch(/fehlschlägt[\s\S]*- `stop` — [\s\S]*- `continue` — /);
    }
  });

  // The shared schema file carries its own list for the backend analyzer; it must match the one the UI uses
  it('lists the same AI block types as the UI (AI_BLOCK_TYPES)', () => {
    expect([...aiOverrideBlockTypes()].sort()).toEqual([...AI_BLOCK_TYPES].sort());
  });

  it('offers the AI override keys only where the block makes an AI call', () => {
    expect(knownConfigKeys('simple')).toEqual(expect.arrayContaining(['provider', 'model', 'effort']));
    expect(knownConfigKeys('shell')).not.toContain('effort');
    expect(knownConfigKeys('approval')).not.toContain('model');
  });

  it('keeps the legacy execution_mode "claude" valid without suggesting it', () => {
    const mode = (buildBlockConfigSchema('foreach', 'en').properties as Record<string, Record<string, unknown>>).execution_mode!;
    expect(mode.anyOf).toEqual([
      expect.objectContaining({ enum: ['ai', 'shell'] }),
      { const: 'claude', doNotSuggest: true },
    ]);
  });

  // Keys the executors read that the editor used to flag (TODO f5153c47, ExecutorConfigKeyDriftTest on the backend)
  it('knows the output check, shell-mode container keys of wrappers and the old workflowId spelling', () => {
    for (const type of BLOCK_TYPES) expect(knownConfigKeys(type), type).toContain('validation');
    for (const type of ['retry', 'foreach', 'while', 'conditional']) {
      expect(knownConfigKeys(type), type).toEqual(expect.arrayContaining(['container_image', 'mount_docker_socket', 'mounts']));
    }
    const workflowId = (buildBlockConfigSchema('sub-workflow', 'en').properties as Record<string, Record<string, unknown>>).workflowId!;
    expect(workflowId.doNotSuggest).toBe(true);
    expect(requiredConfigKeys('sub-workflow')).toEqual(['workflow_id']);
  });

  // todo-update runs with either `id` or `updates` — neither, or both, fails at run time (TODO d6c0afe6)
  it('requires exactly one of id and updates for todo-update', () => {
    expect(requiredOneOfConfigKeys('todo-update')).toEqual(['id', 'updates']);
    expect(requiredConfigKeys('todo-update')).toEqual([]);
    const schema = buildBlockConfigSchema('todo-update', 'en');
    // An empty text counts as not set, as in the executor and the analyzer
    expect(schema.oneOf).toEqual([
      { required: ['id'], properties: { id: { minLength: 1 } } },
      { required: ['updates'], properties: { updates: { minLength: 1 } } },
    ]);
    expect(requiredOneOfConfigKeys('shell')).toEqual([]);
    expect(buildBlockConfigSchema('shell', 'en')).not.toHaveProperty('oneOf');
  });

  it('lets an unknown (server-only) block type through without key warnings', () => {
    expect(buildBlockConfigSchema('future-block', 'en').additionalProperties).toBe(true);
    expect(buildBlockConfigSchema('shell', 'en').additionalProperties).toBe(false);
  });

  // Drift guard: the editor must not flag keys or values the shipped definitions really use
  it('reads only tracked definition files, and all of them when git tracks none there', () => {
    const root = mkdtempSync(join(tmpdir(), 'pool-'));
    try {
      const git = (...args: string[]) => execFileSync('git', args, { cwd: root, stdio: 'ignore' });
      git('init', '-q');
      mkdirSync(join(root, 'workflow-pool'));
      // Nothing tracked yet: falling back to every JSON file, an empty list would pass silently.
      writeFileSync(join(root, 'workflow-pool', 'private.json'), '{}');
      expect(shippedJsonFiles('workflow-pool', root).map((f) => f.slice(root.length + 1)))
        .toEqual(['workflow-pool/private.json']);
      writeFileSync(join(root, 'workflow-pool', 'shipped.json'), '{}');
      writeFileSync(join(root, '.git', 'info', 'exclude'), 'workflow-pool/private.json\n');
      git('add', 'workflow-pool/shipped.json');
      expect(shippedJsonFiles('workflow-pool', root).map((f) => f.slice(root.length + 1)))
        .toEqual(['workflow-pool/shipped.json']);
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  });

  it('knows every config key and fixed value the shipped templates, blueprints and pool use', () => {
    const blocks = shippedBlocks();
    expect(blocks.length).toBeGreaterThan(50);
    const problems: string[] = [];
    for (const { where, block } of blocks) {
      const known = knownConfigKeys(block.type);
      for (const [key, value] of Object.entries(block.config ?? {})) {
        if (!known.includes(key)) {
          if (!KNOWN_INEFFECTIVE.has(`${block.type}.${key}`)) problems.push(`${where}: ${block.type}.${key} unknown`);
          continue;
        }
        const allowed = allowedConfigValues(block.type, key);
        if (allowed && typeof value === 'string' && !value.includes('{{') && !allowed.includes(value)
          && !KNOWN_INEFFECTIVE.has(`${block.type}.${key} = ${value}`)) {
          problems.push(`${where}: ${block.type}.${key} = ${value}`);
        }
      }
      for (const key of requiredConfigKeys(block.type)) {
        if (!(key in (block.config ?? {})) && !KNOWN_INEFFECTIVE.has(`${block.type} lacks ${key}`)) problems.push(`${where}: ${block.type} lacks ${key}`);
      }
      const oneOf = requiredOneOfConfigKeys(block.type);
      const isSet = (key: string) => { const value = block.config?.[key]; return value != null && value !== ''; };
      if (oneOf.length && oneOf.filter(isSet).length !== 1) {
        problems.push(`${where}: ${block.type} needs exactly one of ${oneOf.join(', ')}`);
      }
    }
    expect(problems).toEqual([]);
  });
});
