export function Pagination({
  page,
  totalPages,
  totalElements,
  label,
  setPage,
}: {
  page: number;
  totalPages: number;
  totalElements: number;
  label: string;
  setPage: (fn: (p: number) => number) => void;
}) {
  if (totalPages <= 1) return null;
  return (
    <div className="flex items-center justify-between mt-4">
      <span className="text-sm text-text-secondary">
        {totalElements} {label}
      </span>
      <div className="flex gap-2">
        <button
          onClick={() => setPage((p) => Math.max(0, p - 1))}
          disabled={page === 0}
          className="px-3 py-1.5 text-sm rounded-lg border border-border bg-surface text-text disabled:opacity-40"
        >
          &larr;
        </button>
        <span className="px-3 py-1.5 text-sm text-text-secondary">
          {page + 1} / {totalPages}
        </span>
        <button
          onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
          disabled={page >= totalPages - 1}
          className="px-3 py-1.5 text-sm rounded-lg border border-border bg-surface text-text disabled:opacity-40"
        >
          &rarr;
        </button>
      </div>
    </div>
  );
}
