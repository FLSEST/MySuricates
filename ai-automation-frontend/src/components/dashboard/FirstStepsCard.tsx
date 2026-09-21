import { useNavigate } from 'react-router-dom';
import { Card, CardHeader } from '../ui';
import { useProjects } from '../../hooks/useProjects';
import { useT } from '../../i18n';
import { FirstProjectActions } from '../project/FirstProjectActions';

/**
 * Shown above the widgets only while the instance has no project at all (TODO d12c7560): a fresh
 * installation otherwise opens on a dashboard of zeros that does not say where to begin.
 */
export function FirstStepsCard() {
  const t = useT();
  const navigate = useNavigate();
  const { data: projects } = useProjects();

  if (projects === undefined || projects.length > 0) return null;

  return (
    <Card data-testid="first-steps">
      <CardHeader title={t.projects.firstProject.dashboardTitle} />
      <p className="text-sm text-text-secondary mb-4">{t.projects.firstProject.workflowsHint}</p>
      <div className="flex">
        <FirstProjectActions onProjectReady={() => navigate('/workflows')} />
      </div>
    </Card>
  );
}
