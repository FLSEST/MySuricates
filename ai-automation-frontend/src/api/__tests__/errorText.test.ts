import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { useI18nStore } from '../../i18n';
import { de } from '../../i18n/de';
import { en } from '../../i18n/en';
import { api, apiErrorMessage, resetRateLimitBackoff } from '../client';
import { localizedErrorText } from '../errorText';

// Backend errors in the UI's language (TODO 325cc733, db79450d): the backend sends code + params
// (and a messageKey where one code stands for many messages) when they describe the message
// completely, the UI translates them, everything else keeps the server text.

const BACKEND = resolve(__dirname, '../../../../ai-automation-backend/src/main/java/com/automation');

/**
 * Codes without a text of their own: the message comes from the throw site — free text, shown as
 * it is, or an `ErrorMessage` sent with its messageKey and translated from errors.messages.
 */
const FREE_TEXT_CODES = [
  'MODULE_DISABLED',
  'VALIDATION_FAILED',
  'INVALID_ARGUMENT',
  'WORKFLOW_EXECUTION_FAILED',
  'CONTAINER_ERROR',
  'STAGING_ERROR',
  'STATE_CONFLICT',
  'SERVICE_UNAVAILABLE',
  'OPERATION_TIMEOUT',
];

function backendErrorCodes(): string[] {
  const source = readFileSync(join(BACKEND, 'exception/ErrorCode.java'), 'utf8');
  return [...source.matchAll(/^\s{4}([A-Z_]+)\(HttpStatus\./gm)].map((match) => match[1] ?? '');
}

/** Key → English template of the backend's ErrorMessage enum (one string literal each). */
function backendErrorMessages(): Record<string, string> {
  const source = readFileSync(join(BACKEND, 'exception/ErrorMessage.java'), 'utf8');
  return Object.fromEntries(
    [...source.matchAll(/^\s{4}([A-Z_]+)\(ErrorCode\.[A-Z_]+, "((?:[^"\\]|\\.)*)"\)[,;]/gm)]
      .map((match) => [match[1] ?? '', (match[2] ?? '').replace(/\\(.)/g, '$1')]),
  );
}

function javaFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return javaFiles(path);
    return name.endsWith('.java') ? [path] : [];
  });
}

const placeholders = (text: string) => [...text.matchAll(/\{(\w+)\}/g)].map((match) => match[1]).sort();

describe('backend error codes', () => {
  it('are each either translated or knowingly left as free text', () => {
    const codes = backendErrorCodes();
    expect(codes.length).toBeGreaterThan(20);

    const translated = Object.keys(en.errors.codes);
    expect([...translated, ...FREE_TEXT_CODES].sort()).toEqual([...codes].sort());
  });

  it('use the same placeholders in German and English', () => {
    for (const [code, text] of Object.entries(en.errors.codes)) {
      expect(placeholders(de.errors.codes[code as keyof typeof de.errors.codes]), code).toEqual(placeholders(text));
    }
  });

  it('name every resource type the backend reports as not found', () => {
    const types = new Set<string>();
    for (const file of javaFiles(BACKEND)) {
      for (const match of readFileSync(file, 'utf8').matchAll(/new (?:ResourceNotFound|ElementNotFound|LocalizedEntityNotFound)Exception\("([^"]+)"/g)) {
        if (match[1]) types.add(match[1]);
      }
    }
    expect(types.size).toBeGreaterThan(10);

    const missing = [...types].filter((type) => !(type in en.errors.resources)).sort();
    expect(missing, 'add these to errors.resources in de.ts and en.ts').toEqual([]);
  });
});

describe('backend error messages', () => {
  it('have a text for every key of ErrorMessage.java, in English word for word', () => {
    const backend = backendErrorMessages();
    expect(Object.keys(backend).length).toBeGreaterThan(10);

    expect(en.errors.messages).toEqual(backend);
  });

  it('use the same placeholders in German and English', () => {
    for (const [key, text] of Object.entries(en.errors.messages)) {
      expect(placeholders(de.errors.messages[key as keyof typeof de.errors.messages]), key).toEqual(placeholders(text));
    }
  });
});

describe('localizedErrorText', () => {
  beforeEach(() => useI18nStore.setState({ locale: 'de' }));
  afterEach(() => useI18nStore.setState({ locale: 'en' }));

  it('translates a code the server sent with params', () => {
    expect(localizedErrorText({
      code: 'WORKFLOW_SEALED',
      params: { name: 'Nightly' },
      error: "Workflow 'Nightly' is sealed — unseal it before deleting it",
    })).toBe('Workflow „Nightly“ ist versiegelt – entsiegle ihn zuerst');
  });

  it('translates a fixed text sent with empty params', () => {
    expect(localizedErrorText({ code: 'INTERNAL_ERROR', params: {}, error: 'Internal server error' })).toBe('Interner Serverfehler');
  });

  it('names the resource type in the UI language, and keeps an unknown one as it is', () => {
    expect(localizedErrorText({ code: 'RESOURCE_NOT_FOUND', params: { resource: 'Project', id: 'p-1' }, error: 'x' }))
      .toBe('Projekt nicht gefunden (ID: p-1)');
    expect(localizedErrorText({ code: 'RESOURCE_NOT_FOUND', params: { resource: 'Gadget', id: 'g-1' }, error: 'x' }))
      .toBe('Gadget nicht gefunden (ID: g-1)');
  });

  it('translates by messageKey where the code stands for many messages', () => {
    expect(localizedErrorText({
      code: 'INVALID_ARGUMENT',
      messageKey: 'INVALID_CRON',
      params: { expression: '* *' },
      error: 'Invalid cron expression: * *',
    })).toBe('Ungültiger Cron-Ausdruck: * *');
    expect(localizedErrorText({ code: 'INVALID_ARGUMENT', messageKey: 'NO_SUCH_KEY', params: {}, error: 'Server text' }))
      .toBe('Server text');
  });

  it('keeps the server text without params — the same code can carry a different message', () => {
    const error = 'Run retention can only be extended while deleting runs is disabled: 90 -> 30 days';
    expect(localizedErrorText({ code: 'RUN_DELETION_DISABLED', error })).toBe(error);
  });

  it('keeps the server text for free-text codes, unknown codes and missing placeholders', () => {
    expect(localizedErrorText({ code: 'INVALID_ARGUMENT', params: {}, error: 'Invalid cron expression: x' }))
      .toBe('Invalid cron expression: x');
    expect(localizedErrorText({ code: 'SOMETHING_NEW', params: {}, error: 'New thing' })).toBe('New thing');
    expect(localizedErrorText({ code: 'WORKFLOW_SEALED', params: {}, error: 'Workflow is sealed' })).toBe('Workflow is sealed');
    expect(localizedErrorText({ error: 'HTTP 502: Bad Gateway' })).toBe('HTTP 502: Bad Gateway');
  });

  it('inserts a value literally, even one that looks like a placeholder', () => {
    expect(localizedErrorText({ code: 'METHOD_NOT_ALLOWED', params: { method: 'GET', path: '/a/{id}' }, error: 'x' }))
      .toBe('Methode nicht erlaubt: GET /a/{id}');
  });
});

describe('API client', () => {
  beforeEach(() => {
    resetRateLimitBackoff();
    useI18nStore.setState({ locale: 'de' });
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
      code: 'PROVIDER_NOT_CONNECTED',
      error: "Integration 'GITHUB' ist nicht verbunden",
      params: { provider: 'GITHUB' },
      timestamp: 'x',
    }), { status: 409, headers: { 'Content-Type': 'application/json' } })));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    useI18nStore.setState({ locale: 'en' });
  });

  it('hands the translated text to error.message and apiErrorMessage', async () => {
    const err = await api.post('/todos/t-1/export', {}).catch((e: unknown) => e);

    expect(err).toMatchObject({ status: 409, code: 'PROVIDER_NOT_CONNECTED', message: 'Integration „GITHUB“ ist nicht verbunden' });
    expect(apiErrorMessage(err, 'Export fehlgeschlagen')).toBe('Export fehlgeschlagen: Integration „GITHUB“ ist nicht verbunden');

    useI18nStore.setState({ locale: 'en' });
    const english = await api.post('/todos/t-1/export', {}).catch((e: unknown) => e);
    expect(english).toMatchObject({ message: "Integration 'GITHUB' is not connected" });
  });

  // 2b73ff48: a refused sealed run names its reason by messageKey. The frontend goes live before the
  // backend restarts, so the old answer (code without params) must keep showing the server text.
  it('shows a seal refusal translated from a new backend and as server text from an old one', async () => {
    const english = "Sealed workflow 'Nightly' was not started: its definition no longer matches the seal";
    const answer = (body: object) => vi.fn(async () => new Response(JSON.stringify({ ...body, timestamp: 'x' }),
      { status: 409, headers: { 'Content-Type': 'application/json' } }));

    vi.stubGlobal('fetch', answer({ code: 'WORKFLOW_TAMPERED', error: english }));
    const old = await api.post('/workflows/w-1/execute', {}).catch((e: unknown) => e);
    expect(old).toMatchObject({ status: 409, message: english });

    vi.stubGlobal('fetch', answer({
      code: 'WORKFLOW_TAMPERED', messageKey: 'SEAL_DEFINITION_CHANGED', params: { name: 'Nightly' }, error: english,
    }));
    const current = await api.post('/workflows/w-1/execute', {}).catch((e: unknown) => e);
    expect(current).toMatchObject({
      status: 409,
      message: 'Der versiegelte Workflow „Nightly“ wurde nicht gestartet: Seine Definition passt nicht mehr zum Siegel',
    });
  });
});
