import { Loader2 } from 'lucide-react';

interface SpinnerProps {
  size?: 'sm' | 'md' | 'lg';
  text?: string;
}

const sizes = {
  sm: 'w-4 h-4',
  md: 'w-6 h-6',
  lg: 'w-8 h-8',
};

export function Spinner({ size = 'md', text }: SpinnerProps) {
  return (
    <div className="flex items-center justify-center gap-3 py-8">
      <Loader2 className={`${sizes[size]} animate-spin text-accent`} />
      {text && <span className="text-sm text-text-secondary">{text}</span>}
    </div>
  );
}
