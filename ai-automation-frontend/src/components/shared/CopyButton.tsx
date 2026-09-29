import { useState } from 'react';
import { Copy, Check } from 'lucide-react';
import { useT } from '../../i18n';

export function CopyButton({ text, className }: { text: string; className?: string }) {
  const t = useT();
  const [copied, setCopied] = useState(false);

  const handleCopy = (e: React.MouseEvent) => {
    e.stopPropagation();
    navigator.clipboard.writeText(text)
      .then(() => {
        setCopied(true);
        setTimeout(() => setCopied(false), 2000);
      })
      .catch(() => { /* ignore - non-HTTPS or permission denied */ });
  };

  return (
    <button
      onClick={handleCopy}
      className={className ?? 'p-1 rounded-sm text-text-tertiary hover:text-accent transition-colors'}
      title={t.common.copy}
      aria-label={t.common.copy}
    >
      {copied ? <Check className="w-3.5 h-3.5 text-success" /> : <Copy className="w-3.5 h-3.5" />}
    </button>
  );
}
