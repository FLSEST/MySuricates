import { Plug, Calendar, KanbanSquare, TicketCheck, Mail, ListTodo, GitBranch, GitMerge, GitPullRequest, FileText } from 'lucide-react';
import type { ComponentType } from 'react';

/** Maps a provider's declared Lucide icon name (from getIcon()) to a component. */
const ICON_MAP: Record<string, ComponentType<{ className?: string }>> = {
  Calendar,
  KanbanSquare,
  TicketCheck,
  Mail,
  ListTodo,
  // lucide-react 1.x no longer ships brand logos; the provider keeps reporting "Github".
  Github: GitBranch,
  GitMerge,
  GitPullRequest,
  FileText,
};

/** Renders an integration provider's icon by its declared name; falls back to a generic plug. */
export function ProviderIcon({ icon, className = 'w-4 h-4' }: { icon?: string | null; className?: string }) {
  const Icon = (icon && ICON_MAP[icon]) || Plug;
  return <Icon className={className} />;
}
