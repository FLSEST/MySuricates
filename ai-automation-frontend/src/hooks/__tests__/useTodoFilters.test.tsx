import { describe, it, expect, beforeEach } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { useTodoFilters } from '../useTodoFilters';

function wrapper({ children }: { children: ReactNode }) {
  return <MemoryRouter initialEntries={['/todos']}>{children}</MemoryRouter>;
}

describe('useTodoFilters page', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('keeps the page while the filters stay the same', () => {
    const { result } = renderHook(() => useTodoFilters(), { wrapper });
    act(() => result.current.setPage(2));
    expect(result.current.page).toBe(2);
    act(() => result.current.setPage((p) => p + 1));
    expect(result.current.page).toBe(3);
  });

  it('starts at page 0 again after a filter change', () => {
    const { result } = renderHook(() => useTodoFilters(), { wrapper });
    act(() => result.current.setPage(2));
    act(() => result.current.setStatusFilter('ALL'));
    expect(result.current.page).toBe(0);
    // Paging within the new filter works from there
    act(() => result.current.setPage((p) => p + 1));
    expect(result.current.page).toBe(1);
  });

  it('starts at page 0 again after a view mode change', () => {
    const { result } = renderHook(() => useTodoFilters(), { wrapper });
    act(() => result.current.setPage(2));
    act(() => result.current.setViewMode('board'));
    expect(result.current.page).toBe(0);
  });
});
