import { useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api, errorMessage, findCategory, relativeTime, changeTypeLabel, type ActivityEntry, type CategoryNode, type PageResponse, type PostSort, type PostStatus, type PostSummary, type UserResponse } from '../lib/api'
import { useAuth } from '../lib/auth'
import { useToast } from '../lib/toast'
import Sidebar from '../components/Sidebar'
import PostCard, { type ListView } from '../components/PostCard'
import Pagination from '../components/Pagination'
import FrontGate from '../components/FrontGate'
import { IcSortAsc, IcSortDesc, IcSortLatest, IcSortTitle, IcViewAlbum, IcViewList } from '../components/Icons'

const LIST_VIEW_KEY = 'workblog.listView'
const SORT_KEY = 'workblog.sort'
/** 화면 정렬 = 기준(날짜·제목) × 방향(오름·내림). 기준을 바꾸면 그 기준의 기본 방향(날짜는 최신 먼저, 제목은 가나다 먼저). */
const SORTS: [PostSort, string][] = [['LATEST', '최신순'], ['OLDEST', '오래된순'], ['TITLE', '가나다순'], ['TITLE_DESC', '가나다 역순']]
const isTitleSort = (s: PostSort) => s === 'TITLE' || s === 'TITLE_DESC'
const isAscSort = (s: PostSort) => s === 'OLDEST' || s === 'TITLE'
const sortLabel = (s: PostSort) => SORTS.find(([k]) => k === s)?.[1] ?? ''

function loadSort(): PostSort {
  try { const v = localStorage.getItem(SORT_KEY); return SORTS.some(([k]) => k === v) ? (v as PostSort) : 'LATEST' } catch { return 'LATEST' }
}

function loadListView(): ListView {
  try { return localStorage.getItem(LIST_VIEW_KEY) === 'album' ? 'album' : 'list' } catch { return 'list' }
}

export default function HomePage() {
  const { user } = useAuth()
  const toast = useToast()
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
  // 화면에 없는 정렬(예전 링크의 POPULAR 등)은 최신순으로
  const urlSort = params.get('sort')
  const sort: PostSort = urlSort && SORTS.some(([k]) => k === urlSort) ? (urlSort as PostSort) : loadSort()

  const pageSize = view === 'album' ? 12 : 10

  useEffect(() => {
    let alive = true
    setErr(null)
    // 카테고리를 고르면 그 카테고리에 직접 단 글만(사이드바 숫자와 같은 기준)
    api.posts.list({ categoryId, includeSub: categoryId ? false : undefined, tag, authorId, status, sort, page, size: pageSize })
      .then((d) => { if (alive) setData(d) }).catch((e) => { if (alive) setErr(errorMessage(e)) })
    return () => { alive = false }
  }, [categoryId, tag, authorId, status, sort, page, pageSize])

  useEffect(() => { if (categoryId) api.categories.tree().then(setTree).catch(() => null) }, [categoryId])

  // 가운데 제목(카테고리 이름) 인라인 편집: 관리자가 제목을 클릭하면 글상자. Enter 저장 · Esc 취소 · 포커스 이탈 시 저장.
  const catNode = categoryId ? findCategory(tree, categoryId) : null
  const canEditCat = !!(categoryId && catNode && user?.role === 'ADMIN')
  const [editingCat, setEditingCat] = useState(false)
  const [catDraft, setCatDraft] = useState('')
  const catRef = useRef<HTMLInputElement>(null)
  useEffect(() => { if (editingCat) { catRef.current?.focus(); catRef.current?.select() } }, [editingCat])
  useEffect(() => { setEditingCat(false) }, [categoryId])
  const startCatEdit = () => { if (!canEditCat || !catNode) return; setCatDraft(catNode.name); setEditingCat(true) }
  const commitCat = async () => {
    if (!editingCat || !catNode || !categoryId) return
    setEditingCat(false)
    const n = catDraft.trim()
    if (!n || n === catNode.name) return
    try {
      await api.categories.update(categoryId, { name: n, parentId: catNode.parentId })
      setTree(await api.categories.tree())
      window.dispatchEvent(new CustomEvent('workblog:categories-changed'))
      toast('카테고리 이름을 바꿨습니다.', 'success')
    } catch (e) { toast(errorMessage(e, '이름을 바꾸지 못했습니다.'), 'error') }
  }

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
    : categoryId ? '이 카테고리에 직접 발행된 글 (하위 카테고리 글은 각 하위 카테고리에서)' : tag ? '이 태그가 붙은 글' : author ? (author.department ?? '') : '전사 구성원이 공유한 최신 글'

  const setPage = (p: number) => { const n = new URLSearchParams(params); n.set('page', String(p)); setParams(n) }
  /** 정렬은 URL(공유 가능)과 localStorage(다음 방문) 둘 다에 남긴다. 바꾸면 1페이지로. */
  const changeSort = (s: PostSort) => {
    try { localStorage.setItem(SORT_KEY, s) } catch { /* ignore */ }
    const n = new URLSearchParams(params); n.set('sort', s); n.delete('page'); setParams(n)
  }

  // 대문은 홈에 처음 들어왔을 때만(주소에 정렬·쪽·분류 등 아무 조건이 없을 때)
  const front = params.toString() === ''
  return (
    <>
    <div className="shell shell--3">
      <Sidebar />
      <main>
        {front && <FrontGate />}
        <div className="feed-head">
          <div>
            {editingCat ? (
              <input ref={catRef} className="feed-head__edit" value={catDraft} maxLength={50} aria-label="카테고리 이름 바꾸기"
                     onChange={(e) => setCatDraft(e.target.value)} onBlur={() => void commitCat()}
                     onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); void commitCat() } else if (e.key === 'Escape') { e.preventDefault(); setEditingCat(false) } }} />
            ) : (
              <h2 className={canEditCat ? 'is-editable' : undefined} title={canEditCat ? '클릭하면 카테고리 이름을 바로 고칠 수 있습니다' : undefined}
                  tabIndex={canEditCat ? 0 : undefined} onClick={startCatEdit}
                  onKeyDown={(e) => { if (canEditCat && (e.key === 'Enter' || e.key === 'F2')) startCatEdit() }}>{title}</h2>
            )}
            <p>{desc}{data ? ` · ${data.totalElements}건` : ''}</p>
            {trail.length > 0 && (
              <nav className="path-chips" aria-label="카테고리 경로">
                {trail.map((c, i) => (
                  <span key={c.id} className="row" style={{ gap: 4 }}>
                    {i > 0 && <span className="sep" aria-hidden>›</span>}
                    <Link to={`/?categoryId=${c.id}`} data-depth={c.depth} className={c.id === categoryId ? 'is-current' : undefined}>
                      {i === trail.length - 1 && (
                        <span className={`cat-kind cat-kind--${c.children.length ? 'branch' : 'leaf'}`}
                              title={c.children.length ? '하위 카테고리가 있는 카테고리' : '끝 카테고리 (하위 없음)'}>{c.children.length ? '▸' : ''}</span>
                      )}
                      {c.depth}단계 · {c.name}</Link>
                  </span>
                ))}
              </nav>
            )}
          </div>
          <div className="row" style={{ gap: 8 }}>
            {/* 정렬: 기준 토글(날짜 ↔ 제목) + 방향 토글(내림 ↔ 오름). 아이콘은 지금 상태 */}
            {(() => {
              const title = isTitleSort(sort), asc = isAscSort(sort)
              const nextKey: PostSort = title ? 'LATEST' : 'TITLE'
              const flip: PostSort = title ? (asc ? 'TITLE_DESC' : 'TITLE') : (asc ? 'LATEST' : 'OLDEST')
              return (
                <div className="segmented segmented--icon" aria-label="정렬">
                  <button type="button" aria-label={`정렬 기준: ${title ? '제목' : '날짜'} (누르면 ${sortLabel(nextKey)})`} title={`${title ? '제목' : '날짜'} 기준 · 누르면 ${sortLabel(nextKey)}`}
                          onClick={() => changeSort(nextKey)}>{title ? <IcSortTitle /> : <IcSortLatest />}</button>
                  <button type="button" aria-label={`정렬 방향: ${sortLabel(sort)} (누르면 ${sortLabel(flip)})`} title={`${sortLabel(sort)} · 누르면 ${sortLabel(flip)}`}
                          onClick={() => changeSort(flip)}>{asc ? <IcSortAsc /> : <IcSortDesc />}</button>
                </div>
              )
            })()}
            {/* 보기 방식도 단추 하나로 번갈아: 목록형 ↔ 앨범형. 아이콘은 지금 보기 */}
            <div className="segmented segmented--icon">
              <button type="button" aria-label={view === 'album' ? '보기: 앨범형 (누르면 목록형)' : '보기: 목록형 (누르면 앨범형)'}
                      title={view === 'album' ? '앨범형 · 누르면 목록형' : '목록형 · 누르면 앨범형'}
                      onClick={() => changeView(view === 'album' ? 'list' : 'album')}>{view === 'album' ? <IcViewAlbum /> : <IcViewList />}</button>
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
            {/* 같은 글이 연달아 나오면(예: 저장 두 번) 가장 최근 것 하나만 */}
            {activity.filter((a, i) => i === 0 || activity[i - 1].postId !== a.postId).map((a) => (
              <div key={a.revisionId} style={{ padding: '8px 0', borderBottom: '1px solid var(--line)' }}>
                <div className="small muted">{a.editedBy.displayName} · {changeTypeLabel[a.changeType]} · {relativeTime(a.createdAt)}</div>
                <Link to={`/posts/${a.postId}`} className="small ellipsis" style={{ display: 'block', fontWeight: 600 }}>{a.postTitle}</Link>
              </div>
            ))}
          </div>
        </div>
      </aside>
    </div>
    </>
  )
}
