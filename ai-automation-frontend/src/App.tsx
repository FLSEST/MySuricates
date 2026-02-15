import { createBrowserRouter, RouterProvider } from 'react-router-dom';
import { Layout } from './components/layout/Layout';
import { Dashboard } from './pages/Dashboard';
import { Projects } from './pages/Projects';
import { ProjectDetail } from './pages/ProjectDetail';
import { Workflows } from './pages/Workflows';
import { WorkflowDetail } from './pages/WorkflowDetail';
import { WorkflowExecution } from './pages/WorkflowExecution';
import { Skills } from './pages/Skills';
import { Schedules } from './pages/Schedules';
import { Webhooks } from './pages/Webhooks';
import { Settings } from './pages/Settings';

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
      { path: 'skills', element: <Skills /> },
      { path: 'schedules', element: <Schedules /> },
      { path: 'webhooks', element: <Webhooks /> },
      { path: 'settings', element: <Settings /> },
    ],
  },
]);

export function App() {
  return <RouterProvider router={router} />;
}
