export default function Pagination({ page, totalPages, onChange }: { page: number; totalPages: number; onChange: (p: number) => void }) {
  if (totalPages <= 1) return null
  const start = Math.max(0, Math.min(page - 2, totalPages - 5))
  const pages = Array.from({ length: Math.min(5, totalPages) }, (_, i) => start + i)
  return (
    <nav className="pagination" aria-label="페이지">
      <button type="button" disabled={page === 0} onClick={() => onChange(page - 1)} aria-label="이전 페이지">‹</button>
      {pages.map((p) => (
        <button type="button" key={p} aria-current={p === page ? 'page' : undefined} onClick={() => onChange(p)}>{p + 1}</button>
      ))}
      <button type="button" disabled={page >= totalPages - 1} onClick={() => onChange(page + 1)} aria-label="다음 페이지">›</button>
    </nav>
  )
}
