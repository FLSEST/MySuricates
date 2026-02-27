const MAX_BUFFER_SIZE = 10;
const FLUSH_INTERVAL_MS = 30_000;

interface ErrorReport {
  message: string;
  stack: string | null;
  source: string;
  url: string;
  timestamp: string;
}

let buffer: ErrorReport[] = [];

function getApiKey(): string | null {
  try {
    const raw = localStorage.getItem('app-settings');
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    return parsed?.state?.apiKey ?? null;
  } catch {
    return null;
  }
}

function flushBuffer() {
  if (buffer.length === 0) return;
  const batch = buffer.splice(0, buffer.length);
  const apiKey = getApiKey();

  for (const report of batch) {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (apiKey) headers['X-API-Key'] = apiKey;

    fetch('/api/v1/system/client-errors', {
      method: 'POST',
      headers,
      body: JSON.stringify(report),
    }).catch(() => {/* fire-and-forget */});
  }
}

function enqueue(report: ErrorReport) {
  buffer.push(report);
  if (buffer.length >= MAX_BUFFER_SIZE) {
    flushBuffer();
  }
}

export function reportErrorToBackend(error: unknown, componentStack?: string | null) {
  const message = error instanceof Error ? error.message : String(error);
  const stack = error instanceof Error
    ? (componentStack ? `${error.stack}\n\nComponent Stack:${componentStack}` : error.stack ?? null)
    : componentStack ?? null;

  enqueue({
    message,
    stack,
    source: componentStack ? 'react' : 'javascript',
    url: window.location.href,
    timestamp: new Date().toISOString(),
  });
}

export function installGlobalErrorHandlers() {
  window.onerror = (_msg, _source, _lineno, _colno, error) => {
    reportErrorToBackend(error ?? _msg);
  };

  window.addEventListener('unhandledrejection', (event) => {
    reportErrorToBackend(event.reason ?? 'Unhandled promise rejection');
  });

  setInterval(flushBuffer, FLUSH_INTERVAL_MS);

  // Flush on page unload
  window.addEventListener('beforeunload', flushBuffer);
}
