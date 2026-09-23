import { FileCode2, FileText, Database, Pen, Microscope, Briefcase, ListChecks } from 'lucide-react';
import type { SkillCategory } from '../types';

export const CATEGORY_ICONS: Record<SkillCategory, React.ReactNode> = {
  CODE: <FileCode2 className="w-5 h-5" />,
  DOCUMENT: <FileText className="w-5 h-5" />,
  DATA: <Database className="w-5 h-5" />,
  CONTENT: <Pen className="w-5 h-5" />,
  RESEARCH: <Microscope className="w-5 h-5" />,
  BUSINESS: <Briefcase className="w-5 h-5" />,
  PRODUCTIVITY: <ListChecks className="w-5 h-5" />,
};

export const CATEGORY_COLORS: Record<SkillCategory, string> = {
  CODE: 'text-accent',
  DOCUMENT: 'text-warning',
  DATA: 'text-success',
  CONTENT: 'text-purple-400',
  RESEARCH: 'text-blue-400',
  BUSINESS: 'text-orange-400',
  PRODUCTIVITY: 'text-info',
};

export const SKILL_CATEGORIES: SkillCategory[] = ['CODE', 'DOCUMENT', 'DATA', 'CONTENT', 'RESEARCH', 'BUSINESS', 'PRODUCTIVITY'];
