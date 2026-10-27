import { lazy, Suspense, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api, errorMessage, flattenCategories, formatFileSize, type AttachmentResponse, type CategoryNode, type PostRequest, type PostStatus, type Visibility } from '../lib/api'
import { htmlToText, markdownToHtml } from '../lib/html'
import { useAuth } from '../lib/auth'
import { useToast } from '../lib/toast'
import TagInput from '../components/TagInput'
import type { UploadedFile } from '../editor/RichEditor'
import { editorApi } from '../lib/editorApi'

const RichEditor = lazy(() => import('../editor/RichEditor'))
const DRAFT_KEY = 'workblog.draft.new'

/** 글 작성/수정: 네이버 에디터 방식의 리치 텍스트. 본문은 HTML 로 저장. */
export default function EditorPage() {
  const { id } = useParams()
  const editing = id !== undefined
  const navigate = useNavigate()
  const toast = useToast()
  const { user } = useAuth()
  const isAdmin = user?.role === 'ADMIN'

  const [title, setTitle] = useState('')
  const [content, setContent] = useState('<p></p>')
  const [summary, setSummary] = useState('')
  const [categoryId, setCategoryId] = useState<number | null>(null)
  const [tags, setTags] = useState<string[]>([])
  const [status, setStatus] = useState<PostStatus>('PUBLISHED')
  const [visibility, setVisibility] = useState<Visibility>('PUBLIC')
  const [pinned, setPinned] = useState(false)
  const [changeNote, setChangeNote] = useState('')
  const [attachments, setAttachments] = useState<AttachmentResponse[]>([])
  const [tree, setTree] = useState<CategoryNode[]>([])
  const [busy, setBusy] = useState(false)
  const [loaded, setLoaded] = useState(!editing)
  const [err, setErr] = useState<string | null>(null)
  const [restoredDraft, setRestoredDraft] = useState(false)
  const [converted, setConverted] = useState(false)
  const dirty = useRef(false)

  useEffect(() => { api.categories.tree().then(setTree).catch(() => null) }, [])

  useEffect(() => {
    if (!editing) {
      try {
        const saved = localStorage.getItem(DRAFT_KEY)
        if (saved) {
          const d = JSON.parse(saved) as Partial<PostRequest> & { attachments?: AttachmentResponse[] }
          if (d.title || (d.content && htmlToText(d.content).trim())) {
            setTitle(d.title ?? ''); setContent(d.content ?? '<p></p>'); setSummary(d.summary ?? ''); setCategoryId(d.categoryId ?? null); setTags(d.tags ?? []); setStatus(d.status ?? 'PUBLISHED'); setVisibility(d.visibility ?? 'PUBLIC'); setPinned(d.pinned ?? false); setAttachments(d.attachments ?? [])
            setRestoredDraft(true)
          }
        }
      } catch { /* ignore */ }
      return
    }
    api.posts.get(Number(id), false).then((p) => {
      if (!p.editable) { setErr('이 글을 수정할 권한이 없습니다.'); return }
      setTitle(p.title); setSummary(p.summary ?? ''); setCategoryId(p.category.id); setTags(p.tags); setStatus(p.status); setVisibility(p.visibility); setPinned(p.pinned); setAttachments(p.attachments)
      if (p.contentFormat === 'MARKDOWN') { setContent(markdownToHtml(p.content)); setConverted(true) } else setContent(p.content)
      setLoaded(true)
    }).catch((e) => setErr(errorMessage(e)))
  }, [editing, id])

  // 새 글은 브라우저에 자동 보관. 수정은 서버 리비전이 이력을 가진다.
  useEffect(() => {
    if (editing || !loaded || !dirty.current) return
    const t = window.setTimeout(() => {
      try { localStorage.setItem(DRAFT_KEY, JSON.stringify({ title, content, summary, categoryId, tags, status, visibility, pinned, attachments })) } catch { /* ignore */ }
    }, 600)
    return () => window.clearTimeout(t)
  }, [title, content, summary, categoryId, tags, status, visibility, pinned, attachments, editing, loaded])

  useEffect(() => {
    const onLeave = (e: BeforeUnloadEvent) => { if (dirty.current) e.preventDefault() }
    window.addEventListener('beforeunload', onLeave)
    return () => window.removeEventListener('beforeunload', onLeave)
  }, [])

  const mark = <T,>(set: (v: T) => void) => (v: T) => { dirty.current = true; set(v) }

  const wordInfo = useMemo(() => {
    const chars = htmlToText(content).replace(/\s/g, '').length
    return `${chars.toLocaleString()}자 · 약 ${Math.max(1, Math.round(chars / 500))}분`
  }, [content])

  const upload = async (file: File): Promise<UploadedFile> => {
    const a = await api.files.upload(file)
    return { id: a.id, name: a.name, url: a.url, kind: a.kind, size: a.size }
  }
  const attached = (f: UploadedFile) => {
    dirty.current = true
    setAttachments((list) => list.some((x) => x.id === f.id) ? list : [...list, { id: f.id, name: f.name, url: f.url, kind: f.kind, size: f.size, contentType: '', createdAt: new Date().toISOString() }])
  }

  const save = async (asStatus?: PostStatus) => {
    const st = asStatus ?? status
    if (!title.trim()) { toast('제목을 입력하세요.', 'error'); return }
    if (!htmlToText(content).trim() && !/<img|<table/.test(content)) { toast('본문을 입력하세요.', 'error'); return }
    setBusy(true)
    // 본문에 쓰인 사진 + 첨부 목록을 모두 글에 연결
    const usedIds = Array.from(content.matchAll(/\/api\/files\/([0-9a-f-]{36})/g)).map((m) => m[1])
    const attachmentIds = Array.from(new Set([...usedIds, ...attachments.map((a) => a.id)]))
    const body: PostRequest = { title: title.trim(), content, contentFormat: 'HTML', attachmentIds, summary: summary.trim() || undefined, categoryId, tags, status: st, visibility, pinned: isAdmin ? pinned : undefined, changeNote: changeNote.trim() || undefined }
    try {
      const saved = editing ? await api.posts.update(Number(id), body) : await api.posts.create(body)
      dirty.current = false
      try { localStorage.removeItem(DRAFT_KEY) } catch { /* ignore */ }
      toast(st === 'DRAFT' ? '임시 저장했습니다.' : editing ? '수정 내용을 발행했습니다.' : '글을 발행했습니다.', 'success')
      navigate(`/posts/${saved.id}`)
    } catch (e) { toast(errorMessage(e, '저장하지 못했습니다.'), 'error') } finally { setBusy(false) }
  }

  if (err) return <div className="shell shell--wide"><div className="alert alert--error">{err}</div></div>
  if (!loaded) return <div className="shell shell--wide"><div className="empty"><div className="spinner" style={{ margin: '0 auto' }} /></div></div>

  const categoryOptions = flattenCategories(tree)

  return (
    <div className="shell shell--wide">
      <section className="editor">
        <div className="editor__bar">
          <div className="row row--wrap">
            <span style={{ fontWeight: 700 }}>{editing ? '글 수정' : '새 글'}</span>
            {restoredDraft && <span className="chip chip--neutral">작성 중이던 내용을 불러왔습니다</span>}
            {converted && <span className="chip chip--draft">예전 마크다운 글을 서식 편집으로 변환했습니다. 저장하면 새 형식으로 바뀝니다.</span>}
          </div>
          <span className="small muted">{wordInfo}</span>
        </div>

        <input className="editor__title" value={title} onChange={(e) => mark(setTitle)(e.target.value)} placeholder="제목을 입력하세요" aria-label="제목" maxLength={300} />

        <div className="editor__meta">
          <div className="field">
            <label htmlFor="category">카테고리 <span className="muted" style={{ fontWeight: 400 }}>(최대 5단계 트리)</span></label>
            <select id="category" className="select" value={categoryId ?? ''} onChange={(e) => mark(setCategoryId)(e.target.value === '' ? null : Number(e.target.value))}>
              <option value="">일반 (기본)</option>
              {categoryOptions.map((c) => <option key={c.id} value={c.id}>{'　'.repeat(c.depth - 1)}{c.depth > 1 ? '› ' : ''}{c.name}</option>)}
            </select>
          </div>
          <div className="field">
            <label>태그 <span className="muted" style={{ fontWeight: 400 }}>(최대 10개)</span></label>
            <TagInput value={tags} onChange={mark(setTags)} />
          </div>
          <div className="field" style={{ gridColumn: '1 / -1' }}>
            <label htmlFor="summary">요약 <span className="muted" style={{ fontWeight: 400 }}>(선택 · 목록과 글 상단에 표시)</span></label>
            <input id="summary" className="input" value={summary} onChange={(e) => mark(setSummary)(e.target.value)} maxLength={500} placeholder="이 글이 무엇을 다루는지 한두 문장으로" />
          </div>
          <div className="field editor__publish" style={{ gridColumn: '1 / -1' }}>
            <label>공개 설정</label>
            <div className="row row--wrap" style={{ gap: 14 }}>
              <div className="segmented" role="radiogroup" aria-label="공개 범위">
                <button type="button" role="radio" aria-checked={visibility === 'PUBLIC'} className={visibility === 'PUBLIC' ? 'is-on' : undefined} onClick={() => mark(setVisibility)('PUBLIC')}>전체 공개</button>
                <button type="button" role="radio" aria-checked={visibility === 'PRIVATE'} className={visibility === 'PRIVATE' ? 'is-on' : undefined} onClick={() => mark(setVisibility)('PRIVATE')}>비공개</button>
              </div>
              <span className="small muted">{visibility === 'PRIVATE' ? '나와 관리자만 볼 수 있습니다.' : '로그인한 전 구성원이 볼 수 있습니다.'}</span>
              {isAdmin && (
                <label className="check" style={{ marginLeft: 'auto' }}>
                  <input type="checkbox" checked={pinned} onChange={(e) => mark(setPinned)(e.target.checked)} />
                  공지로 고정 <span className="muted">(목록 맨 위에 표시)</span>
                </label>
              )}
            </div>
          </div>
        </div>

        <Suspense fallback={<div className="empty"><div className="spinner" style={{ margin: '0 auto' }} /></div>}>
          <RichEditor value={content} onChange={mark(setContent)} onUpload={upload} onAttached={attached}
                      onImportDocument={(f) => editorApi.convert(f, true)}
                      onImportedTitle={(t) => { if (!title.trim()) mark(setTitle)(t) }} />
        </Suspense>

        <div className="editor__attach">
          <div className="row row--between row--wrap">
            <strong className="small">첨부파일 {attachments.length > 0 && <span className="muted">({attachments.length})</span>}</strong>
            <span className="small muted">사진·엑셀·문서·파일은 툴바 버튼으로 올립니다. 문서(HTML·Word·Excel 등)는 본문으로 변환되고 원본이 첨부됩니다. 본문에 넣은 사진·다이어그램도 자동으로 첨부됩니다.</span>
          </div>
          {attachments.length > 0 && (
            <ul className="attach-list">
              {attachments.map((a) => (
                <li key={a.id}>
                  <span className="attach-kind">{a.kind === 'IMAGE' ? '사진' : a.kind === 'EXCEL' ? '엑셀' : '파일'}</span>
                  <a href={`${a.url}?download=true`} className="ellipsis grow">{a.name}</a>
                  <span className="small muted">{formatFileSize(a.size)}</span>
                  <button type="button" className="btn btn--sm btn--ghost" onClick={() => { dirty.current = true; setAttachments((l) => l.filter((x) => x.id !== a.id)) }} aria-label={`${a.name} 첨부 해제`}>✕</button>
                </li>
              ))}
            </ul>
          )}
        </div>

        <div className="editor__foot">
          {editing && <input className="input grow" style={{ maxWidth: 420, height: 36 }} value={changeNote} onChange={(e) => setChangeNote(e.target.value)} placeholder="변경 메모 (선택) 예) 2단계 절차 수정" maxLength={500} />}
          <div className="row" style={{ marginLeft: 'auto' }}>
            <button type="button" className="btn" onClick={() => navigate(-1)} disabled={busy}>취소</button>
            <button type="button" className="btn" onClick={() => save('DRAFT')} disabled={busy}>임시 저장</button>
            <button type="button" className="btn btn--primary" onClick={() => save('PUBLISHED')} disabled={busy}>{busy ? '저장 중…' : editing ? (status === 'DRAFT' ? '발행' : '수정 발행') : '발행'}</button>
          </div>
        </div>
      </section>
    </div>
  )
}
