import { AlertTriangle, RotateCcw } from 'lucide-react';
import { Button } from '../ui';
import { useT } from '../../i18n';

interface ErrorStateProps {
  message?: string;
  onRetry?: () => void;
}

export function ErrorState({ message, onRetry }: ErrorStateProps) {
  const t = useT();

  return (
    <div className="flex flex-col items-center justify-center py-16 text-center animate-fade-in">
      <div className="p-4 rounded-2xl bg-danger-subtle mb-4">
        <AlertTriangle className="w-8 h-8 text-danger" />
      </div>
      <h3 className="text-base font-semibold text-text mb-1">{t.common.error}</h3>
      {message && <p className="text-sm text-text-secondary max-w-sm">{message}</p>}
      {onRetry && (
        <div className="mt-4">
          <Button
            variant="secondary"
            size="sm"
            icon={<RotateCcw className="w-3.5 h-3.5" />}
            onClick={onRetry}
          >
            {t.common.retry}
          </Button>
        </div>
      )}
    </div>
  );
}
