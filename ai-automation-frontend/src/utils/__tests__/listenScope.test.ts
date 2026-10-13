import { describe, expect, it } from 'vitest';
import { listenUrl, scopeOfAddress } from '../listenScope';

describe('scopeOfAddress', () => {
  it('counts loopback in every spelling as this machine', () => {
    for (const address of ['127.0.0.1', '127.0.1.1', '::1', '[::1]', 'localhost', 'LOCALHOST']) {
      expect(scopeOfAddress(address)).toBe('LOCAL');
    }
  });

  it('counts every interface and any other address as the network', () => {
    for (const address of ['', '0.0.0.0', '::', '192.168.0.181', '100.64.0.7', 'myhost.local', null]) {
      expect(scopeOfAddress(address)).toBe('NETWORK');
    }
  });
});

describe('listenUrl', () => {
  it('writes scheme, address and port, every interface as 0.0.0.0 and IPv6 in brackets', () => {
    expect(listenUrl('http', '127.0.0.1', 9090)).toBe('http://127.0.0.1:9090');
    expect(listenUrl('https', null, 8090)).toBe('https://0.0.0.0:8090');
    expect(listenUrl('http', '::1', 9090)).toBe('http://[::1]:9090');
    expect(listenUrl('https', '127.0.0.1', '')).toBe('https://127.0.0.1');
  });
});
