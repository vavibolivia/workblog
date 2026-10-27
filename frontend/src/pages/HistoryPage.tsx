import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api, changeTypeLabel, errorMessage, formatDate, type ActivityEntry, type PageResponse } from '../lib/api'
import Sidebar from '../components/Sidebar'
import Pagination from '../components/Pagination'

/** 전사 History: 모든 글의 작성/수정/복원/삭제를 시간순으로. 날짜별로 묶어 보여준다. */
export default function HistoryPage() {
  const [params, setParams] = useSearchParams()
  const page = Number(params.get('page') ?? 0)
  const [data, setData] = useState<PageResponse<ActivityEntry> | null>(null)
  const [err, setErr] = useState<string | null>(null)

  useEffect(() => {
    setData(null); setErr(null)
    api.history.activity(page, 30).then(setData).catch((e) => setErr(errorMessage(e)))
  }, [page])

  const groups = new Map<string, ActivityEntry[]>()
  data?.content.forEach((a) => { const k = formatDate(a.createdAt); groups.set(k, [...(groups.get(k) ?? []), a]) })

  return (
    <div className="shell">
      <Sidebar />
      <main>
        <div className="page-head">
          <h2>변경 이력</h2>
          <p>전사 글의 작성, 수정, 복원, 삭제 기록입니다. 각 항목에서 해당 글과 버전을 바로 확인할 수 있습니다.{data ? ` · ${data.totalElements}건` : ''}</p>
        </div>
        {err && <div className="alert alert--error">{err}</div>}
        {!data && !err && <div className="empty"><div className="spinner" style={{ margin: '0 auto' }} /></div>}
        {data && data.content.length === 0 && <div className="card empty"><strong>아직 기록이 없습니다</strong>글을 쓰면 여기에 이력이 쌓입니다.</div>}
        {data && [...groups.entries()].map(([day, items]) => (
          <section key={day} className="card" style={{ marginBottom: 12 }}>
            <div className="card__head"><h3>{day}</h3><span className="small muted">{items.length}건</span></div>
            <div className="card__body">
              <div className="timeline">
                {items.map((a) => (
                  <div key={a.revisionId} className={`tl-item tl-item--${a.changeType}`}>
                    <div className="tl-item__head">
                      <span style={{ fontWeight: 600, color: 'var(--ink)' }}>{a.editedBy.displayName}</span>
                      {a.editedBy.department && <span>{a.editedBy.department}</span>}
                      <span className={`chip ${a.changeType === 'DELETE' ? 'chip--danger' : a.changeType === 'RESTORE' || a.changeType === 'UNDELETE' ? 'chip--success' : 'chip--neutral'}`} style={{ height: 20, fontSize: 11 }}>{changeTypeLabel[a.changeType]}</span>
                      <span>v{a.version}</span>
                      <span>{formatDate(a.createdAt, true).slice(11)}</span>
                    </div>
                    <div className="tl-item__title"><Link to={`/posts/${a.postId}`}>{a.postTitle}</Link></div>
                    {a.changeNote && <div className="small muted">{a.changeNote}</div>}
                    <div className="small" style={{ marginTop: 2 }}><Link to={`/posts/${a.postId}/history`} style={{ color: 'var(--sky)', fontWeight: 600 }}>이 글의 전체 이력</Link></div>
                  </div>
                ))}
              </div>
            </div>
          </section>
        ))}
        {data && <Pagination page={data.page} totalPages={data.totalPages} onChange={(p) => { const n = new URLSearchParams(params); n.set('page', String(p)); setParams(n) }} />}
      </main>
    </div>
  )
}
