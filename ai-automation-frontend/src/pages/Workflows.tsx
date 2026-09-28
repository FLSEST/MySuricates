import { useNavigate } from 'react-router-dom';
import { GitBranch } from 'lucide-react';
import { Button, Spinner } from '../components/ui';
import { EmptyState } from '../components/shared/EmptyState';
import { ErrorState } from '../components/shared/ErrorState';
import { ExecuteDialog } from '../components/workflow/ExecuteDialog';
import { WorkflowListHeader } from '../components/workflow/WorkflowListHeader';
import { WorkflowListToolbar } from '../components/workflow/WorkflowListToolbar';
import { WorkflowViewModeTabs } from '../components/workflow/WorkflowViewModeTabs';
import { SealFilterTabs } from '../components/workflow/SealFilterTabs';
import { WorkflowCreateFlow } from '../components/workflow/WorkflowCreateFlow';
import { WorkflowImportModal } from '../components/workflow/WorkflowImportModal';
import { WorkflowBulkDeleteModal } from '../components/workflow/WorkflowBulkDeleteModal';
import { ProjectGroup } from '../components/workflow/ProjectGroup';
import { DiscoveryView } from '../components/workflow/DiscoveryView';
import { WorkflowDetailPanel } from '../components/workflow/WorkflowDetailPanel';
import { FirstProjectActions } from '../components/project/FirstProjectActions';
import { Hint } from '../components/hints/Hint';
import { useWorkflows } from '../hooks/useWorkflows';
import { useProjects } from '../hooks/useProjects';
import { useSaveWorkflowAsTemplate } from '../hooks/useTemplates';
import { useWorkflowListView } from '../hooks/useWorkflowListView';
import { useWorkflowBulkSelection } from '../hooks/useWorkflowBulkSelection';
import { useWorkflowCreateFlow } from '../hooks/useWorkflowCreateFlow';
import { useWorkflowExecuteFlow } from '../hooks/useWorkflowExecuteFlow';
import { useWorkflowImportFlow } from '../hooks/useWorkflowImportFlow';
import { useT } from '../i18n';

export function Workflows() {
  const t = useT();
  const navigate = useNavigate();
  const { data: workflows, isLoading, error, refetch } = useWorkflows();
  const { data: projects } = useProjects();
  const saveAsTemplate = useSaveWorkflowAsTemplate();
  const view = useWorkflowListView(workflows);
  const bulk = useWorkflowBulkSelection(view.filtered, view.forgetSelection);
  const creation = useWorkflowCreateFlow();
  const execution = useWorkflowExecuteFlow();
  const importing = useWorkflowImportFlow();
  const { search, filtered, selectedWorkflow } = view;
  const { selectMode } = bulk;

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-64">
        <Spinner text={t.common.loading} />
      </div>
    );
  }

  if (error) {
    return <ErrorState message={error.message} onRetry={() => refetch()} />;
  }

  return (
    <div className="flex flex-col h-full animate-fade-in">
      <WorkflowListHeader t={t} onOpenCreate={creation.openCreate} onImportReady={importing.importReady} />
      <Hint id="workflows-start" className="mb-4" />

      {!search && projects?.length === 0 ? (
        <EmptyState
          icon={<GitBranch className="w-8 h-8 text-text-tertiary" />}
          title={t.projects.firstProject.title}
          description={t.projects.firstProject.workflowsHint}
          action={<FirstProjectActions />}
        />
      ) : filtered.length === 0 ? (
        <EmptyState
          icon={<GitBranch className="w-8 h-8 text-text-tertiary" />}
          title={search ? t.common.noResults : t.workflows.title}
          description={search ? undefined : t.workflows.noWorkflows}
          action={
            !search && (
              <Button size="sm" onClick={creation.openCreate}>
                {t.workflows.create}
              </Button>
            )
          }
        />
      ) : (
        <div className="flex gap-4 flex-1 min-h-0">
          <div className="w-full lg:w-96 lg:min-w-[24rem] flex flex-col min-h-0">
            <WorkflowListToolbar
              t={t}
              search={search}
              onSearchChange={view.setSearch}
              selectMode={selectMode}
              onToggleSelectMode={bulk.toggleSelectMode}
              allCollapsed={view.allCollapsed}
              onToggleAll={view.toggleAll}
              selectedIds={bulk.selectedIds}
              filtered={filtered}
              onRequestDeleteSelected={bulk.requestDeleteSelected}
              onBulkToggleAutoTodo={bulk.bulkToggleAutoTodo}
              showDetails={view.showDetails}
              onToggleShowDetails={view.toggleShowDetails}
            />

            <WorkflowViewModeTabs t={t} value={view.viewMode} onChange={view.setViewMode} />
            <div className="mb-2 text-xs">
              <SealFilterTabs t={t} value={view.sealFilter} onChange={view.setSealFilter}
                sealedCount={view.sealedCount} unsealedCount={(workflows?.length ?? 0) - view.sealedCount} />
            </div>

            <div className="flex-1 overflow-y-auto space-y-1 pr-1">
              {view.viewMode === 'discovery' && !selectMode ? (
                <DiscoveryView
                  t={t}
                  workflows={filtered}
                  search={search}
                  onExecute={execution.execute}
                  navigate={navigate}
                />
              ) : view.groupedByProject.map((group) => (
                <ProjectGroup
                  key={group.projectId}
                  projectId={group.projectId}
                  projectName={group.projectName}
                  workflows={group.workflows}
                  collapsed={view.isProjectCollapsed(group.projectId)}
                  onToggle={() => view.toggleProject(group.projectId)}
                  selectedId={selectedWorkflow?.id ?? null}
                  onSelect={(id) => view.setSelectedWorkflowId(id)}
                  onExecute={execution.execute}
                  onDelete={bulk.requestDeleteSingle}
                  navigate={navigate}
                  selectMode={selectMode}
                  selectedIds={bulk.selectedIds}
                  onToggleSelect={bulk.toggleWorkflowSelect}
                  onToggleProjectSelect={bulk.toggleProjectSelect}
                  showDetails={view.showDetails}
                />
              ))}
            </div>
          </div>

          {selectedWorkflow && !selectMode && (
            <div className="hidden lg:block flex-1 min-h-0 overflow-y-auto">
              <WorkflowDetailPanel
                workflow={selectedWorkflow}
                t={t}
                onExecute={() => execution.execute(selectedWorkflow)}
                onEdit={() => navigate(`/workflows/${selectedWorkflow.id}`)}
                onSaveAsTemplate={() => saveAsTemplate.mutate(selectedWorkflow.id)}
                saveAsTemplateLoading={saveAsTemplate.isPending}
                navigate={navigate}
              />
            </div>
          )}
        </div>
      )}

      <WorkflowBulkDeleteModal
        t={t}
        confirm={bulk.deleteConfirm}
        deleting={bulk.deleting}
        onClose={bulk.cancelDelete}
        onConfirm={bulk.confirmDelete}
      />

      <ExecuteDialog
        open={execution.executeDialog.open}
        onClose={execution.closeExecuteDialog}
        onExecute={execution.executeWithContext}
        variables={execution.executeDialog.variables}
        defaultValues={execution.executeDialog.workflow?.defaultContext}
        optionalVariables={execution.schema.optional}
        descriptions={execution.schema.descriptions}
        placeholders={execution.schema.placeholders}
        loading={execution.executing}
      />

      <WorkflowCreateFlow
        t={t}
        locale={creation.locale}
        createStep={creation.createStep}
        selectedTemplate={creation.selectedTemplate}
        form={creation.form}
        projects={projects}
        createLoading={creation.createLoading}
        onFormChange={creation.setForm}
        onTemplateSelect={creation.selectTemplate}
        onPoolSelect={creation.selectPoolEntry}
        onCreate={creation.create}
        onClose={creation.close}
        onBackToTemplates={creation.backToTemplates}
      />

      <WorkflowImportModal
        t={t}
        open={importing.showImport}
        importData={importing.importData}
        importName={importing.importName}
        importProjectId={importing.importProjectId}
        projects={projects}
        importLoading={importing.importLoading}
        onImportNameChange={importing.setImportName}
        onImportProjectIdChange={importing.setImportProjectId}
        onImport={importing.runImport}
        onClose={importing.closeImport}
      />
    </div>
  );
}
