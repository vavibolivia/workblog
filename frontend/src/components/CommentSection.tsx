import { useCallback, useEffect, useMemo, useRef, useState, type KeyboardEvent, type ReactNode } from 'react'
import { useLocation } from 'react-router-dom'
import { api, errorMessage, relativeTime, type UserResponse } from '../lib/api'
import { COMMENT_MAX_LENGTH, commentsApi, type CommentResponse } from '../lib/commentsApi'
import { mentionQueryAt } from '../lib/collabApi'
import { useToast } from '../lib/toast'
import { Avatar } from './Avatar'
import Modal from './Modal'

/** 글 하단 댓글 영역: 댓글 목록 → 답글(1단계 들여쓰기) → 맨 아래 입력창. 네이버 블로그 댓글 흐름을 따른다. */
export default function CommentSection({ postId }: { postId: number }) {
  const toast = useToast()
  const [items, setItems] = useState<CommentResponse[] | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const [replyTo, setReplyTo] = useState<number | null>(null)
  const [editing, setEditing] = useState<number | null>(null)
  const [confirmDelete, setConfirmDelete] = useState<number | null>(null)
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    try { setItems(await commentsApi.list(postId)); setErr(null) }
    catch (e) { setErr(errorMessage(e, '댓글을 불러오지 못했습니다.')) }
  }, [postId])

  useEffect(() => { setItems(null); setReplyTo(null); setEditing(null); load() }, [load])

  // 알림에서 온 #comment-ID 로 스크롤하고 잠깐 강조한다.
  const { hash } = useLocation()
  useEffect(() => {
    if (!items || !hash.startsWith('#comment-')) return
    const el = document.getElementById(hash.slice(1))
    if (!el) return
    el.scrollIntoView({ behavior: 'smooth', block: 'center' })
    el.classList.add('comment--focus')
    const t = window.setTimeout(() => el.classList.remove('comment--focus'), 2400)
    return () => window.clearTimeout(t)
  }, [items, hash])

  const count = useMemo(() => (items ?? []).filter((c) => !c.deleted).length, [items])
  const roots = useMemo(() => (items ?? []).filter((c) => c.parentId === null), [items])
  const repliesOf = useMemo(() => {
    const map = new Map<number, CommentResponse[]>()
    for (const c of items ?? []) {
      if (c.parentId === null) continue
      const list = map.get(c.parentId) ?? []
      list.push(c); map.set(c.parentId, list)
    }
    return map
  }, [items])

  const submit = async (content: string, parentId: number | null) => {
    setBusy(true)
    try {
      await commentsApi.create(postId, content, parentId)
      setReplyTo(null)
      await load()
      toast(parentId === null ? '댓글을 등록했습니다.' : '답글을 등록했습니다.', 'success')
      return true
    } catch (e) { toast(errorMessage(e), 'error'); return false } finally { setBusy(false) }
  }

  const saveEdit = async (id: number, content: string) => {
    setBusy(true)
    try {
      await commentsApi.update(postId, id, content)
      setEditing(null)
      await load()
      toast('댓글을 수정했습니다.', 'success')
      return true
    } catch (e) { toast(errorMessage(e), 'error'); return false } finally { setBusy(false) }
  }

  const remove = async () => {
    if (confirmDelete === null) return
    setBusy(true)
    try {
      await commentsApi.remove(postId, confirmDelete)
      await load()
      toast('댓글을 삭제했습니다.', 'success')
    } catch (e) { toast(errorMessage(e), 'error') } finally { setBusy(false); setConfirmDelete(null) }
  }

  const renderItem = (c: CommentResponse, isReply: boolean) => (
    <li key={c.id} id={`comment-${c.id}`} className={`comment${isReply ? ' comment--reply' : ''}${c.deleted ? ' comment--deleted' : ''}`}>
      <Avatar name={c.author.displayName} size={isReply ? 'xs' : undefined} />
      <div className="comment__main">
        <div className="comment__meta">
          <strong>{c.author.displayName}</strong>
          {c.author.department && <span>{c.author.department}</span>}
          <span>{relativeTime(c.createdAt)}</span>
          {!c.deleted && c.updatedAt !== c.createdAt && <span>수정됨</span>}
        </div>
        {c.deleted ? (
          <p className="comment__content comment__content--deleted">삭제된 댓글입니다.</p>
        ) : editing === c.id ? (
          <CommentComposer
            initial={c.content ?? ''}
            placeholder="댓글을 입력하세요"
            submitLabel="저장"
            busy={busy}
            autoFocus
            onSubmit={(text) => saveEdit(c.id, text)}
            onCancel={() => setEditing(null)}
          />
        ) : (
          <p className="comment__content">{linkify(c.content ?? '')}</p>
        )}
        {!c.deleted && editing !== c.id && (
          <div className="comment__actions">
            <button type="button" className="comment__link" onClick={() => { setReplyTo(replyTo === c.id ? null : c.id); setEditing(null) }}>
              {replyTo === c.id ? '답글 취소' : '답글 달기'}
            </button>
            {c.editable && <>
              <button type="button" className="comment__link" onClick={() => { setEditing(c.id); setReplyTo(null) }}>수정</button>
              <button type="button" className="comment__link comment__link--danger" onClick={() => setConfirmDelete(c.id)}>삭제</button>
            </>}
          </div>
        )}
        {c.deleted && (
          <div className="comment__actions">
            <button type="button" className="comment__link" onClick={() => { setReplyTo(replyTo === c.id ? null : c.id); setEditing(null) }}>
              {replyTo === c.id ? '답글 취소' : '답글 달기'}
            </button>
          </div>
        )}
      </div>
    </li>
  )

  const renderReplyBox = (parentId: number) => (
    <li key={`reply-${parentId}`} className="comment comment--reply comment--composer">
      <span className="comment__arrow" aria-hidden>↳</span>
      <div className="comment__main">
        <CommentComposer
          placeholder="답글을 입력하세요"
          submitLabel="답글 등록"
          busy={busy}
          autoFocus
          onSubmit={(text) => submit(text, parentId)}
          onCancel={() => setReplyTo(null)}
        />
      </div>
    </li>
  )

  return (
    <section className="comments" aria-label="댓글">
      <h2 className="comments__title">댓글 <span className="comments__count">{count}</span></h2>

      {err && <div className="alert alert--error">{err}</div>}
      {!items && !err && <div className="comments__loading"><div className="spinner" /></div>}
      {items && items.length === 0 && <p className="comments__empty">첫 댓글을 남겨 보세요.</p>}

      {items && items.length > 0 && (
        <ul className="comments__list">
          {roots.map((root) => {
            const replies = repliesOf.get(root.id) ?? []
            const rows = [renderItem(root, false), ...replies.map((r) => renderItem(r, true))]
            // 답글 입력창은 그 묶음(댓글 + 답글들)의 맨 아래에 연다.
            if (replyTo === root.id || replies.some((r) => r.id === replyTo)) rows.push(renderReplyBox(root.id))
            return rows
          })}
        </ul>
      )}

      <div className="comments__composer">
        <CommentComposer
          placeholder="댓글을 입력하세요"
          submitLabel="등록"
          busy={busy}
          onSubmit={(text) => submit(text, null)}
        />
      </div>

      {confirmDelete !== null && (
        <Modal title="댓글을 삭제할까요?" onClose={() => setConfirmDelete(null)}
               footer={<>
                 <button type="button" className="btn" onClick={() => setConfirmDelete(null)}>취소</button>
                 <button type="button" className="btn btn--danger" disabled={busy} onClick={remove}>삭제</button>
               </>}>
          <p>삭제한 댓글은 되돌릴 수 없습니다. 답글이 달린 댓글은 '삭제된 댓글입니다'로 자리가 남습니다.</p>
        </Modal>
      )}
    </section>
  )
}

const URL_RE = /https?:\/\/[^\s<>"'`]+/g

/** 평문 댓글 안의 http(s) 주소만 새 창 링크로 바꾼다(R11). 끝에 붙은 문장 부호는 링크에서 뺀다. */
function linkify(text: string): ReactNode[] {
  const out: ReactNode[] = []
  let last = 0
  for (const m of text.matchAll(URL_RE)) {
    let url = m[0]
    const trail = /[.,;:!?)\]}]+$/.exec(url)?.[0] ?? ''
    url = url.slice(0, url.length - trail.length)
    const start = m.index
    if (start > last) out.push(text.slice(last, start))
    out.push(<a key={start} href={url} target="_blank" rel="noopener noreferrer" className="comment__url">{url}</a>)
    last = start + url.length
  }
  if (last < text.length) out.push(text.slice(last))
  return out
}

interface ComposerProps {
  initial?: string
  placeholder: string
  submitLabel: string
  busy: boolean
  autoFocus?: boolean
  onSubmit: (text: string) => Promise<boolean>
  onCancel?: () => void
}

/** @멘션 자동완성용 구성원 목록. 화면에서 한 번만 불러온다(GET /api/users). */
let usersCache: Promise<UserResponse[]> | null = null
function loadUsers(): Promise<UserResponse[]> {
  if (!usersCache) usersCache = api.users.list().then((l) => l.filter((u) => u.active)).catch(() => { usersCache = null; return [] })
  return usersCache
}

const MENTION_LIMIT = 6

/** 텍스트 입력 + 등록. Ctrl+Enter(맥은 Cmd+Enter)로도 등록한다. @ 를 치면 구성원 자동완성(↑↓ 선택, Enter·Tab 넣기, Esc 닫기). */
function CommentComposer({ initial = '', placeholder, submitLabel, busy, autoFocus, onSubmit, onCancel }: ComposerProps) {
  const [text, setText] = useState(initial)
  const trimmed = text.trim()
  const over = text.length > COMMENT_MAX_LENGTH
  const canSubmit = trimmed.length > 0 && !over && !busy
  const areaRef = useRef<HTMLTextAreaElement>(null)
  const [mention, setMention] = useState<{ start: number; query: string } | null>(null)
  const [users, setUsers] = useState<UserResponse[]>([])
  const [active, setActive] = useState(0)

  const matches = useMemo(() => {
    if (!mention) return []
    const q = mention.query.toLowerCase()
    const starts = users.filter((u) => u.username.toLowerCase().startsWith(q) || u.displayName.toLowerCase().startsWith(q))
    const rest = q ? users.filter((u) => !starts.includes(u) && (u.username.toLowerCase().includes(q) || u.displayName.toLowerCase().includes(q))) : []
    return [...starts, ...rest].slice(0, MENTION_LIMIT)
  }, [mention, users])

  const detect = (value: string, caret: number) => {
    const m = mentionQueryAt(value, caret)
    setMention(m)
    setActive(0)
    if (m) loadUsers().then(setUsers)
  }

  const pick = (u: UserResponse) => {
    const el = areaRef.current
    if (!mention || !el) return
    const caret = el.selectionStart ?? text.length
    const insert = `@${u.username} `
    const next = text.slice(0, mention.start) + insert + text.slice(caret)
    setText(next); setMention(null)
    const pos = mention.start + insert.length
    window.requestAnimationFrame(() => { el.focus(); el.setSelectionRange(pos, pos) })
  }

  const send = async () => {
    if (!canSubmit) return
    const ok = await onSubmit(trimmed)
    if (ok) { setText(''); setMention(null) }
  }

  const onKey = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (mention && matches.length > 0) {
      if (e.key === 'ArrowDown') { e.preventDefault(); setActive((i) => (i + 1) % matches.length); return }
      if (e.key === 'ArrowUp') { e.preventDefault(); setActive((i) => (i - 1 + matches.length) % matches.length); return }
      if ((e.key === 'Enter' && !e.ctrlKey && !e.metaKey && !e.nativeEvent.isComposing) || e.key === 'Tab') { e.preventDefault(); pick(matches[active]); return }
      if (e.key === 'Escape') { e.preventDefault(); setMention(null); return }
    }
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); send() }
    if (e.key === 'Escape' && onCancel) onCancel()
  }

  return (
    <div className="composer">
      <div className="mention-wrap">
        <textarea
          ref={areaRef}
          className="textarea composer__input"
          rows={3}
          placeholder={placeholder}
          value={text}
          autoFocus={autoFocus}
          maxLength={COMMENT_MAX_LENGTH + 100}
          onChange={(e) => { setText(e.target.value); detect(e.target.value, e.target.selectionStart ?? e.target.value.length) }}
          onKeyDown={onKey}
          onClick={(e) => detect(e.currentTarget.value, e.currentTarget.selectionStart ?? 0)}
          onBlur={() => window.setTimeout(() => setMention(null), 150)}
          aria-label={placeholder}
          aria-autocomplete="list"
          aria-expanded={mention !== null && matches.length > 0}
        />
        {mention && matches.length > 0 && (
          <ul className="mention-list" role="listbox" aria-label="언급할 사람">
            {matches.map((u, i) => (
              <li key={u.id} role="option" aria-selected={i === active}>
                <button type="button" className={i === active ? 'is-active' : undefined} onMouseDown={(e) => { e.preventDefault(); pick(u) }} onMouseEnter={() => setActive(i)}>
                  <strong>{u.displayName}</strong>
                  <span className="muted">@{u.username}{u.department ? ` · ${u.department}` : ''}</span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
      <div className="composer__foot">
        <span className={`composer__count${over ? ' composer__count--over' : ''}`}>{text.length} / {COMMENT_MAX_LENGTH}</span>
        <span className="composer__hint">Ctrl+Enter로 등록 · @아이디로 알림</span>
        <span className="grow" />
        {onCancel && <button type="button" className="btn btn--sm" onClick={onCancel} disabled={busy}>취소</button>}
        <button type="button" className="btn btn--sm btn--primary" onClick={send} disabled={!canSubmit}>{submitLabel}</button>
      </div>
    </div>
  )
}
