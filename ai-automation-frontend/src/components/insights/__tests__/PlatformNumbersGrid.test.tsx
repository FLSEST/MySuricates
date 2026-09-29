import { beforeEach, describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { PlatformNumbersGrid } from '../PlatformNumbersGrid';
import { useI18nStore } from '../../../i18n';
import type { PlatformStats } from '../../../types';

const stats = {
  activeProjects: 2, archivedProjects: 1, totalWorkflows: 5, workflowPoolSize: 3,
  totalSkills: 4, builtinSkills: 3, customSkills: 1, topSkillsByUsage: [],
  totalTemplates: 6, builtinTemplates: 5, customTemplates: 1,
  activeSchedules: 1, totalSchedules: 2, activeWebhooks: 0, totalWebhooks: 1,
  chainStats: { totalChains: 1, totalRuns: 7 },
  activeTodoTriggers: 3, activeCompletionTriggers: 4, activeMemoryEntries: 8,
  todoStats: {}, wishStats: { total: 2, completed: 1 }, assistantStats: { totalSessions: 3, avgMessagesPerSession: 2.5 },
} as unknown as PlatformStats;

// 7e3135ee: the trigger split under "Triggers" comes from de.ts/en.ts, not a fixed English string
describe('PlatformNumbersGrid — trigger split', () => {
  beforeEach(() => useI18nStore.setState({ locale: 'en' }));

  it('names the todo and completion triggers in English', () => {
    render(<PlatformNumbersGrid data={stats} />);
    expect(screen.getByText('3 Todo / 4 Completion')).toBeInTheDocument();
  });

  it('names them in German when the locale is de', () => {
    useI18nStore.setState({ locale: 'de' });
    render(<PlatformNumbersGrid data={stats} />);
    expect(screen.getByText('3 Todo / 4 Abschluss')).toBeInTheDocument();
  });
});
