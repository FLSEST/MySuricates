import { Component, type ErrorInfo, type ReactNode } from 'react';
import { reportErrorToBackend } from '../../utils/errorReporter';
import { AlertTriangle, RotateCcw, Home } from 'lucide-react';
import { getTranslations } from '../../i18n';

interface Props {
  children: ReactNode;
}

interface State {
  hasError: boolean;
  error: Error | null;
}

export class ErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false, error: null };

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    reportErrorToBackend(error, info.componentStack);
  }

  handleRetry = () => {
    this.setState({ hasError: false, error: null });
  };

  render() {
    if (this.state.hasError) {
      const t = getTranslations();
      return (
        <div className="min-h-screen flex items-center justify-center bg-bg-primary p-4">
          <div className="max-w-md w-full text-center space-y-6">
            <div className="flex justify-center">
              <div className="w-16 h-16 rounded-full bg-danger/10 flex items-center justify-center">
                <AlertTriangle className="w-8 h-8 text-danger" />
              </div>
            </div>
            <div className="space-y-2">
              <h1 className="text-xl font-semibold text-text-primary">{t.errorPage.somethingWentWrong}</h1>
              <p className="text-sm text-text-secondary">
                {this.state.error?.message || t.errorPage.unexpectedError}
              </p>
            </div>
            <div className="flex gap-3 justify-center">
              <button
                onClick={this.handleRetry}
                className="inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-accent text-text-inverse text-sm font-medium hover:opacity-90 transition-opacity"
              >
                <RotateCcw className="w-4 h-4" />
                {t.errorPage.tryAgain}
              </button>
              <a
                href="/"
                className="inline-flex items-center gap-2 px-4 py-2 rounded-lg border border-border text-text-secondary text-sm font-medium hover:text-text-primary transition-colors"
              >
                <Home className="w-4 h-4" />
                {t.errorPage.goHome}
              </a>
            </div>
          </div>
        </div>
      );
    }
    return this.props.children;
  }
}
