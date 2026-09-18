import type { ResolvedWidget } from '../../hooks/useDashboardLayout';
import type { WidgetSize } from './dashboardLayout';
import { WIDGET_SIZE_CLASSES } from './widgetRegistry';

// grid + minmax(0,1fr) lets the card fill the slot in both axes without overflowing (charts).
export const SLOT_CLASSES = 'grid grid-cols-1 grow min-w-0';

/** The size a widget is shown in: the instance's choice, else the registry default. */
export function effectiveWidgetSize({ def, instance }: ResolvedWidget): WidgetSize {
  return instance.size ?? def.size;
}

export function widgetSizeClasses(widget: ResolvedWidget): string {
  return WIDGET_SIZE_CLASSES[effectiveWidgetSize(widget)];
}
