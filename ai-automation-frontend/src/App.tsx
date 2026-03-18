import { lazy } from 'react';
import { createBrowserRouter, RouterProvider } from 'react-router-dom';
import { Layout } from './components/layout/Layout';
import { RouteErrorFallback } from './components/shared/RouteErrorFallback';
import { ToastContainer } from './components/ui/ToastContainer';
import { useSettingsStore } from './stores/settingsStore';

const Dashboard = lazy(() => import('./pages/Dashboard').then(m => ({ default: m.Dashboard })));
const SimpleDashboard = lazy(() => import('./pages/SimpleDashboard').then(m => ({ default: m.SimpleDashboard })));
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
  return simpleMode ? <SimpleDashboard /> : <Dashboard />;
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
const Runs = lazy(() => import('./pages/Runs').then(m => ({ default: m.Runs })));
const Skills = lazy(() => import('./pages/Skills').then(m => ({ default: m.Skills })));
const Schedules = lazy(() => import('./pages/Schedules').then(m => ({ default: m.Schedules })));
const Webhooks = lazy(() => import('./pages/Webhooks').then(m => ({ default: m.Webhooks })));
const Settings = lazy(() => import('./pages/Settings').then(m => ({ default: m.Settings })));
const Chains = lazy(() => import('./pages/Chains').then(m => ({ default: m.Chains })));
const ChainExecution = lazy(() => import('./pages/ChainExecution').then(m => ({ default: m.ChainExecution })));
const Wishes = lazy(() => import('./pages/Wishes').then(m => ({ default: m.Wishes })));
const Todos = lazy(() => import('./pages/Todos').then(m => ({ default: m.Todos })));
const ActivityLog = lazy(() => import('./pages/ActivityLog').then(m => ({ default: m.ActivityLog })));
const WorkflowFactory = lazy(() => import('./pages/WorkflowFactory').then(m => ({ default: m.WorkflowFactory })));
const Docs = lazy(() => import('./pages/Docs').then(m => ({ default: m.Docs })));

const router = createBrowserRouter([
  {
    path: '/',
    element: <Layout />,
    errorElement: <RouteErrorFallback />,
    children: [
      { index: true, element: <DashboardRoute /> },
      { path: 'projects', element: <Projects /> },
      { path: 'projects/:id', element: <ProjectDetail /> },
      { path: 'wishes', element: <Wishes /> },
      { path: 'workflows', element: <WorkflowsRoute /> },
      { path: 'workflows/:id', element: <WorkflowDetailRoute /> },
      { path: 'workflows/:id/run/:runId', element: <WorkflowExecutionRoute /> },
      { path: 'factory', element: <WorkflowFactory /> },
      { path: 'chains', element: <Chains /> },
      { path: 'chains/:id/run/:runId', element: <ChainExecution /> },
      { path: 'runs', element: <Runs /> },
      { path: 'skills', element: <Skills /> },
      { path: 'schedules', element: <Schedules /> },
      { path: 'webhooks', element: <Webhooks /> },
      { path: 'todos', element: <Todos /> },
      { path: 'activity', element: <ActivityLog /> },
      { path: 'docs', element: <Docs /> },
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
