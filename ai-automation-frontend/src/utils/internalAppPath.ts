/**
 * True only for app-internal paths that may safely go to react-router's navigate().
 *
 * A path starting with "//" or "/\\" is protocol-relative or is resolved by browsers and
 * older react-router versions as an external target — "/\\evil.example" thus ends up
 * outside the app (GHSA open redirect, react-router <= 7.17.0). Assistant answers and
 * rendered Markdown are LLM text and may contain untrusted content from workflow outputs or
 * webhooks, so it is checked here instead of trusted.
 */
export function isInternalAppPath(href: string | undefined): href is string {
  if (!href || !href.startsWith('/')) return false;
  const second = href[1];
  return second !== '/' && second !== '\\';
}
