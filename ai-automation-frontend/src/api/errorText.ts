import { getTranslations } from '../i18n';
import type { ApiError } from '../types';

type ErrorBody = Pick<ApiError, 'error' | 'code' | 'params'>;

/**
 * The text the UI shows for an API error.
 *
 * The server sends `params` only when code and params describe its message completely (TODO
 * 325cc733, backend `CodedException`); then the code's translation from de.ts/en.ts is shown.
 * Without params, for a code without translation, or when a placeholder has no value, the server
 * text stays — a wrong translation would be worse than an English one.
 */
export function localizedErrorText(body: ErrorBody): string {
  const { codes, resources } = getTranslations().errors;
  const template = body.code && body.params ? (codes as Record<string, string | undefined>)[body.code] : undefined;
  if (!template || !body.params) return body.error;

  const params: Record<string, string> = { ...body.params };
  if (params.resource) params.resource = (resources as Record<string, string | undefined>)[params.resource] ?? params.resource;

  const placeholders = [...template.matchAll(/\{(\w+)\}/g)].map((match) => match[1] ?? '');
  if (placeholders.some((key) => params[key] === undefined)) return body.error;
  return template.replace(/\{(\w+)\}/g, (_, key: string) => params[key] ?? '');
}
