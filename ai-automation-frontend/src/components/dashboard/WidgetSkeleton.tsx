import type { WidgetSize } from './dashboardLayout';

/** Placeholder while a widget's data loads, roughly as tall as the real card. */
export function WidgetSkeleton({ size }: { size: WidgetSize }) {
  return (
    <div
      data-testid="widget-skeleton"
      aria-hidden="true"
      className={`animate-pulse rounded-xl border border-border bg-surface-raised ${size === 'stat' ? 'h-[70px]' : 'h-64'}`}
    />
  );
}
