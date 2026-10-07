import type { ExecutionConfig } from '../../hooks/useSystem';

/**
 * Form model of the execution settings card: which settings it edits, how a server config becomes
 * form values and how form values go back to the server. Adding a setting means one entry in
 * SETTINGS plus its place in the card (a field, toggle or CLI limit row).
 */

interface SettingSpec<K extends 'number' | 'flag', V> {
  kind: K;
  /** Shown before the config has loaded — and, for an `optional` setting, when the backend does not send it. */
  initial: V;
  /** Older backends may omit the setting; the form then shows `initial`. Otherwise the value is taken as sent. */
  optional: boolean;
}

const number = (initial: number, optional = false): SettingSpec<'number', number> =>
  ({ kind: 'number', initial, optional });
/** A switch the backend stores as 1 (on) / 0 (off). */
const flag = (initial: boolean, optional = false): SettingSpec<'flag', boolean> =>
  ({ kind: 'flag', initial, optional });

const SETTINGS = {
  executionMaxConcurrent: number(10),
  claudeMaxConcurrentCalls: number(5),
  claudeReservedForInteractive: number(1, true),
  piMaxConcurrentCalls: number(5, true),
  piReservedForInteractive: number(1, true),
  codexMaxConcurrentCalls: number(5, true),
  codexReservedForInteractive: number(1, true),
  aiCliMaxConcurrentCalls: number(8, true),
  aiCliReservedForInteractive: number(1, true),
  claudeTimeoutSeconds: number(300),
  claudeInterCallDelayMs: number(0),
  chainWorkflowTimeoutMinutes: number(180),
  rateLimitEnabled: flag(true),
  rateLimitRequestsPerMinute: number(120),
  assistantChatTimeoutSeconds: number(240, true),
  schedulerCatchUpOnResume: flag(true, true),
  schedulerResumeGraceMinutes: number(5, true),
  runAutoResumeEnabled: flag(true, true),
  runAutoResumeDelaySeconds: number(30, true),
  summarizationTimeoutSeconds: number(120, true),
  factoryTimeoutSeconds: number(600, true),
  runRetentionDays: number(90, true),
  runRetentionMaxPerNight: number(200, true),
  liveLogPersistDefault: flag(true, true),
  dashboardLayoutRetentionCount: number(100, true),
  dashboardReportAssistEnabled: flag(true, true),
} satisfies { [K in keyof ExecutionConfig]?: SettingSpec<'number', number> | SettingSpec<'flag', boolean> };

type Settings = typeof SETTINGS;
type SettingKey = keyof Settings;

export type FlagSettingKey = { [K in SettingKey]: Settings[K]['kind'] extends 'flag' ? K : never }[SettingKey];
export type NumericSettingKey = Exclude<SettingKey, FlagSettingKey>;

export type ExecutionSettingsForm = { [K in NumericSettingKey]: number } & { [K in FlagSettingKey]: boolean };

export type ExecutionSettingsPayload = Partial<Omit<ExecutionConfig, 'activeRuns'>>;

const KEYS = Object.keys(SETTINGS) as SettingKey[];

function mapSettings(value: (key: SettingKey) => number | boolean): ExecutionSettingsForm {
  return Object.fromEntries(KEYS.map((key) => [key, value(key)])) as ExecutionSettingsForm;
}

/** Form values before the server config is known. */
export const DEFAULT_FORM: ExecutionSettingsForm = mapSettings((key) => SETTINGS[key].initial);

/** Form values for a server config; optional settings missing in it fall back to their initial value. */
export function toFormState(config: ExecutionConfig): ExecutionSettingsForm {
  return mapSettings((key) => {
    const spec: SettingSpec<'number', number> | SettingSpec<'flag', boolean> = SETTINGS[key];
    const sent = config[key];
    if (spec.kind === 'flag') {
      const fallback = spec.optional ? (spec.initial ? 1 : 0) : undefined;
      return (sent ?? fallback) === 1;
    }
    return (spec.optional ? sent ?? spec.initial : sent) as number;
  });
}

/** The request body for saving the form; switches go out as 1 / 0. */
export function toPayload(form: ExecutionSettingsForm): ExecutionSettingsPayload {
  return Object.fromEntries(KEYS.map((key) => {
    const value = form[key];
    return [key, typeof value === 'boolean' ? (value ? 1 : 0) : value];
  })) as ExecutionSettingsPayload;
}

/** Whether the form differs from what the server config shows; false while no config is loaded. */
export function hasChanges(form: ExecutionSettingsForm, config: ExecutionConfig | undefined): boolean {
  if (!config) return false;
  const saved = toFormState(config);
  return KEYS.some((key) => form[key] !== saved[key]);
}
