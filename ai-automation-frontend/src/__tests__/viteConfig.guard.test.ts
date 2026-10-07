import { afterEach, describe, expect, it } from 'vitest';
import type { ConfigEnv, UserConfig } from 'vite';
import viteConfig from '../../vite.config';

// The dev server listens on loopback unless MYSURICATES_DEV_BIND opens it — the same switch the dev
// starter passes to the backend (scripts/start-mysuricates-dev.sh, cd7c53f9).
const ENV: ConfigEnv = { command: 'serve', mode: 'development' };
const SWITCHES = ['MYSURICATES_DEV_BIND', 'CYPRESS_TARGET', 'CYPRESS_API_BASE_URL', 'VITE_API_BASE_URL', 'CYPRESS_WS_BASE_URL', 'VITE_WS_BASE_URL'];
const saved = Object.fromEntries(SWITCHES.map((name) => [name, process.env[name]]));

function serverFor(env: Record<string, string>) {
  for (const name of SWITCHES) delete process.env[name];
  Object.assign(process.env, env);
  const config = (viteConfig as (env: ConfigEnv) => UserConfig)(ENV);
  const proxy = config.server?.proxy as Record<string, { target: string }>;
  return { host: config.server?.host, api: proxy['/api']?.target, ws: proxy['/ws']?.target };
}

describe('vite dev server bind address', () => {
  afterEach(() => {
    for (const name of SWITCHES) {
      if (saved[name] === undefined) delete process.env[name];
      else process.env[name] = saved[name];
    }
  });

  it('listens on 127.0.0.1 by default and proxies to the backend on localhost', () => {
    expect(serverFor({})).toEqual({ host: '127.0.0.1', api: 'http://localhost:9090', ws: 'http://localhost:9090' });
  });

  it('treats an empty switch like an unset one', () => {
    expect(serverFor({ MYSURICATES_DEV_BIND: '  ' }).host).toBe('127.0.0.1');
  });

  it('opens all interfaces with 0.0.0.0, the backend stays on localhost', () => {
    expect(serverFor({ MYSURICATES_DEV_BIND: '0.0.0.0' })).toEqual({
      host: '0.0.0.0',
      api: 'http://localhost:9090',
      ws: 'http://localhost:9090',
    });
  });

  it('follows one address to the backend, which then does not answer on localhost', () => {
    expect(serverFor({ MYSURICATES_DEV_BIND: '100.64.1.2' })).toEqual({
      host: '100.64.1.2',
      api: 'http://100.64.1.2:9090',
      ws: 'http://100.64.1.2:9090',
    });
    expect(serverFor({ MYSURICATES_DEV_BIND: 'fd7a::1' }).api).toBe('http://[fd7a::1]:9090');
  });

  it('keeps an explicit proxy target and the test target', () => {
    expect(serverFor({ MYSURICATES_DEV_BIND: '100.64.1.2', VITE_API_BASE_URL: 'http://localhost:19090/api/v1' }).api)
      .toBe('http://localhost:19090');
    expect(serverFor({ MYSURICATES_DEV_BIND: '100.64.1.2', CYPRESS_TARGET: 'test' }).api).toBe('http://localhost:8090');
  });

  it.each(['foo', '1.2.3.4; rm', '$(id)', 'localhost'])('refuses %s, which is no IP address', (bind) => {
    expect(() => serverFor({ MYSURICATES_DEV_BIND: bind })).toThrow(/Ungueltiges MYSURICATES_DEV_BIND/);
  });
});
