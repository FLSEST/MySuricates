interface SectionProps {
  title: string;
  icon?: React.ReactNode;
  borderTop?: boolean;
  children: React.ReactNode;
}

export function Section({ title, icon, borderTop, children }: SectionProps) {
  return (
    <div className={`py-1 ${borderTop ? 'border-t border-border' : ''}`}>
      <div className="px-4 py-1 text-[10px] font-semibold uppercase tracking-wider text-text-tertiary flex items-center gap-1">
        {icon}
        {title}
      </div>
      {children}
    </div>
  );
}
