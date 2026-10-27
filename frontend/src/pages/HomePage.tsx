import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api, errorMessage, findCategory, relativeTime, changeTypeLabel, type ActivityEntry, type CategoryNode, type PageResponse, type PostSort, type PostStatus, type PostSummary, type UserResponse } from '../lib/api'
import { useAuth } from '../lib/auth'
import Sidebar from '../components/Sidebar'
import PostCard, { type ListView } from '../components/PostCard'
import Pagination from '../components/Pagination'

const LIST_VIEW_KEY = 'workblog.listView'
const SORT_KEY = 'workblog.sort'
const SORTS: [PostSort, string][] = [['LATEST', '최신순'], ['POPULAR', '인기순'], ['VIEWS', '조회순'], ['COMMENTS', '댓글순'], ['OLDEST', '오래된순']]

function loadSort(): PostSort {
  try { const v = localStorage.getItem(SORT_KEY); return SORTS.some(([k]) => k === v) ? (v as PostSort) : 'LATEST' } catch { return 'LATEST' }
}

function loadListView(): ListView {
  try { return localStorage.getItem(LIST_VIEW_KEY) === 'album' ? 'album' : 'list' } catch { return 'list' }
}

export default function HomePage() {
  const { user } = useAuth()
  const [params, setParams] = useSearchParams()
  const categoryId = params.get('categoryId') ? Number(params.get('categoryId')) : undefined
  const tag = params.get('tag') ?? undefined
  const authorId = params.get('authorId') ? Number(params.get('authorId')) : undefined
  const status = (params.get('status') as PostStatus | null) ?? undefined
  const page = Number(params.get('page') ?? 0)

  const [data, setData] = useState<PageResponse<PostSummary> | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const [author, setAuthor] = useState<UserResponse | null>(null)
  const [activity, setActivity] = useState<ActivityEntry[]>([])
  const [tree, setTree] = useState<CategoryNode[]>([])
  const [view, setView] = useState<ListView>(loadListView)
  const sort = (params.get('sort') as PostSort | null) ?? loadSort()

  const pageSize = view === 'album' ? 12 : 10

  useEffect(() => {
    setErr(null)
    api.posts.list({ categoryId, tag, authorId, status, sort, page, size: pageSize })
      .then(setData).catch((e) => setErr(errorMessage(e)))
  }, [categoryId, tag, authorId, status, sort, page, pageSize])

  useEffect(() => { if (categoryId) api.categories.tree().then(setTree).catch(() => null) }, [categoryId])

  useEffect(() => {
    if (!authorId) { setAuthor(null); return }
    if (user && authorId === user.id) { setAuthor(user); return }
    api.users.list().then((us) => setAuthor(us.find((u) => u.id === authorId) ?? null)).catch(() => setAuthor(null))
  }, [authorId, user])

  useEffect(() => { api.history.activity(0, 8).then((p) => setActivity(p.content)).catch(() => null) }, [])

  const changeView = (v: ListView) => {
    setView(v)
    try { localStorage.setItem(LIST_VIEW_KEY, v) } catch { /* ignore */ }
    if (page !== 0) { const n = new URLSearchParams(params); n.delete('page'); setParams(n) }
  }

  // R21: 선택한 카테고리의 루트→현재 경로(피드 머리 칩)
  const trail: CategoryNode[] = []
  if (categoryId) {
    const walk = (nodes: CategoryNode[], acc: CategoryNode[]): boolean => {
      for (const n of nodes) { if (n.id === categoryId) { trail.push(...acc, n); return true } if (walk(n.children, [...acc, n])) return true }
      return false
    }
    walk(tree, [])
  }

  const title = status === 'DRAFT' ? '임시 저장 글'
    : author ? (author.id === user?.id ? '내가 쓴 글' : `${author.displayName}님의 글`)
    : categoryId ? (findCategory(tree, categoryId)?.name ?? '카테고리') : tag ? `#${tag}` : '전체 글'
  const desc = status === 'DRAFT' ? '아직 발행하지 않은 글입니다. 작성자와 관리자만 볼 수 있습니다.'
    : categoryId ? '이 카테고리와 하위 카테고리에 발행된 글' : tag ? '이 태그가 붙은 글' : author ? (author.department ?? '') : '전사 구성원이 공유한 최신 글'

  const setPage = (p: number) => { const n = new URLSearchParams(params); n.set('page', String(p)); setParams(n) }
  /** 정렬은 URL(공유 가능)과 localStorage(다음 방문) 둘 다에 남긴다. 바꾸면 1페이지로. */
  const changeSort = (s: PostSort) => {
    try { localStorage.setItem(SORT_KEY, s) } catch { /* ignore */ }
    const n = new URLSearchParams(params); n.set('sort', s); n.delete('page'); setParams(n)
  }

  return (
    <div className="shell shell--3">
      <Sidebar />
      <main>
        <div className="feed-head">
          <div>
            <h2>{title}</h2>
            <p>{desc}{data ? ` · ${data.totalElements}건` : ''}</p>
            {trail.length > 0 && (
              <nav className="path-chips" aria-label="카테고리 경로">
                {trail.map((c, i) => (
                  <span key={c.id} className="row" style={{ gap: 4 }}>
                    {i > 0 && <span className="sep" aria-hidden>›</span>}
                    <Link to={`/?categoryId=${c.id}`} data-depth={c.depth} className={c.id === categoryId ? 'is-current' : undefined}>{c.depth}단계 · {c.name}</Link>
                  </span>
                ))}
              </nav>
            )}
          </div>
          <div className="row" style={{ gap: 8 }}>
            {(categoryId || tag || authorId || status) && <Link to="/" className="btn btn--sm">필터 해제</Link>}
            <div className="segmented" role="radiogroup" aria-label="정렬">
              {SORTS.map(([k, label]) => (
                <button key={k} type="button" role="radio" aria-checked={sort === k} className={sort === k ? 'is-on' : undefined} onClick={() => changeSort(k)}>{label}</button>
              ))}
            </div>
            <div className="segmented" role="radiogroup" aria-label="목록 보기 방식">
              <button type="button" role="radio" aria-checked={view === 'list'} className={view === 'list' ? 'is-on' : undefined} onClick={() => changeView('list')}>목록형</button>
              <button type="button" role="radio" aria-checked={view === 'album'} className={view === 'album' ? 'is-on' : undefined} onClick={() => changeView('album')}>앨범형</button>
            </div>
          </div>
        </div>
        {err && <div className="alert alert--error">{err}</div>}
        {!data && !err && <div className="empty"><div className="spinner" style={{ margin: '0 auto' }} /></div>}
        {data && data.content.length === 0 && (
          <div className="card empty">
            <strong>아직 글이 없습니다</strong>
            첫 글을 써서 팀의 지식을 남겨 보세요.
            <div style={{ marginTop: 14 }}><Link to="/write" className="btn btn--primary">글쓰기</Link></div>
          </div>
        )}
        {data && data.content.length > 0 && (
          <>
            <div className={view === 'album' ? 'feed feed--album' : 'feed'}>
              {data.content.map((p) => <PostCard key={p.id} post={p} view={view} />)}
            </div>
            <Pagination page={data.page} totalPages={data.totalPages} onChange={setPage} />
          </>
        )}
      </main>
      <aside className="shell__aside--right stack">
        <div className="card">
          <div className="card__head"><h3>최근 활동</h3><Link to="/history" className="small" style={{ color: 'var(--sky)', fontWeight: 600 }}>전체 이력</Link></div>
          <div className="card__body" style={{ padding: '6px 18px 10px' }}>
            {activity.length === 0 && <p className="small muted" style={{ padding: '8px 0' }}>아직 활동이 없습니다.</p>}
            {activity.map((a) => (
              <div key={a.revisionId} style={{ padding: '8px 0', borderBottom: '1px solid var(--line)' }}>
                <div className="small muted">{a.editedBy.displayName} · {changeTypeLabel[a.changeType]} · {relativeTime(a.createdAt)}</div>
                <Link to={`/posts/${a.postId}`} className="small ellipsis" style={{ display: 'block', fontWeight: 600 }}>{a.postTitle}</Link>
              </div>
            ))}
          </div>
        </div>
      </aside>
    </div>
  )
}
