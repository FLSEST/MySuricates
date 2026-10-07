import { describe, it, expect } from 'vitest';
import type { ExecutionConfig } from '../../../hooks/useSystem';
import { DEFAULT_FORM, hasChanges, toFormState, toPayload } from '../executionSettingsForm';

/** What a backend that predates the optional settings sends. */
const OLD_CONFIG = {
  executionMaxConcurrent: 4, claudeMaxConcurrentCalls: 3, claudeReservedForInteractive: 2, claudeTimeoutSeconds: 200,
  claudeInterCallDelayMs: 50, chainWorkflowTimeoutMinutes: 30, rateLimitEnabled: 0, rateLimitRequestsPerMinute: 60,
  assistantChatTimeoutSeconds: 100, schedulerCatchUpOnResume: 0, schedulerResumeGraceMinutes: 7,
  summarizationTimeoutSeconds: 90, factoryTimeoutSeconds: 700, activeRuns: 0,
} as ExecutionConfig;

/** A current backend sending every setting, with values off the defaults. */
const FULL_CONFIG: ExecutionConfig = {
  ...OLD_CONFIG,
  piMaxConcurrentCalls: 6, piReservedForInteractive: 0, codexMaxConcurrentCalls: 7, codexReservedForInteractive: 2,
  aiCliMaxConcurrentCalls: 12, aiCliReservedForInteractive: 3, runAutoResumeEnabled: 0, runAutoResumeDelaySeconds: 90,
  runRetentionDays: 30, runRetentionMaxPerNight: 500, liveLogPersistDefault: 0, dashboardLayoutRetentionCount: 20,
  dashboardReportAssistEnabled: 0, wishTotalTimeoutMinutes: 15, queuedRuns: 1,
};

describe('executionSettingsForm', () => {
  it('starts with the defaults the card shows before the config has loaded', () => {
    expect(DEFAULT_FORM).toEqual({
      executionMaxConcurrent: 10, claudeMaxConcurrentCalls: 5, claudeReservedForInteractive: 1,
      piMaxConcurrentCalls: 5, piReservedForInteractive: 1, codexMaxConcurrentCalls: 5, codexReservedForInteractive: 1,
      aiCliMaxConcurrentCalls: 8, aiCliReservedForInteractive: 1, claudeTimeoutSeconds: 300, claudeInterCallDelayMs: 0,
      chainWorkflowTimeoutMinutes: 180, rateLimitEnabled: true, rateLimitRequestsPerMinute: 120,
      assistantChatTimeoutSeconds: 240, schedulerCatchUpOnResume: true, schedulerResumeGraceMinutes: 5,
      runAutoResumeEnabled: true, runAutoResumeDelaySeconds: 30, summarizationTimeoutSeconds: 120,
      factoryTimeoutSeconds: 600, runRetentionDays: 90, runRetentionMaxPerNight: 200, liveLogPersistDefault: true,
      dashboardLayoutRetentionCount: 100, dashboardReportAssistEnabled: true,
    });
  });

  it('takes every sent value and turns the 1/0 switches into booleans', () => {
    const form = toFormState(FULL_CONFIG);
    expect(form).toMatchObject({
      executionMaxConcurrent: 4, piReservedForInteractive: 0, aiCliMaxConcurrentCalls: 12, claudeInterCallDelayMs: 50,
      runRetentionMaxPerNight: 500, rateLimitEnabled: false, schedulerCatchUpOnResume: false,
      runAutoResumeEnabled: false, liveLogPersistDefault: false, dashboardReportAssistEnabled: false,
    });
    // only the edited settings, not the rest of the config
    expect(form).not.toHaveProperty('wishTotalTimeoutMinutes');
    expect(form).not.toHaveProperty('activeRuns');
  });

  it('fills settings an older backend does not send with their defaults', () => {
    expect(toFormState(OLD_CONFIG)).toMatchObject({
      piMaxConcurrentCalls: 5, piReservedForInteractive: 1, codexMaxConcurrentCalls: 5, aiCliMaxConcurrentCalls: 8,
      aiCliReservedForInteractive: 1, runAutoResumeEnabled: true, runAutoResumeDelaySeconds: 30, runRetentionDays: 90,
      runRetentionMaxPerNight: 200, liveLogPersistDefault: true, dashboardLayoutRetentionCount: 100,
      dashboardReportAssistEnabled: true,
    });
  });

  it('reads a missing rate limit switch as off — it has no fallback', () => {
    const { rateLimitEnabled: _omitted, ...withoutSwitch } = FULL_CONFIG;
    expect(toFormState(withoutSwitch as ExecutionConfig).rateLimitEnabled).toBe(false);
  });

  it('saves every setting, switches as 1/0, and round-trips through the form', () => {
    const payload = toPayload(toFormState(FULL_CONFIG));
    expect(payload).toMatchObject({ rateLimitEnabled: 0, runAutoResumeEnabled: 0, aiCliMaxConcurrentCalls: 12 });
    expect(Object.keys(payload).sort()).toEqual(Object.keys(DEFAULT_FORM).sort());
    expect(toPayload({ ...DEFAULT_FORM })).toMatchObject({ rateLimitEnabled: 1, liveLogPersistDefault: 1 });
    expect(toFormState({ ...FULL_CONFIG, ...payload })).toEqual(toFormState(FULL_CONFIG));
  });

  it('reports a change only when a form value differs from the loaded config', () => {
    const form = toFormState(OLD_CONFIG);
    expect(hasChanges(form, undefined)).toBe(false);
    expect(hasChanges(form, OLD_CONFIG)).toBe(false);
    expect(hasChanges({ ...form, runRetentionDays: 91 }, OLD_CONFIG)).toBe(true);
    expect(hasChanges({ ...form, liveLogPersistDefault: false }, OLD_CONFIG)).toBe(true);
    expect(hasChanges({ ...form, rateLimitEnabled: true }, OLD_CONFIG)).toBe(true);
  });
});
