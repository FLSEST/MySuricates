import { useNavigate } from 'react-router-dom';
import { FolderKanban, GitBranch, Zap } from 'lucide-react';
import { Card, CardHeader, Button } from '../ui';
import { useDashboardData } from './dashboardDataContext';
import { useT } from '../../i18n';

export function QuickActionsCard() {
  const t = useT();
  const navigate = useNavigate();
  const { data: stats } = useDashboardData();

  return (
    <Card>
      <CardHeader title={t.dashboard.quickActions} />
      <div className="space-y-2">
        <Button
          variant="secondary"
          size="sm"
          className="w-full justify-start"
          icon={<FolderKanban className="w-4 h-4" />}
          onClick={() => navigate('/projects')}
        >
          {t.projects.create}
        </Button>
        <Button
          variant="secondary"
          size="sm"
          className="w-full justify-start"
          icon={<GitBranch className="w-4 h-4" />}
          onClick={() => navigate('/workflows')}
        >
          {t.workflows.create}
        </Button>
        <Button
          variant="secondary"
          size="sm"
          className="w-full justify-start"
          icon={<Zap className="w-4 h-4" />}
          onClick={() => navigate('/skills')}
        >
          {t.skills.title}
        </Button>
      </div>

      {/* Top workflows */}
      {stats?.topWorkflows && stats.topWorkflows.length > 0 && (
        <div className="mt-6">
          <h4 className="text-xs font-semibold uppercase tracking-wider text-text-tertiary mb-3">
            {t.dashboard.topWorkflows}
          </h4>
          <div className="space-y-2">
            {stats.topWorkflows.slice(0, 5).map((wf) => (
              <div key={wf.workflowId} className="flex items-center justify-between text-sm">
                <span className="text-text-secondary truncate">{wf.workflowName}</span>
                <span className="text-text-tertiary shrink-0 ml-2">{wf.runCount} {t.common.runs}</span>
              </div>
            ))}
          </div>
        </div>
      )}
    </Card>
  );
}
