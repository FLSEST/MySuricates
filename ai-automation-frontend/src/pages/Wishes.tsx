import { Hint } from '../components/hints/Hint';
import { useState, useMemo } from 'react';
import { Sparkles } from 'lucide-react';
import { Card, Button, Input, Modal, Spinner } from '../components/ui';
import { EmptyState } from '../components/shared/EmptyState';
import { ErrorState } from '../components/shared/ErrorState';
import { WishAnalytics } from '../components/wishes/WishAnalytics';
import { WishStatusBadge } from '../components/wishes/WishStatusBadge';
import { WishDetail } from '../components/wishes/WishDetail';
import { WishForm } from '../components/wishes/WishForm';
import { useWishes, useCreateWish, useCancelWish, useDeleteWish } from '../hooks/useWishes';
import { useProjects } from '../hooks/useProjects';
import { useWorkflows } from '../hooks/useWorkflows';
import { useT } from '../i18n';
import { formatRelativeTime } from '../utils/formatTime';
import type { Wish } from '../types';

export function Wishes() {
  const t = useT();
  const { data: wishes, isLoading, error, refetch } = useWishes();
  const { data: projects } = useProjects();
  const createWish = useCreateWish();
  const cancelWish = useCancelWish();
  const deleteWish = useDeleteWish();

  const { data: allWorkflows } = useWorkflows();

  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [deleteConfirm, setDeleteConfirm] = useState<{ open: boolean; wish: Wish | null }>({ open: false, wish: null });

  const filtered = useMemo(() => {
    if (!wishes) return [];
    if (!search) return wishes;
    const q = search.toLowerCase();
    return wishes.filter(w => w.originalText.toLowerCase().includes(q) || w.projectName?.toLowerCase().includes(q));
  }, [wishes, search]);

  const selectedWish = useMemo(() => {
    if (selectedId) return filtered.find(w => w.id === selectedId) ?? filtered[0];
    return filtered[0];
  }, [filtered, selectedId]);

  const handleFormSubmit = (data: { text: string; projectId?: string; referenceWorkflowIds?: string[] }) => {
    createWish.mutate(
      data,
      {
        onSuccess: (wish) => {
          setSelectedId(wish.id);
        },
      },
    );
  };

  const handleDelete = () => {
    if (!deleteConfirm.wish) return;
    deleteWish.mutate(deleteConfirm.wish.id, {
      onSuccess: () => {
        setDeleteConfirm({ open: false, wish: null });
        if (selectedId === deleteConfirm.wish?.id) setSelectedId(null);
      },
    });
  };

  if (isLoading) {
    return <div className="flex items-center justify-center h-64"><Spinner text={t.common.loading} /></div>;
  }

  if (error) {
    return <ErrorState message={error.message} onRetry={() => refetch()} />;
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div>
        <h1 className="text-2xl font-bold text-text-primary">{t.wishes.title}</h1>
        <p className="text-text-secondary mt-1">{t.wishes.subtitle}</p>
      </div>

      <Hint id="wishes-loop" />

      {/* Analytics */}
      <WishAnalytics />

      {/* Input Area */}
      <WishForm
        onSubmit={handleFormSubmit}
        isPending={createWish.isPending}
        projects={projects}
        allWorkflows={allWorkflows}
      />

      {/* Content */}
      {(!wishes || wishes.length === 0) ? (
        <EmptyState
          icon={<Sparkles className="w-8 h-8 text-text-tertiary" />}
          title={t.wishes.noWishes}
        />
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          {/* Left: Wish List */}
          <div className="lg:col-span-1 space-y-3">
            <Input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder={t.common.search}
            />
            <div className="space-y-2">
              {filtered.map(wish => (
                <Card
                  key={wish.id}
                  className={`cursor-pointer transition-all ${selectedWish?.id === wish.id ? 'ring-2 ring-accent' : ''}`}
                  onClick={() => setSelectedId(wish.id)}
                >
                  <div className="p-3">
                    <div className="flex items-start justify-between gap-2">
                      <div className="min-w-0 flex-1">
                        <p className="text-sm font-medium text-text-primary line-clamp-2">{wish.originalText}</p>
                        <div className="flex items-center gap-2 mt-1.5">
                          <WishStatusBadge status={wish.status} />
                          {wish.iterationCount > 0 && (
                            <span className="text-xs text-text-tertiary">
                              {t.wishes.iteration} {wish.iterationCount}/{wish.maxIterations}
                            </span>
                          )}
                        </div>
                        {wish.projectName && (
                          <p className="text-xs text-text-tertiary mt-1">{wish.projectName}</p>
                        )}
                      </div>
                      <span className="text-xs text-text-tertiary whitespace-nowrap">
                        {formatRelativeTime(wish.createdAt)}
                      </span>
                    </div>
                  </div>
                </Card>
              ))}
            </div>
          </div>

          {/* Right: Detail Panel */}
          <div className="lg:col-span-2">
            {selectedWish ? (
              <WishDetail
                wishId={selectedWish.id}
                onCancel={() => cancelWish.mutate(selectedWish.id)}
                onDelete={() => setDeleteConfirm({ open: true, wish: selectedWish })}
              />
            ) : (
              <Card className="p-8 text-center text-text-tertiary">
                {t.wishes.noWishes}
              </Card>
            )}
          </div>
        </div>
      )}

      {/* Delete Confirm */}
      <Modal open={deleteConfirm.open} onClose={() => setDeleteConfirm({ open: false, wish: null })} title={t.common.delete}>
        <p className="text-text-secondary mb-4">{t.wishes.deleteConfirm}</p>
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={() => setDeleteConfirm({ open: false, wish: null })}>{t.common.cancel}</Button>
          <Button variant="danger" onClick={handleDelete} disabled={deleteWish.isPending}>{t.common.delete}</Button>
        </div>
      </Modal>
    </div>
  );
}
