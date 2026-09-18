import { useT } from '../../i18n';
import type { ResolvedWidget } from '../../hooks/useDashboardLayout';
import type { WidgetConfig } from './dashboardLayout';
import { WidgetFrame } from './WidgetFrame';

const NO_CONFIG: WidgetConfig = Object.freeze({});

/** The widget inside its error boundary. Renders no DOM of its own, so empty widgets stay empty. */
export function WidgetContent({ widget }: { widget: ResolvedWidget }) {
  const t = useT();
  const Widget = widget.def.component;
  return (
    <WidgetFrame label={widget.def.label(t)}>
      <Widget instanceId={widget.instance.instanceId} config={widget.instance.config ?? NO_CONFIG} />
    </WidgetFrame>
  );
}
