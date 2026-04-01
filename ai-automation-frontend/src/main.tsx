import React from 'react';
import ReactDOM from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from './App';
import { ErrorBoundary } from './components/shared/ErrorBoundary';
import { useThemeStore } from './stores/themeStore';
import { installGlobalErrorHandlers } from './utils/errorReporter';
import './index.css';

// Install global error handlers before anything else
installGlobalErrorHandlers();

// Initialize TanStack Query client
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: (failureCount, error) => {
        // Never retry rate limit errors — retries make the problem worse
        if (error && typeof error === 'object' && 'status' in error && (error as { status: number }).status === 429) return false;
        return failureCount < 1;
      },
      refetchOnWindowFocus: false,
    },
  },
});

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
