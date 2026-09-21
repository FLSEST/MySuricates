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
