import { Suspense } from 'react';
import { useT } from '../../i18n';
import type { ResolvedWidget } from '../../hooks/useDashboardLayout';
import type { WidgetConfig } from './dashboardLayout';
import { WidgetFrame } from './WidgetFrame';
import { WidgetSkeleton } from './WidgetSkeleton';
import { effectiveWidgetSize } from './widgetSlot';

const NO_CONFIG: WidgetConfig = Object.freeze({});

/**
 * The widget inside its error boundary. Lazy widgets show a skeleton while their code loads; a
 * failed load ends in the error card. Renders no DOM of its own, so empty widgets stay empty.
 */
export function WidgetContent({ widget }: { widget: ResolvedWidget }) {
  const t = useT();
  const Widget = widget.def.component;
  return (
    <WidgetFrame label={widget.def.label(t)}>
      <Suspense fallback={<WidgetSkeleton size={effectiveWidgetSize(widget)} />}>
        <Widget instanceId={widget.instance.instanceId} config={widget.instance.config ?? NO_CONFIG} />
      </Suspense>
    </WidgetFrame>
  );
}
