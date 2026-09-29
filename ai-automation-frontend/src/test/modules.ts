import type { ModuleInfo, ModuleName, ModulePreset, SystemModules } from '../types';

const FEATURES: Array<Pick<ModuleInfo, 'name' | 'tier' | 'maturity' | 'risks' | 'requires'>> = [
  { name: 'dashboard-report', tier: 'standard', maturity: 'stable', risks: [], requires: [] },
  { name: 'goals', tier: 'standard', maturity: 'stable', risks: [], requires: [] },
  { name: 'chains', tier: 'research', maturity: 'stable', risks: [], requires: [] },
  { name: 'assistant', tier: 'research', maturity: 'experimental', risks: [], requires: [] },
  { name: 'learning', tier: 'research', maturity: 'experimental', risks: [], requires: [] },
  { name: 'memory', tier: 'research', maturity: 'experimental', risks: [], requires: [] },
  { name: 'audit', tier: 'research', maturity: 'experimental', risks: [], requires: [] },
  { name: 'bpmn', tier: 'research', maturity: 'experimental', risks: [], requires: [] },
  { name: 'integrations', tier: 'advanced', maturity: 'stable', risks: ['credentials', 'network'], requires: [] },
  { name: 'webhooks', tier: 'advanced', maturity: 'stable', risks: ['network'], requires: [] },
  { name: 'remote-access', tier: 'advanced', maturity: 'stable', risks: ['network'], requires: [] },
  { name: 'terminal', tier: 'advanced', maturity: 'stable', risks: ['host-access'], requires: [] },
  { name: 'staging', tier: 'advanced', maturity: 'experimental', risks: ['host-access', 'network'], requires: [] },
  { name: 'conversion', tier: 'advanced', maturity: 'legacy', risks: [], requires: [] },
  { name: 'dialog-import', tier: 'advanced', maturity: 'legacy', risks: [], requires: ['conversion'] },
  { name: 'onboarding-profiles', tier: 'advanced', maturity: 'legacy', risks: [], requires: [] },
  { name: 'skills', tier: 'advanced', maturity: 'legacy', risks: ['host-access'], requires: [] },
  { name: 'debug', tier: 'advanced', maturity: 'legacy', risks: [], requires: [] },
  { name: 'factory', tier: 'advanced', maturity: 'experimental', risks: [], requires: [] },
  { name: 'wishes', tier: 'advanced', maturity: 'experimental', risks: [], requires: ['chains'] },
];

const HARDENING: Array<Pick<ModuleInfo, 'name' | 'requires'>> = [
  { name: 'provenance', requires: [] },
  { name: 'sealing', requires: ['provenance'] },
  { name: 'require-sealed-for-unattended', requires: ['sealing'] },
  { name: 'require-signed-seal', requires: ['sealing'] },
  { name: 'seal-immutable', requires: ['sealing'] },
  { name: 'webhook-signatures', requires: ['webhooks'] },
  { name: 'private-network-guard', requires: [] },
  { name: 'private-network-guard-http-api', requires: [] },
];

function envName(prefix: string, name: string) {
  return `${prefix.replace(/\./g, '_')}_${name.replace(/-/g, '_')}`.toUpperCase();
}

/**
 * Capabilities fixture mirroring the backend catalog; `disabled` lists modules reported off.
 * Carries both the cache shape (`uiEditable`) and the capabilities field (`modulesUiEditable`).
 */
export function modulesFixture(
  preset: ModulePreset = 'full', disabled: ModuleName[] = [], uiEditable = false,
): SystemModules & { modulesUiEditable: boolean } {
  const off = new Set(disabled);
  const modules: ModuleInfo[] = [
    ...FEATURES.map((f) => ({
      ...f,
      kind: 'feature' as const,
      enabled: !off.has(f.name),
      configured: !off.has(f.name),
      overridden: false,
      property: `automation.modules.${f.name}`,
      envVar: envName('automation.modules', f.name),
      description: `${f.name} description`,
    })),
    ...HARDENING.map((h) => ({
      ...h,
      kind: 'hardening' as const,
      tier: 'hardening' as const,
      maturity: 'experimental' as const,
      risks: [],
      enabled: !off.has(h.name),
      property: `automation.hardening.${h.name}`,
      envVar: envName('automation.hardening', h.name),
      description: `${h.name} description`,
    })),
  ];
  return { preset, uiEditable, modulesUiEditable: uiEditable, modules };
}
