/**
 * Generate a UUID v4 string. Uses crypto.randomUUID() when available (secure contexts),
 * falls back to crypto.getRandomValues() for non-secure contexts (e.g. HTTP via Tailscale).
 */
export function generateId(): string {
  const c = globalThis.crypto;
  if (!c) {
    return fallbackUuid();
  }
  if (typeof c.randomUUID === 'function') {
    return c.randomUUID();
  }
  const bytes = new Uint8Array(16);
  c.getRandomValues(bytes);
  // Set version (4) and variant (1) bits
  bytes.set([((bytes[6] ?? 0) & 0x0f) | 0x40], 6);
  bytes.set([((bytes[8] ?? 0) & 0x3f) | 0x80], 8);
  const hex = [...bytes].map(b => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function fallbackUuid(): string {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}
