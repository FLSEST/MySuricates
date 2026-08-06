import { Save } from 'lucide-react';
import { Card, Button, Badge } from '../ui';
import { useCrystallizeWish } from '../../hooks/useWishes';
import { useT } from '../../i18n';
import type { Wish } from '../../types';

export function CrystallizeButton({ wish }: { wish: Wish }) {
  const t = useT();
  const crystallize = useCrystallizeWish();
  const isCrystallized = typeof wish.metadata?.crystallizedTemplateId === 'string';

  if (isCrystallized) {
    return (
      <Card>
        <div className="p-4 flex items-center gap-2">
          <Badge variant="success">
            <Save className="w-3 h-3 mr-1 inline" />
            {t.wishes.crystallized}
          </Badge>
        </div>
      </Card>
    );
  }

  return (
    <Card>
      <div className="p-4">
        <Button
          variant="secondary"
          onClick={() => crystallize.mutate(wish.id)}
          disabled={crystallize.isPending}
          icon={<Save className="w-4 h-4" />}
        >
          {t.wishes.saveAsTemplate}
        </Button>
      </div>
    </Card>
  );
}
