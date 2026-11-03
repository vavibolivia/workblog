import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, errorMessage, formatDate, type PostDetail, type PostSummary } from '../lib/api'
import { useAuth } from '../lib/auth'
import { FRONT_CHANGED, frontApi, notifyFrontChanged, type FrontItem, type FrontKind } from '../lib/frontApi'
import { useMmSheet } from '../lib/useMmSheet'
import { useToast } from '../lib/toast'
import ManMonthBoard from './ManMonthBoard'
import Modal from './Modal'
import RichContent from './RichContent'

const TAB_KEY = 'workblog.frontTab'
const FOLD_KEY = 'workblog.frontFolded'
const keyOf = (i: { kind: FrontKind; refId: number }) => `${i.kind}:${i.refId}`
const hrefOf = (i: { kind: FrontKind; refId: number }) => (i.kind === 'POST' ? `/posts/${i.refId}` : `/mm/${i.refId}`)
const KIND_LABEL: Record<FrontKind, string> = { POST: '글', MM_SHEET: '공수' }

/** 대문: 홈 맨 위. 관리자가 고른 글을 탭으로(공수 표·발표 자료도 글 안에). 관리자는 [대문 고르기] 로 넣고·빼고·순서를 바꾼다. */
export default function FrontGate() {
  const { user } = useAuth()
  const admin = user?.role === 'ADMIN'
  const [items, setItems] = useState<FrontItem[] | null>(null)
  const [active, setActive] = useState<string | null>(null)
  const [picking, setPicking] = useState(false)
  const [folded, setFolded] = useState(() => { try { return localStorage.getItem(FOLD_KEY) === '1' } catch { return false } })
  const fold = (v: boolean) => { setFolded(v); try { localStorage.setItem(FOLD_KEY, v ? '1' : '0') } catch { /* 무시 */ } }

  const load = useCallback(() => frontApi.list().then((l) => {
    setItems(l)
    let saved: string | null = null
    try { saved = localStorage.getItem(TAB_KEY) } catch { /* 저장소 없음 */ }
    setActive((cur) => {
      const want = cur ?? saved
      return l.some((i) => keyOf(i) === want) ? want : l[0] ? keyOf(l[0]) : null
    })
  }).catch(() => setItems([])), [])

  useEffect(() => {
    load()
    window.addEventListener(FRONT_CHANGED, load)
    return () => window.removeEventListener(FRONT_CHANGED, load)
  }, [load])

  if (items === null) return null
  if (!items.length) {
    if (!admin) return null
    return (
      <section className="front-sec" aria-label="대문">
        <div className="feed-head">
          <div><h2>대문</h2><p>비어 있습니다. 글을 골라 홈 첫 화면에 띄울 수 있습니다.</p></div>
          <button type="button" className="btn btn--primary btn--sm" onClick={() => setPicking(true)}>대문 고르기</button>
        </div>
        {picking && <FrontPicker items={items} onClose={() => setPicking(false)} onSaved={(l) => { setItems(l); setPicking(false); setActive(l[0] ? keyOf(l[0]) : null) }} />}
      </section>
    )
  }
  const cur = items.find((i) => keyOf(i) === active) ?? items[0]
  const pick = (k: string) => { setActive(k); if (folded) fold(false); try { localStorage.setItem(TAB_KEY, k) } catch { /* 무시 */ } }
  return (
    <section className="front-sec" aria-label="대문">
    <div className="feed-head">
      <div><h2>대문</h2><p>관리자가 고른 글 · {items.length}건</p></div>
      <div className="row front__actions" style={{ gap: 6 }}>
        {!folded && <Link to={hrefOf(cur)} className="btn btn--sm">{cur.kind === 'POST' ? '글 열기' : '전체 화면 · 관리'}</Link>}
        {admin && <button type="button" className="btn btn--sm" onClick={() => setPicking(true)}>대문 고르기</button>}
        <button type="button" className="btn btn--sm" onClick={() => fold(!folded)} aria-expanded={!folded} title={folded ? '대문 내용 펼치기' : '대문 내용 접기 — 이 브라우저에서 기억'}>{folded ? '펼치기 ▾' : '접기 ▴'}</button>
      </div>
    </div>
    <div className={`front${folded ? ' front--folded' : ''}`}>
      <div className="front__head">
        <div className="front__tabs" role="tablist" aria-label="대문 항목">
          {items.map((i) => (
            <button key={keyOf(i)} type="button" role="tab" aria-selected={keyOf(i) === keyOf(cur)} className={keyOf(i) === keyOf(cur) ? 'is-on' : undefined} onClick={() => pick(keyOf(i))} title={i.title}>
              <span className="front__title">{i.title}</span>
            </button>
          ))}
        </div>
      </div>
      {!folded && (
        <div role="tabpanel">
          {cur.kind === 'POST' ? <FrontPost key={keyOf(cur)} id={cur.refId} /> : <FrontSheet key={keyOf(cur)} id={cur.refId} />}
        </div>
      )}
    </div>
      {picking && <FrontPicker items={items} onClose={() => setPicking(false)} onSaved={(l) => { setItems(l); setPicking(false) }} />}
    </section>
  )
}

function FrontSheet({ id }: { id: number }) {
  const state = useMmSheet(id)
  if (state.err) return <div className="alert alert--error">{state.err}</div>
  if (!state.sheet) return <div className="spinner" aria-label="불러오는 중" />
  return <ManMonthBoard state={state} compact />
}

function FrontPost({ id }: { id: number }) {
  const [post, setPost] = useState<PostDetail | null>(null)
  const [err, setErr] = useState<string | null>(null)
  useEffect(() => {
    let alive = true
    api.posts.get(id, false).then((p) => { if (alive) setPost(p) }).catch((e) => { if (alive) setErr(errorMessage(e)) })
    return () => { alive = false }
  }, [id])
  if (err) return <div className="alert alert--error">{err}</div>
  if (!post) return <div className="spinner" aria-label="불러오는 중" />
  return (
    <article className="front__post">
      <p className="small muted">{post.category.name} · {post.author.displayName} · {formatDate(post.updatedAt)} · v{post.version}</p>
      <div className="front__post-body post__body"><RichContent content={post.content} format={post.contentFormat} readOnlyEmbeds /></div>
      <div className="front__more"><Link to={`/posts/${post.id}`} className="btn btn--sm btn--primary">계속 읽기 →</Link></div>
    </article>
  )
}

/** 대문 고르기: 왼쪽 = 지금 대문(순서), 오른쪽 = 글 검색해서 추가. 대문에는 글만 — 공수 표는 그 표를 담은 글로. */
function FrontPicker({ items, onClose, onSaved }: { items: FrontItem[]; onClose: () => void; onSaved: (l: FrontItem[]) => void }) {
  const toast = useToast()
  const [list, setList] = useState<{ kind: FrontKind; refId: number; title: string }[]>(items)
  const [q, setQ] = useState('')
  const [posts, setPosts] = useState<PostSummary[]>([])
  const [busy, setBusy] = useState(false)
  const [askClose, setAskClose] = useState(false)
  const dirty = useMemo(() => list.map(keyOf).join() !== items.map(keyOf).join(), [list, items])

  useEffect(() => {
    let alive = true
    const t = window.setTimeout(() => {
      api.posts.list({ q: q.trim() || undefined, size: 12, sort: 'LATEST' }).then((r) => { if (alive) setPosts(r.content) }).catch(() => null)
    }, 250)
    return () => { alive = false; window.clearTimeout(t) }
  }, [q])

  const has = (kind: FrontKind, id: number) => list.some((i) => i.kind === kind && i.refId === id)
  const add = (kind: FrontKind, refId: number, title: string) => { if (!has(kind, refId)) setList((l) => [...l, { kind, refId, title }]) }
  const moveItem = (i: number, d: number) => setList((l) => { const n = [...l]; const j = i + d; if (j < 0 || j >= n.length) return l; [n[i], n[j]] = [n[j], n[i]]; return n })
  const toTop = (i: number) => setList((l) => [l[i], ...l.filter((_, n) => n !== i)])
  const drag = useRef<number | null>(null)
  const dropAt = (to: number) => setList((l) => { const from = drag.current; if (from === null || from === to) return l; const n = [...l]; const [x] = n.splice(from, 1); n.splice(to, 0, x); return n })
  // 저장하지 않은 변경이 있으면 닫기 전에 한 번 묻는다
  const close = () => { if (dirty && !askClose) setAskClose(true); else onClose() }
  const save = async () => {
    setBusy(true)
    try {
      const saved = await frontApi.replace(list.map(({ kind, refId }) => ({ kind, refId })))
      notifyFrontChanged()
      toast('대문을 저장했습니다.', 'success')
      onSaved(saved)
    } catch (e) { toast(errorMessage(e), 'error') } finally { setBusy(false) }
  }

  return (
    <Modal title="대문 고르기" onClose={close} wide
           footer={askClose ? <>
             <span className="small" style={{ marginRight: 'auto', color: 'var(--danger)' }}>저장하지 않은 변경이 있습니다. 버리고 닫을까요?</span>
             <button type="button" className="btn btn--danger" onClick={onClose}>버리고 닫기</button>
             <button type="button" className="btn btn--primary" onClick={save} disabled={busy}>저장하고 닫기</button>
           </> : <>
             <span className="small muted" style={{ marginRight: 'auto' }}>{dirty ? '● 저장하지 않은 변경이 있습니다' : '변경 없음'}</span>
             <button type="button" className="btn" onClick={close}>닫기</button>
             <button type="button" className="btn btn--primary" onClick={save} disabled={busy || !dirty}>저장</button>
           </>}>
      <div className="front-pick">
        <div className="front-pick__col">
          <h4>지금 대문 <span className="muted small">— 위에서부터 탭 순서 · 끌어서 옮기기 ({list.length}/30)</span></h4>
          {list.length === 0 && <p className="muted small">오른쪽에서 글이나 공수 표를 [추가] 하세요.</p>}
          <ol className="front-pick__list">
            {list.map((i, n) => (
              <li key={keyOf(i)} draggable onDragStart={() => { drag.current = n }} onDragOver={(e) => e.preventDefault()} onDrop={() => { dropAt(n); drag.current = null }}>
                <span className="front-pick__grip" aria-hidden>⋮⋮</span>
                <span className="front__kind">{KIND_LABEL[i.kind]}</span>
                <span className="front-pick__title" title={i.title}>{i.title}</span>
                <button type="button" className="btn btn--ghost btn--sm" onClick={() => toTop(n)} disabled={n === 0} title="맨 위로(첫 탭)" aria-label={`${i.title} 맨 위로`}>⤒</button>
                <button type="button" className="btn btn--ghost btn--sm" onClick={() => moveItem(n, -1)} disabled={n === 0} title="위로" aria-label={`${i.title} 위로`}>▲</button>
                <button type="button" className="btn btn--ghost btn--sm" onClick={() => moveItem(n, 1)} disabled={n === list.length - 1} title="아래로" aria-label={`${i.title} 아래로`}>▼</button>
                <button type="button" className="btn btn--ghost btn--sm" onClick={() => setList((l) => l.filter((x) => keyOf(x) !== keyOf(i)))} title="대문에서 빼기" aria-label={`${i.title} 빼기`}>✕</button>
              </li>
            ))}
          </ol>
        </div>
        <div className="front-pick__col">
          <h4>글 찾아 추가 <span className="muted small">— 공수 표는 [공수 표 → 글로 게시] 로 글을 만든 뒤 여기서 고릅니다</span></h4>
          <input className="input" value={q} onChange={(e) => setQ(e.target.value)} placeholder="글 제목·내용으로 찾기 (비우면 최신 글)" aria-label="글 찾기" autoFocus />
          <ul className="front-pick__results">
            {posts.map((p) => (
              <li key={p.id} className={has('POST', p.id) ? 'is-in' : undefined} onClick={() => add('POST', p.id, p.title)}>
                <span className="front-pick__title" title={p.title}>{p.title}<span className="muted small"> · {p.category.name}</span></span>
                <button type="button" className="btn btn--sm" disabled={has('POST', p.id)} onClick={(e) => { e.stopPropagation(); add('POST', p.id, p.title) }}>{has('POST', p.id) ? '추가됨' : '추가'}</button>
              </li>
            ))}
          </ul>
        </div>
      </div>
    </Modal>
  )
}
