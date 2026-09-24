import { type ButtonHTMLAttributes, forwardRef } from 'react';
import { Loader2 } from 'lucide-react';

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger';
type Size = 'sm' | 'md' | 'lg';

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: Size;
  loading?: boolean;
  icon?: React.ReactNode;
}

const variantStyles: Record<Variant, string> = {
  primary: 'bg-accent text-text-inverse hover:bg-accent-hover',
  secondary: 'bg-surface-raised border border-border text-text hover:bg-surface-overlay hover:border-border-strong',
  ghost: 'text-text-secondary hover:text-text hover:bg-surface-raised',
  danger: 'bg-danger text-white hover:bg-red-700',
};

const sizeStyles: Record<Size, string> = {
  sm: 'px-3 py-1.5 text-xs',
  md: 'px-4 py-2 text-sm',
  lg: 'px-5 py-2.5 text-base',
};

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(
  ({ variant = 'primary', size = 'md', loading, icon, children, className = '', disabled, ...props }, ref) => {
    return (
      <button
        ref={ref}
        className={`btn-base ${variantStyles[variant]} ${sizeStyles[size]} ${className}`}
        {...props}
        // After the spread: an explicit disabled={false} must not lift the lock while loading (660706aa)
        disabled={loading || disabled}
      >
        {loading ? <Loader2 className="w-4 h-4 animate-spin" /> : icon}
        {children}
      </button>
    );
  }
);

Button.displayName = 'Button';
