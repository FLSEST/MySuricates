import { useRouteError, isRouteErrorResponse } from 'react-router-dom';
import { AlertTriangle, RotateCcw, Home } from 'lucide-react';
import { useEffect } from 'react';
import { reportErrorToBackend } from '../../utils/errorReporter';

export function RouteErrorFallback() {
  const error = useRouteError();

  useEffect(() => {
    reportErrorToBackend(error instanceof Error ? error : new Error(String(error)));
  }, [error]);

  let message: string;
  if (isRouteErrorResponse(error)) {
    message = `${error.status}: ${error.statusText}`;
  } else if (error instanceof Error) {
    message = error.message;
  } else {
    message = 'Ein unerwarteter Fehler ist aufgetreten.';
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-bg-primary p-4">
      <div className="max-w-md w-full text-center space-y-6">
        <div className="flex justify-center">
          <div className="w-16 h-16 rounded-full bg-red-500/10 flex items-center justify-center">
            <AlertTriangle className="w-8 h-8 text-red-500" />
          </div>
        </div>
        <div className="space-y-2">
          <h1 className="text-xl font-semibold text-text-primary">Etwas ist schiefgelaufen</h1>
          <p className="text-sm text-text-secondary">{message}</p>
        </div>
        <div className="flex gap-3 justify-center">
          <button
            onClick={() => window.location.reload()}
            className="inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-accent text-white text-sm font-medium hover:opacity-90 transition-opacity"
          >
            <RotateCcw className="w-4 h-4" />
            Erneut versuchen
          </button>
          <a
            href="/"
            className="inline-flex items-center gap-2 px-4 py-2 rounded-lg border border-border text-text-secondary text-sm font-medium hover:text-text-primary transition-colors"
          >
            <Home className="w-4 h-4" />
            Zur Startseite
          </a>
        </div>
      </div>
    </div>
  );
}
