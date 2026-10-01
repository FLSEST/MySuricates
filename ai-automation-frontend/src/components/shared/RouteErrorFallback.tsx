import { useRouteError, isRouteErrorResponse } from 'react-router-dom';
import { AlertTriangle, RotateCcw, Home } from 'lucide-react';
import { useEffect } from 'react';
import { reportErrorToBackend } from '../../utils/errorReporter';
import { useT } from '../../i18n';

export function RouteErrorFallback() {
  const error = useRouteError();
  const t = useT();

  useEffect(() => {
    reportErrorToBackend(error instanceof Error ? error : new Error(String(error)));
  }, [error]);

  let message: string;
  if (isRouteErrorResponse(error)) {
    message = `${error.status}: ${error.statusText}`;
  } else if (error instanceof Error) {
    message = error.message;
  } else {
    message = t.errorPage.unexpectedError;
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-surface p-4">
      <div className="max-w-md w-full text-center space-y-6">
        <div className="flex justify-center">
          <div className="w-16 h-16 rounded-full bg-danger/10 flex items-center justify-center">
            <AlertTriangle className="w-8 h-8 text-danger" />
          </div>
        </div>
        <div className="space-y-2">
          <h1 className="text-xl font-semibold text-text">{t.errorPage.somethingWentWrong}</h1>
          <p className="text-sm text-text-secondary">{message}</p>
        </div>
        <div className="flex gap-3 justify-center">
          <button
            onClick={() => window.location.reload()}
            className="inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-accent text-text-inverse text-sm font-medium hover:opacity-90 transition-opacity"
          >
            <RotateCcw className="w-4 h-4" />
            {t.errorPage.tryAgain}
          </button>
          <a
            href="/"
            className="inline-flex items-center gap-2 px-4 py-2 rounded-lg border border-border text-text-secondary text-sm font-medium hover:text-text transition-colors"
          >
            <Home className="w-4 h-4" />
            {t.errorPage.goHome}
          </a>
        </div>
      </div>
    </div>
  );
}
