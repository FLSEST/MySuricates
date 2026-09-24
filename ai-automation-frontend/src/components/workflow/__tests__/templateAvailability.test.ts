import { describe, it, expect } from 'vitest';
import { missingModules, splitByAvailability, templateNeedsAi } from '../templateAvailability';
import { modulesFixture } from '../../../test/modules';
import type { BlockDefinition } from '../../../types';

const core = modulesFixture('core', ['skills', 'integrations']).modules;

describe('templateAvailability', () => {
  it('names only the required modules that are off', () => {
    expect(missingModules({ requiredModules: ['skills', 'dashboard-report'] }, core)).toEqual(['skills']);
    expect(missingModules({ requiredModules: [] }, core)).toEqual([]);
  });

  it('hides nothing while the module state or the requirement is unknown', () => {
    expect(missingModules({ requiredModules: ['skills'] }, undefined)).toEqual([]);
    expect(missingModules({}, core)).toEqual([]);
  });

  it('splits into runnable and needs-a-module, keeping the order', () => {
    const templates = [
      { id: 'a', requiredModules: ['skills' as const] },
      { id: 'b', requiredModules: [] },
      { id: 'c' },
      { id: 'd', requiredModules: ['integrations' as const] },
    ];
    const { runnable, needsModule } = splitByAvailability(templates, core);
    expect(runnable.map((t) => t.id)).toEqual(['b', 'c']);
    expect(needsModule.map((t) => t.id)).toEqual(['a', 'd']);
  });

  it('trusts the server on needsAi and falls back to the badge rule for older backends', () => {
    const shellOnly = [{ type: 'shell', config: {} }] as unknown as BlockDefinition[];
    const withAi = [{ type: 'conditional', config: { condition: 'x' } }] as unknown as BlockDefinition[];
    expect(templateNeedsAi({ needsAi: true, blocks: shellOnly })).toBe(true);
    expect(templateNeedsAi({ needsAi: false, blocks: withAi })).toBe(false);
    expect(templateNeedsAi({ blocks: withAi })).toBe(true);
    expect(templateNeedsAi({ blocks: shellOnly })).toBe(false);
  });
});
