import {
  FolderKanban, GitBranch, Sparkles, LayoutTemplate, CalendarClock,
  Globe, Link2, Brain, ListChecks, Wand2, MessageSquare, Archive,
} from 'lucide-react';
import { Card } from '../ui';
import { useT } from '../../i18n';
import type { PlatformStats } from '../../types';

function MiniStat({ icon, label, value, sub }: { icon: React.ReactNode; label: string; value: number | string; sub?: string }) {
  return (
    <Card className="flex items-center gap-2.5 py-2.5! px-3!">
      <div className="p-1.5 rounded-md bg-accent/10">{icon}</div>
      <div className="min-w-0">
        <p className="text-[10px] text-text-tertiary truncate">{label}</p>
        <p className="text-sm font-bold text-text">{value}</p>
        {sub && <p className="text-[10px] text-text-secondary truncate">{sub}</p>}
      </div>
    </Card>
  );
}

export function PlatformNumbersGrid({ data }: { data?: PlatformStats }) {
  const t = useT();
  if (!data) return null;

  return (
    <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 xl:grid-cols-6 gap-3">
      <MiniStat icon={<FolderKanban className="w-3.5 h-3.5 text-accent" />} label={t.insights.projects} value={data.activeProjects} sub={`${data.archivedProjects} ${t.insights.archived}`} />
      <MiniStat icon={<GitBranch className="w-3.5 h-3.5 text-success" />} label={t.insights.totalWorkflows} value={data.totalWorkflows} />
      <MiniStat icon={<Archive className="w-3.5 h-3.5 text-blue-500" />} label={t.insights.workflowPool} value={data.workflowPoolSize} />
      <MiniStat icon={<Sparkles className="w-3.5 h-3.5 text-warning" />} label={t.insights.skills} value={data.totalSkills} sub={`${data.builtinSkills} ${t.insights.builtin} / ${data.customSkills} ${t.insights.custom}`} />
      <MiniStat icon={<LayoutTemplate className="w-3.5 h-3.5 text-violet-500" />} label={t.insights.templates} value={data.totalTemplates} sub={`${data.builtinTemplates} ${t.insights.builtin} / ${data.customTemplates} ${t.insights.custom}`} />
      <MiniStat icon={<CalendarClock className="w-3.5 h-3.5 text-blue-500" />} label={t.insights.schedules} value={`${data.activeSchedules}/${data.totalSchedules}`} sub={t.insights.active} />
      <MiniStat icon={<Globe className="w-3.5 h-3.5 text-teal-500" />} label={t.insights.webhooks} value={`${data.activeWebhooks}/${data.totalWebhooks}`} sub={t.insights.active} />
      <MiniStat icon={<Link2 className="w-3.5 h-3.5 text-orange-500" />} label={t.insights.chains} value={data.chainStats?.totalChains ?? 0} sub={`${data.chainStats?.totalRuns ?? 0} ${t.insights.runs}`} />
      <MiniStat icon={<ListChecks className="w-3.5 h-3.5 text-rose-500" />} label={t.insights.triggers} value={data.activeTodoTriggers + data.activeCompletionTriggers} sub={`${data.activeTodoTriggers} Todo / ${data.activeCompletionTriggers} Completion`} />
      <MiniStat icon={<Brain className="w-3.5 h-3.5 text-purple-500" />} label={t.insights.memoryEntries} value={data.activeMemoryEntries} />
      <MiniStat icon={<Wand2 className="w-3.5 h-3.5 text-pink-500" />} label={t.insights.wishes} value={data.wishStats?.total ?? 0} sub={`${data.wishStats?.completed ?? 0} ${t.insights.wishCompleted}`} />
      <MiniStat icon={<MessageSquare className="w-3.5 h-3.5 text-cyan-500" />} label={t.insights.assistantSection} value={data.assistantStats?.totalSessions ?? 0} sub={`${t.insights.avgMessages}: ${(data.assistantStats?.avgMessagesPerSession ?? 0).toFixed(1)}`} />
    </div>
  );
}
