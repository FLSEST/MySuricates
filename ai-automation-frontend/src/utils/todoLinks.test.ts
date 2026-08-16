import { describe, it, expect } from 'vitest';
import { todoDeepLink } from './todoLinks';

describe('todoDeepLink', () => {
  it('carries the todo id', () => {
    expect(todoDeepLink({ id: 't1' })).toBe('/todos?todoId=t1');
  });

  it('adds the project so the filter is set before the todo loads', () => {
    expect(todoDeepLink({ id: 't1', projectId: 'p1' })).toBe('/todos?todoId=t1&projectId=p1');
  });

  it('omits a null/empty project instead of sending an empty param', () => {
    expect(todoDeepLink({ id: 't1', projectId: null })).toBe('/todos?todoId=t1');
    expect(todoDeepLink({ id: 't1', projectId: '' })).toBe('/todos?todoId=t1');
  });

  it('encodes ids', () => {
    expect(todoDeepLink({ id: 'a b&c' })).toBe('/todos?todoId=a+b%26c');
  });
});
