import { describe, it, expect } from 'vitest';
import { aiStatusFingerprint, defaultProviderReady, orderProviders } from '../aiProviderStatus';
import type { AiProviderStatus, AiProviderStatusReport } from '../../../hooks/useSystem';

function provider(id: string, state: AiProviderStatus['state'], defaultProvider = false): AiProviderStatus {
  return {
    id, cli: id.toLowerCase(), state, installed: state !== 'NOT_INSTALLED', loggedIn: state === 'READY',
    loginVia: null, loginSource: null, install: 'install', login: 'login', defaultProvider,
  };
}

function report(...providers: AiProviderStatus[]): AiProviderStatusReport {
  return { container: false, checkedAt: '2026-09-24T10:00:00Z', providers };
}

describe('aiProviderStatus', () => {
  it('puts the default provider first and keeps the rest in order', () => {
    const ordered = orderProviders([provider('CLAUDE', 'READY'), provider('CODEX', 'READY'), provider('PI', 'READY', true)]);
    expect(ordered.map((p) => p.id)).toEqual(['PI', 'CLAUDE', 'CODEX']);
  });

  it('knows whether the default provider is ready', () => {
    expect(defaultProviderReady(report(provider('CLAUDE', 'READY', true)))).toBe(true);
    expect(defaultProviderReady(report(provider('CLAUDE', 'LOGIN_MISSING', true), provider('CODEX', 'READY')))).toBe(false);
    expect(defaultProviderReady(report(provider('CODEX', 'READY')))).toBe(false);
  });

  it('changes the fingerprint with every state and with the default, not with the check time', () => {
    const base = report(provider('CLAUDE', 'LOGIN_MISSING', true), provider('CODEX', 'READY'));
    expect(aiStatusFingerprint({ ...base, checkedAt: '2026-09-24T11:00:00Z' })).toBe(aiStatusFingerprint(base));
    expect(aiStatusFingerprint(report(provider('CLAUDE', 'NOT_INSTALLED', true), provider('CODEX', 'READY'))))
      .not.toBe(aiStatusFingerprint(base));
    expect(aiStatusFingerprint(report(provider('CLAUDE', 'LOGIN_MISSING'), provider('CODEX', 'READY', true))))
      .not.toBe(aiStatusFingerprint(base));
  });
});
