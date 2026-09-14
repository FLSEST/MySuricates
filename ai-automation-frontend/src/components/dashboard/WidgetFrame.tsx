import { Component, type ErrorInfo, type ReactNode } from 'react';
import { AlertTriangle, RotateCcw } from 'lucide-react';
import { getTranslations } from '../../i18n';
import { reportErrorToBackend } from '../../utils/errorReporter';

interface WidgetFrameProps {
  /** Names the failed widget in the error card. */
  label: string;
  children: ReactNode;
}

interface WidgetFrameState {
  error: Error | null;
}

/**
 * Error boundary per dashboard widget: a crashing widget turns into a small card, the rest of
 * the page keeps working. Renders no DOM of its own otherwise, so `empty:hidden` on the slot
 * still collapses widgets that render nothing.
 */
export class WidgetFrame extends Component<WidgetFrameProps, WidgetFrameState> {
  state: WidgetFrameState = { error: null };

  static getDerivedStateFromError(error: Error): WidgetFrameState {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    reportErrorToBackend(error, info.componentStack);
  }

  render() {
    if (!this.state.error) return this.props.children;
    const t = getTranslations();
    return (
      <div
        role="alert"
        className="flex min-h-20 flex-col items-start justify-center gap-1 rounded-xl border border-danger/40 bg-danger-subtle px-4 py-3"
      >
        <p className="flex items-center gap-1.5 text-sm font-medium text-text">
          <AlertTriangle className="h-4 w-4 shrink-0 text-danger" />
          {this.props.label}
        </p>
        <p className="text-xs text-text-secondary">{t.dashboard.widgetFailed}</p>
        <button
          type="button"
          onClick={() => this.setState({ error: null })}
          className="mt-1 inline-flex items-center gap-1 text-xs text-accent hover:text-accent-hover"
        >
          <RotateCcw className="h-3 w-3" />
          {t.common.retry}
        </button>
      </div>
    );
  }
}
