import type { LucideIcon } from 'lucide-react';
import {
  Workflow,
  Shield,
  Wand2,
  Code,
  Search,
  FileText,
  Globe,
  Zap,
  Bug,
  GitBranch,
  Database,
  Terminal,
  BookOpen,
  Palette,
  Rocket,
  Settings,
  BarChart,
  Brain,
  Lightbulb,
  Mail,
  Lock,
  Eye,
  RefreshCw,
  Layers,
  Package,
  Cpu,
  Cloud,
  Wrench,
  Target,
  Sparkles,
} from 'lucide-react';

const lucideMap: Record<string, LucideIcon> = {
  Shield,
  Wand2,
  Code,
  Search,
  FileText,
  Globe,
  Zap,
  Bug,
  GitBranch,
  Database,
  Terminal,
  BookOpen,
  Palette,
  Rocket,
  Settings,
  BarChart,
  Brain,
  Lightbulb,
  Mail,
  Lock,
  Eye,
  RefreshCw,
  Layers,
  Package,
  Cpu,
  Cloud,
  Wrench,
  Target,
  Sparkles,
  Workflow,
};

const PASCAL_CASE = /^[A-Z][a-zA-Z0-9]+$/;

export function WorkflowIcon({
  icon,
  className = 'w-5 h-5',
}: {
  icon?: string | null;
  className?: string;
}) {
  if (!icon) {
    return <Workflow className={className} />;
  }

  if (PASCAL_CASE.test(icon)) {
    const Icon = lucideMap[icon] ?? Workflow;
    return <Icon className={className} />;
  }

  return <span className={className} style={{ fontSize: 'inherit', lineHeight: 1 }}>{icon}</span>;
}
