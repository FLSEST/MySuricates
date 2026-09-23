import type { AiProviderStatus, AiProviderStatusReport } from '../../hooks/useSystem';

/** The default provider first — it is the one every AI block without its own provider uses. */
export function orderProviders(providers: AiProviderStatus[]): AiProviderStatus[] {
  return [...providers].sort((a, b) => Number(b.defaultProvider) - Number(a.defaultProvider));
}

/** Whether the provider a workflow uses when it names none is ready. */
export function defaultProviderReady(report: AiProviderStatusReport): boolean {
  return report.providers.some((p) => p.defaultProvider && p.state === 'READY');
}

/** The status as one string: the start page hides its card for exactly the status it was hidden at. */
export function aiStatusFingerprint(report: AiProviderStatusReport): string {
  return report.providers.map((p) => `${p.id}:${p.state}${p.defaultProvider ? ':default' : ''}`).join(',');
}
