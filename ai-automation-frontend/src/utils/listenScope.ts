import type { ListenScope } from '../types';

const ALL_INTERFACES = new Set(['', '0.0.0.0', '::', '*']);

/**
 * Reach of an address: loopback is this machine, every interface or any other address the network. The same
 * rule as `ListenAddresses.scopeOf` in the backend — used here for what only the browser knows: the Vite
 * dev server's bind and the address the page was opened under (1af11dcc).
 */
export function scopeOfAddress(address: string | null | undefined): ListenScope {
  const a = (address ?? '').trim().toLowerCase().replace(/^\[(.*)\]$/, '$1');
  if (ALL_INTERFACES.has(a)) return 'NETWORK';
  if (a === 'localhost' || a === '::1' || a.startsWith('127.') || a === '::ffff:127.0.0.1') return 'LOCAL';
  return 'NETWORK';
}

/** `scheme://address:port`; every interface shows as 0.0.0.0, IPv6 in brackets. */
export function listenUrl(scheme: string, address: string | null, port: number | string | null): string {
  const host = address == null || ALL_INTERFACES.has(address) ? '0.0.0.0' : address;
  const shown = host.includes(':') && !host.startsWith('[') ? `[${host}]` : host;
  return `${scheme}://${shown}${port == null || port === '' ? '' : `:${port}`}`;
}
