import React from 'react';
import ReactDOM from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from './App';
import { ErrorBoundary } from './components/shared/ErrorBoundary';
import { appQueryDefaults } from './api/queryDefaults';
import { useThemeStore } from './stores/themeStore';
import { installGlobalErrorHandlers } from './utils/errorReporter';
import './index.css';

// Install global error handlers before anything else
installGlobalErrorHandlers();

// Initialize TanStack Query client (defaults incl. 429-Backoff-Ableitung: api/queryDefaults)
const queryClient = new QueryClient({ defaultOptions: appQueryDefaults });

// Apply persisted theme on load
useThemeStore.getState().apply();

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <App />
      </QueryClientProvider>
    </ErrorBoundary>
  </React.StrictMode>
);
