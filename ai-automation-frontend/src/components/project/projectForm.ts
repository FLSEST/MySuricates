import type { ProjectType } from '../../types';

export const PROJECT_TYPES: ProjectType[] = ['CODE', 'RESEARCH', 'CONTENT', 'BUSINESS', 'PERSONAL'];

export interface ProjectFormValues {
  name: string;
  subtitle?: string;
  description?: string;
  type: ProjectType;
  path: string;
}

export const EMPTY_PROJECT_FORM: ProjectFormValues = { name: '', subtitle: '', description: '', type: 'CODE', path: '' };

/** Simple mode's form: name and folder only; the type just labels a project, PERSONAL fits a newcomer's own folder. */
export const COMPACT_PROJECT_FORM: ProjectFormValues = { name: '', type: 'PERSONAL', path: '' };
