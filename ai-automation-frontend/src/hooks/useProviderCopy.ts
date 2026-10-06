import { useT } from '../i18n';

type ProviderCopy = Record<string, { displayName?: string; description?: string; actions?: Record<string, string> }>;

/**
 * Display texts per integration provider in the app language (`t.integrations.providers`, DE/EN):
 * name, description and description per action. Without a translation the backend's text applies.
 */
export function useProviderCopy() {
  const t = useT();
  const providerCopy = (t.integrations as unknown as { providers?: ProviderCopy }).providers;
  return {
    name: (p: { providerId: string; displayName: string }) =>
      providerCopy?.[p.providerId]?.displayName ?? p.displayName,
    description: (p: { providerId: string; description: string }) =>
      providerCopy?.[p.providerId]?.description ?? p.description,
    actionDescription: (providerId: string, a: { name: string; description: string }) =>
      providerCopy?.[providerId]?.actions?.[a.name] ?? a.description,
  };
}
