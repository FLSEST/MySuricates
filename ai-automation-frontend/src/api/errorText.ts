import { getTranslations } from '../i18n';
import type { ApiError } from '../types';

type ErrorBody = Pick<ApiError, 'error' | 'code' | 'params' | 'messageKey'>;

/**
 * The text the UI shows for an API error.
 *
 * The server sends `params` only when code and params describe its message completely (TODO
 * 325cc733, backend `CodedException`); then the translation from de.ts/en.ts is shown — of its
 * `messageKey` (backend `ErrorMessage`, TODO db79450d) when there is one, else of its code.
 * Without params, for a code without translation, or when a placeholder has no value, the server
 * text stays — a wrong translation would be worse than an English one.
 */
export function localizedErrorText(body: ErrorBody): string {
  const { codes, messages, resources } = getTranslations().errors;
  const template = !body.params
    ? undefined
    : body.messageKey
      ? (messages as Record<string, string | undefined>)[body.messageKey]
      : body.code
        ? (codes as Record<string, string | undefined>)[body.code]
        : undefined;
  if (!template || !body.params) return body.error;

  const params: Record<string, string> = { ...body.params };
  if (params.resource) params.resource = (resources as Record<string, string | undefined>)[params.resource] ?? params.resource;

  const placeholders = [...template.matchAll(/\{(\w+)\}/g)].map((match) => match[1] ?? '');
  if (placeholders.some((key) => params[key] === undefined)) return body.error;
  return template.replace(/\{(\w+)\}/g, (_, key: string) => params[key] ?? '');
}
