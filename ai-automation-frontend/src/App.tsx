import { lazy } from 'react';
import { createBrowserRouter, RouterProvider } from 'react-router-dom';
import { Layout } from './components/layout/Layout';
import { ToastContainer } from './components/ui/ToastContainer';

const Dashboard = lazy(() => import('./pages/Dashboard').then(m => ({ default: m.Dashboard })));
const Projects = lazy(() => import('./pages/Projects').then(m => ({ default: m.Projects })));
const ProjectDetail = lazy(() => import('./pages/ProjectDetail').then(m => ({ default: m.ProjectDetail })));
const Workflows = lazy(() => import('./pages/Workflows').then(m => ({ default: m.Workflows })));
const WorkflowDetail = lazy(() => import('./pages/WorkflowDetail').then(m => ({ default: m.WorkflowDetail })));
const WorkflowExecution = lazy(() => import('./pages/WorkflowExecution').then(m => ({ default: m.WorkflowExecution })));
const Runs = lazy(() => import('./pages/Runs').then(m => ({ default: m.Runs })));
const Skills = lazy(() => import('./pages/Skills').then(m => ({ default: m.Skills })));
const Schedules = lazy(() => import('./pages/Schedules').then(m => ({ default: m.Schedules })));
const Webhooks = lazy(() => import('./pages/Webhooks').then(m => ({ default: m.Webhooks })));
const Settings = lazy(() => import('./pages/Settings').then(m => ({ default: m.Settings })));
const ActivityLog = lazy(() => import('./pages/ActivityLog').then(m => ({ default: m.ActivityLog })));

const router = createBrowserRouter([
  {
    path: '/',
    element: <Layout />,
    children: [
      { index: true, element: <Dashboard /> },
      { path: 'projects', element: <Projects /> },
      { path: 'projects/:id', element: <ProjectDetail /> },
      { path: 'workflows', element: <Workflows /> },
      { path: 'workflows/:id', element: <WorkflowDetail /> },
      { path: 'workflows/:id/run/:runId', element: <WorkflowExecution /> },
      { path: 'runs', element: <Runs /> },
      { path: 'skills', element: <Skills /> },
      { path: 'schedules', element: <Schedules /> },
      { path: 'webhooks', element: <Webhooks /> },
      { path: 'activity', element: <ActivityLog /> },
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
