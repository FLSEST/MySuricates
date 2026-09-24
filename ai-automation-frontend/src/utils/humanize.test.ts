import { describe, it, expect } from 'vitest';
import { humanizeName } from './humanize';

describe('humanizeName', () => {
  it('turns snake_case into a sentence-case label', () => {
    expect(humanizeName('project_name')).toBe('Project name');
    expect(humanizeName('target_repo_path')).toBe('Target repo path');
  });

  it('splits camelCase, kebab-case and dotted names', () => {
    expect(humanizeName('repoUrl')).toBe('Repo url');
    expect(humanizeName('max-items')).toBe('Max items');
    expect(humanizeName('input.topic')).toBe('Input topic');
  });

  it('keeps acronyms and single words readable', () => {
    expect(humanizeName('PR_URL')).toBe('PR URL');
    expect(humanizeName('github_PR')).toBe('Github PR');
    expect(humanizeName('topic')).toBe('Topic');
  });

  it('falls back to the raw name when nothing is left', () => {
    expect(humanizeName('__')).toBe('__');
    expect(humanizeName('')).toBe('');
  });
});
