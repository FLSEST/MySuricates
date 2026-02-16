import { BarChart3 } from 'lucide-react';
import { Card, CardHeader } from '../ui';
import { useT } from '../../i18n';

interface ChartCardProps {
  title: string;
  isEmpty: boolean;
  children: React.ReactNode;
}

export function ChartCard({ title, isEmpty, children }: ChartCardProps) {
  const t = useT();

  return (
    <Card>
      <CardHeader title={title} />
      {isEmpty ? (
        <div className="flex flex-col items-center justify-center py-12 text-text-tertiary">
          <BarChart3 className="w-8 h-8 mb-2 opacity-50" />
          <p className="text-sm">{t.charts.noData}</p>
        </div>
      ) : (
        children
      )}
    </Card>
  );
}
