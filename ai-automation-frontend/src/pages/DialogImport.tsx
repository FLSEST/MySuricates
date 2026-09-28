import { Hint } from '../components/hints/Hint';
import { MessageCircle, Clock, Sparkles, Layers } from 'lucide-react';
import { Card, Button, Input, Spinner, Toggle } from '../components/ui';
import { ProjectSidebar } from '../components/dialog/ProjectSidebar';
import { SessionCard } from '../components/dialog/SessionCard';
import { ExistingWorkflowsList } from '../components/dialog/ExistingWorkflowsList';
import { SuggestionPanel } from '../components/dialog/SuggestionPanel';
import { projectShortName } from '../components/dialog/dialogSessionFormat';
import { useDialogImport } from '../hooks/useDialogImport';
import { useT } from '../i18n';

export function DialogImport() {
  const t = useT();
  const {
    activeProjects,
    groups, countsLoading, selectedGroupId, selectedGroup, selectGroup,
    search, setSearch, substantialOnly, setSubstantialOnly,
    filteredSessions, sessionsLoading,
    projectWorkflows,
    selectedSession, suggestion, analyze, analyzing,
    create, creating,
  } = useDialogImport();

  return (
    <div className="flex flex-col gap-4 p-6 max-w-screen-xl mx-auto">
      {/* Header */}
      <div className="flex items-center gap-3">
        <div className="p-2 rounded-xl bg-accent/10">
          <MessageCircle className="w-6 h-6 text-accent" />
        </div>
        <div>
          <div className="flex items-center gap-2">
            <h1 className="text-xl font-semibold text-text">{t.dialog.title}</h1>
            <span className="text-xs px-2 py-0.5 rounded-full bg-amber-500/10 text-amber-500 border border-amber-500/20 font-medium">
              {t.dialog.experimental}
            </span>
          </div>
          <p className="text-sm text-text-secondary">{t.dialog.subtitle}</p>
        </div>
      </div>

      <Hint id="dialogs-sessions" />

      {/* 3-Panel-Grid */}
      <div className="grid grid-cols-1 lg:grid-cols-4 gap-4 items-start">

        {/* Panel 1: Projekte */}
        <Card>
          <div className="p-3">
            <p className="text-xs font-semibold text-text-secondary uppercase tracking-wide mb-2 px-1">
              {t.nav.projects}
            </p>
            {countsLoading
              ? <div className="flex justify-center py-4"><Spinner size="sm" /></div>
              : groups.length === 0
                ? <p className="text-xs text-text-secondary px-1">{t.dialog.noSessions}</p>
                : <ProjectSidebar groups={groups} selectedId={selectedGroupId} onSelect={selectGroup} />}
          </div>
        </Card>

        {/* Panel 2: Sessions */}
        <Card>
          <div className="p-3 flex flex-col gap-2">
            <p className="text-xs font-semibold text-text-secondary uppercase tracking-wide px-1">
              {t.dialog.sessions}
              {selectedGroup && <span className="ml-1 font-normal">— {selectedGroup.label}</span>}
            </p>

            {!selectedGroup ? (
              <div className="flex flex-col items-center gap-2 py-10 text-center">
                <Layers className="w-8 h-8 text-text-secondary/30" />
                <p className="text-xs text-text-secondary">{t.dialog.selectProjectFirst}</p>
              </div>
            ) : (
              <>
                <div className="flex items-center gap-2">
                  <div className="flex-1">
                    <Input
                      placeholder={t.common.search}
                      value={search}
                      onChange={e => setSearch(e.target.value)}
                    />
                  </div>
                  <div className="shrink-0 pt-4">
                    <Toggle enabled={substantialOnly} onChange={setSubstantialOnly} />
                  </div>
                </div>

                <div className="flex flex-col gap-1.5 max-h-[65vh] overflow-y-auto pr-0.5">
                  {sessionsLoading ? (
                    <div className="flex justify-center py-8"><Spinner size="sm" /></div>
                  ) : filteredSessions.length === 0 ? (
                    <div className="flex flex-col items-center gap-2 py-8 text-center">
                      <Clock className="w-7 h-7 text-text-secondary/30" />
                      <p className="text-xs text-text-secondary">
                        {substantialOnly ? t.dialog.noSubstantialSessions : t.dialog.noSessions}
                      </p>
                    </div>
                  ) : (
                    filteredSessions.map(s => (
                      <SessionCard
                        key={s.sessionId}
                        session={s}
                        selected={selectedSession?.sessionId === s.sessionId}
                        onClick={() => analyze(s)}
                      />
                    ))
                  )}
                </div>
              </>
            )}
          </div>
        </Card>

        {/* Panel 3: Detail (col-span-2) */}
        <div className="lg:col-span-2 flex flex-col gap-4">
          {selectedGroup && !selectedGroup.isOther && (
            <Card>
              <div className="p-3">
                <p className="text-xs font-semibold text-text-secondary uppercase tracking-wide mb-2">
                  {t.dialog.existingWorkflows}
                </p>
                <ExistingWorkflowsList workflows={projectWorkflows} />
              </div>
            </Card>
          )}

          <Card>
            <div className="p-4">
              {!selectedSession && !analyzing && (
                <div className="flex flex-col items-center justify-center gap-3 py-12 text-center">
                  <MessageCircle className="w-10 h-10 text-text-secondary/30" />
                  <p className="text-sm text-text-secondary">{t.dialog.selectSession}</p>
                </div>
              )}

              {analyzing && (
                <div className="flex flex-col items-center gap-3 py-12">
                  <Spinner />
                  <p className="text-sm text-text-secondary">{t.dialog.analyzing}</p>
                </div>
              )}

              {suggestion && !analyzing && (
                <>
                  <div className="flex items-center justify-between mb-3">
                    <div className="flex items-center gap-2">
                      <Sparkles className="w-4 h-4 text-accent" />
                      <span className="text-sm font-medium text-text">
                        {selectedSession && projectShortName(selectedSession.projectPath)}
                      </span>
                    </div>
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => selectedSession && analyze(selectedSession)}
                    >
                      {t.dialog.reanalyze}
                    </Button>
                  </div>
                  <SuggestionPanel
                    suggestion={suggestion}
                    projects={activeProjects.map(p => ({ id: p.id, name: p.name }))}
                    onCreateWorkflow={create}
                    isCreating={creating}
                  />
                </>
              )}
            </div>
          </Card>
        </div>
      </div>
    </div>
  );
}
