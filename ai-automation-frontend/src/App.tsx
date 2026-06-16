import { lazy, useEffect, useState } from 'react';
import { createBrowserRouter, RouterProvider, useParams, Navigate } from 'react-router-dom';
import { Layout } from './components/layout/Layout';
import { RouteErrorFallback } from './components/shared/RouteErrorFallback';
import { ToastContainer } from './components/ui/ToastContainer';
import { useSettingsStore } from './stores/settingsStore';
import { api } from './api/client';

const Dashboard = lazy(() => import('./pages/Dashboard').then(m => ({ default: m.Dashboard })));
const SimpleHome = lazy(() => import('./pages/SimpleHome').then(m => ({ default: m.SimpleHome })));
const Projects = lazy(() => import('./pages/Projects').then(m => ({ default: m.Projects })));
const ProjectDetail = lazy(() => import('./pages/ProjectDetail').then(m => ({ default: m.ProjectDetail })));
const Workflows = lazy(() => import('./pages/Workflows').then(m => ({ default: m.Workflows })));
const SimpleWorkflows = lazy(() => import('./pages/SimpleWorkflows').then(m => ({ default: m.SimpleWorkflows })));
const WorkflowDetail = lazy(() => import('./pages/WorkflowDetail').then(m => ({ default: m.WorkflowDetail })));
const SimpleWorkflowDetail = lazy(() => import('./pages/SimpleWorkflowDetail').then(m => ({ default: m.SimpleWorkflowDetail })));
const WorkflowExecution = lazy(() => import('./pages/WorkflowExecution').then(m => ({ default: m.WorkflowExecution })));
const SimpleWorkflowExecution = lazy(() => import('./pages/SimpleWorkflowExecution').then(m => ({ default: m.SimpleWorkflowExecution })));

function DashboardRoute() {
  const { simpleMode } = useSettingsStore();
  return simpleMode ? <SimpleHome /> : <Dashboard />;
}

function WorkflowsRoute() {
  const { simpleMode } = useSettingsStore();
  return simpleMode ? <SimpleWorkflows /> : <Workflows />;
}

function WorkflowDetailRoute() {
  const { simpleMode } = useSettingsStore();
  return simpleMode ? <SimpleWorkflowDetail /> : <WorkflowDetail />;
}

function WorkflowExecutionRoute() {
  const { simpleMode } = useSettingsStore();
  return simpleMode ? <SimpleWorkflowExecution /> : <WorkflowExecution />;
}

function RunsRoute() {
  const { simpleMode } = useSettingsStore();
  return simpleMode ? <SimpleRuns /> : <Runs />;
}

function ProjectsRoute() {
  const { simpleMode } = useSettingsStore();
  return simpleMode ? <SimpleProjects /> : <Projects />;
}

function SchedulesRoute() {
  const { simpleMode } = useSettingsStore();
  return simpleMode ? <SimpleSchedules /> : <Schedules />;
}

function TodosRoute() {
  const { simpleMode } = useSettingsStore();
  return simpleMode ? <SimpleTodos /> : <Todos />;
}

function ActivityRoute() {
  const { simpleMode } = useSettingsStore();
  return simpleMode ? <SimpleActivity /> : <ActivityLog />;
}

/** Redirects /runs/:runId to /workflows/:workflowId/run/:runId */
function RunRedirect() {
  const { runId } = useParams<{ runId: string }>();
  const [workflowId, setWorkflowId] = useState<string | null>(null);
  const [error, setError] = useState(false);

  useEffect(() => {
    if (!runId) { setError(true); return; }
    api.get<{ workflowId: string }>(`/executions/${runId}`)
      .then(run => setWorkflowId(run.workflowId))
      .catch(() => setError(true));
  }, [runId]);

  if (error) return <Navigate to="/runs" replace />;
  if (workflowId) return <Navigate to={`/workflows/${workflowId}/run/${runId}`} replace />;
  return null;
}
const Runs = lazy(() => import('./pages/Runs').then(m => ({ default: m.Runs })));
const SimpleRuns = lazy(() => import('./pages/SimpleRuns').then(m => ({ default: m.SimpleRuns })));
const SimpleProjects = lazy(() => import('./pages/SimpleProjects').then(m => ({ default: m.SimpleProjects })));
const SimpleSchedules = lazy(() => import('./pages/SimpleSchedules').then(m => ({ default: m.SimpleSchedules })));
const SimpleTodos = lazy(() => import('./pages/SimpleTodos').then(m => ({ default: m.SimpleTodos })));
const SimpleActivity = lazy(() => import('./pages/SimpleActivity').then(m => ({ default: m.SimpleActivity })));
const Skills = lazy(() => import('./pages/Skills').then(m => ({ default: m.Skills })));
const Schedules = lazy(() => import('./pages/Schedules').then(m => ({ default: m.Schedules })));
const CalendarPage = lazy(() => import('./pages/Calendar').then(m => ({ default: m.Calendar })));
const Webhooks = lazy(() => import('./pages/Webhooks').then(m => ({ default: m.Webhooks })));
const Integrations = lazy(() => import('./pages/Integrations').then(m => ({ default: m.Integrations })));
const Settings = lazy(() => import('./pages/Settings').then(m => ({ default: m.Settings })));
const Chains = lazy(() => import('./pages/Chains').then(m => ({ default: m.Chains })));
const ChainExecution = lazy(() => import('./pages/ChainExecution').then(m => ({ default: m.ChainExecution })));
const Wishes = lazy(() => import('./pages/Wishes').then(m => ({ default: m.Wishes })));
const Todos = lazy(() => import('./pages/Todos').then(m => ({ default: m.Todos })));
const ProjectCockpit = lazy(() => import('./pages/ProjectCockpit').then(m => ({ default: m.ProjectCockpit })));
const ActivityLog = lazy(() => import('./pages/ActivityLog').then(m => ({ default: m.ActivityLog })));
const DeadLetterQueue = lazy(() => import('./pages/DeadLetterQueue').then(m => ({ default: m.DeadLetterQueue })));
const WorkflowDebug = lazy(() => import('./pages/WorkflowDebug').then(m => ({ default: m.WorkflowDebug })));
const WorkflowFactory = lazy(() => import('./pages/WorkflowFactory').then(m => ({ default: m.WorkflowFactory })));
const Docs = lazy(() => import('./pages/Docs').then(m => ({ default: m.Docs })));
const Reports = lazy(() => import('./pages/Reports').then(m => ({ default: m.Reports })));
const Insights = lazy(() => import('./pages/Insights').then(m => ({ default: m.Insights })));
const DialogImport = lazy(() => import('./pages/DialogImport').then(m => ({ default: m.DialogImport })));
const OnboardingProfiles = lazy(() => import('./pages/OnboardingProfiles').then(m => ({ default: m.OnboardingProfiles })));

const router = createBrowserRouter([
  {
    path: '/',
    element: <Layout />,
    errorElement: <RouteErrorFallback />,
    children: [
      { index: true, element: <DashboardRoute /> },
      { path: 'projects', element: <ProjectsRoute /> },
      { path: 'projects/:id', element: <ProjectDetail /> },
      { path: 'wishes', element: <Wishes /> },
      { path: 'workflows', element: <WorkflowsRoute /> },
      { path: 'workflows/:id', element: <WorkflowDetailRoute /> },
      { path: 'workflows/:id/run/:runId', element: <WorkflowExecutionRoute /> },
      { path: 'factory', element: <WorkflowFactory /> },
      { path: 'dialogs', element: <DialogImport /> },
      { path: 'chains', element: <Chains /> },
      { path: 'chains/:id/run/:runId', element: <ChainExecution /> },
      { path: 'runs', element: <RunsRoute /> },
      { path: 'runs/:runId', element: <RunRedirect /> },
      { path: 'skills', element: <Skills /> },
      { path: 'schedules', element: <SchedulesRoute /> },
      { path: 'calendar', element: <CalendarPage /> },
      { path: 'webhooks', element: <Webhooks /> },
      { path: 'integrations', element: <Integrations /> },
      { path: 'cockpit', element: <ProjectCockpit /> },
      { path: 'todos', element: <TodosRoute /> },
      { path: 'activity', element: <ActivityRoute /> },
      { path: 'dlq', element: <DeadLetterQueue /> },
      { path: 'debug', element: <WorkflowDebug /> },
      { path: 'docs', element: <Docs /> },
      { path: 'reports', element: <Reports /> },
      { path: 'insights', element: <Insights /> },
      { path: 'onboarding-profiles', element: <OnboardingProfiles /> },
      { path: 'settings', element: <Settings /> },
    ],
  },
]);

export function App() {
  return (
    <>
      <RouterProvider router={router} />
      <ToastContainer />
    </>
  );
}
