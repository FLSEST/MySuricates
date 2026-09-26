import type { IntegrationProviderInfo } from '../../types';

/**
 * Providers that run without a stored connection, for backends that do not send
 * `connectionOptional` yet (cdccc1e5). The same list as the backend's `ConnectionOptionalGateTest`.
 * `requiresCredentials: false` alone is not enough: the HTTP API needs its stored base URL.
 */
const CONNECTION_OPTIONAL_FALLBACK = new Set(['open-meteo', 'feed', 'ical-feed', 'taskwarrior', 'ntfy']);

/** The provider works in the integration block without any stored connection. */
export function isConnectionOptional(provider: IntegrationProviderInfo): boolean {
  return provider.connectionOptional
    ?? (provider.requiresCredentials === false && CONNECTION_OPTIONAL_FALLBACK.has(provider.providerId));
}

/**
 * Usable as it is: it runs without a stored connection, and none is stored. A stored one that is not
 * connected — a failed Taskwarrior check, a disconnect — makes the backend refuse the provider, so
 * that one is not ready. `connectedSince` is when the default connection was stored, null while there
 * is none (every backend sends it).
 */
export function isReadyWithoutConnection(provider: IntegrationProviderInfo): boolean {
  return provider.status !== 'CONNECTED' && isConnectionOptional(provider) && provider.connectedSince == null;
}
