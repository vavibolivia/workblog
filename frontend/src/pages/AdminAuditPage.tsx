import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api, errorMessage, formatDate, type PageResponse, type UserResponse } from '../lib/api'
import { auditApi, viaLabel, type ViewLogEntry } from '../lib/collabApi'
import Pagination from '../components/Pagination'

const PAGE_SIZE = 50

/** 관리자: 글 열람 감사 로그. 같은 사람이 같은 글을 10분 안에 다시 연 것은 1건으로 남는다. */
export default function AdminAuditPage() {
  const [params, setParams] = useSearchParams()
  const [users, setUsers] = useState<UserResponse[]>([])
  const [postId, setPostId] = useState(params.get('postId') ?? '')
  const [userId, setUserId] = useState(params.get('userId') ?? '')
  const [from, setFrom] = useState(params.get('from') ?? '')
  const [to, setTo] = useState(params.get('to') ?? '')
  const page = Math.max(0, Number(params.get('page') ?? 0) || 0)
  const [data, setData] = useState<PageResponse<ViewLogEntry> | null>(null)
  const [err, setErr] = useState<string | null>(null)

  useEffect(() => { api.users.list().then(setUsers).catch(() => null) }, [])

  const load = useCallback(async () => {
    setErr(null)
    const num = (v: string | null) => { const n = Number(v); return v && Number.isFinite(n) && n > 0 ? n : undefined }
    try {
      setData(await auditApi.views({
        postId: num(params.get('postId')), userId: num(params.get('userId')),
        from: params.get('from') || undefined, to: params.get('to') || undefined, page, size: PAGE_SIZE,
      }))
    } catch (e) { setErr(errorMessage(e, '감사 로그를 불러오지 못했습니다.')) }
  }, [params, page])

  useEffect(() => { load() }, [load])

  const apply = (e: FormEvent) => {
    e.preventDefault()
    const next = new URLSearchParams()
    if (postId.trim()) next.set('postId', postId.trim())
    if (userId) next.set('userId', userId)
    if (from) next.set('from', from)
    if (to) next.set('to', to)
    setParams(next)
  }
  const reset = () => { setPostId(''); setUserId(''); setFrom(''); setTo(''); setParams(new URLSearchParams()) }
  const goPage = (p: number) => { const next = new URLSearchParams(params); next.set('page', String(p)); setParams(next) }

  return (
    <div className="shell shell--wide">
      <div className="page-head">
        <h2>감사 로그</h2>
        <p>누가 언제 어떤 글을 열어 봤는지 기록합니다. 같은 사람이 같은 글을 10분 안에 다시 연 것은 한 번으로 남습니다. 웹과 API 토큰 접근을 나눠 표시합니다.</p>
      </div>

      <form className="card audit-filter" onSubmit={apply}>
        <div className="field">
          <label htmlFor="a-post">글 번호</label>
          <input id="a-post" className="input" inputMode="numeric" pattern="[0-9]*" value={postId} onChange={(e) => setPostId(e.target.value)} placeholder="예) 128" />
        </div>
        <div className="field">
          <label htmlFor="a-user">사용자</label>
          <select id="a-user" className="select" value={userId} onChange={(e) => setUserId(e.target.value)}>
            <option value="">전체</option>
            {users.map((u) => <option key={u.id} value={u.id}>{u.displayName} ({u.username}){u.department ? ` · ${u.department}` : ''}</option>)}
          </select>
        </div>
        <div className="field">
          <label htmlFor="a-from">시작일</label>
          <input id="a-from" type="date" className="input" value={from} onChange={(e) => setFrom(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="a-to">종료일</label>
          <input id="a-to" type="date" className="input" value={to} onChange={(e) => setTo(e.target.value)} />
        </div>
        <div className="audit-filter__actions">
          <button type="button" className="btn" onClick={reset}>초기화</button>
          <button type="submit" className="btn btn--primary">조회</button>
        </div>
      </form>

      {err && <div className="alert alert--error">{err}</div>}

      <div className="card table-wrap">
        <table className="table">
          <thead><tr><th>시각</th><th>사용자</th><th>글</th><th>접근</th></tr></thead>
          <tbody>
            {!data && !err && <tr><td colSpan={4} className="empty"><div className="spinner" style={{ margin: '0 auto' }} /></td></tr>}
            {data?.content.map((v) => (
              <tr key={v.id}>
                <td className="small nowrap">{formatDate(v.viewedAt, true)}</td>
                <td>
                  <button type="button" className="link-btn" title="이 사용자만 보기" onClick={() => { setUserId(String(v.user.id)); const n = new URLSearchParams(params); n.set('userId', String(v.user.id)); n.delete('page'); setParams(n) }}>
                    {v.user.displayName}
                  </button>
                  <span className="small muted"> {v.user.username}{v.user.department ? ` · ${v.user.department}` : ''}</span>
                </td>
                <td className="audit-post">
                  <Link to={`/posts/${v.postId}`} className="ellipsis">{v.postTitle}</Link>
                  <span className="small muted"> #{v.postId}{v.postDeleted ? ' · 삭제됨' : ''}</span>
                </td>
                <td><span className={`chip chip--xs ${v.via === 'API_TOKEN' ? 'chip--draft' : 'chip--neutral'}`}>{viaLabel[v.via]}</span></td>
              </tr>
            ))}
            {data && data.content.length === 0 && <tr><td colSpan={4} className="empty">조건에 맞는 열람 기록이 없습니다.</td></tr>}
          </tbody>
        </table>
      </div>
      {data && <p className="small muted" style={{ margin: '10px 2px' }}>전체 {data.totalElements.toLocaleString()}건</p>}
      {data && <Pagination page={data.page} totalPages={data.totalPages} onChange={goPage} />}
    </div>
  )
}
