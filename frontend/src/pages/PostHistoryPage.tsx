import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, changeTypeLabel, errorMessage, formatDate, type PostDetail, type RevisionDetail, type RevisionSummary } from '../lib/api'
import { collapse, diffLines } from '../lib/diff'
import { useToast } from '../lib/toast'
import RichContent from '../components/RichContent'
import { contentToText } from '../lib/html'
import Modal from '../components/Modal'

/** 글 하나의 변경 이력: 왼쪽 버전 목록, 오른쪽 선택 버전 내용 또는 현재 버전과의 차이. 복원 가능. */
export default function PostHistoryPage() {
  const { id } = useParams()
  const postId = Number(id)
  const navigate = useNavigate()
  const toast = useToast()
  const [post, setPost] = useState<PostDetail | null>(null)
  const [revs, setRevs] = useState<RevisionSummary[]>([])
  const [selected, setSelected] = useState<number | null>(null)
  const [detail, setDetail] = useState<RevisionDetail | null>(null)
  const [mode, setMode] = useState<'diff' | 'content'>('diff')
  const [err, setErr] = useState<string | null>(null)
  const [confirm, setConfirm] = useState(false)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    Promise.all([api.posts.get(postId, false), api.posts.revisions(postId)])
      .then(([p, r]) => { setPost(p); setRevs(r); setSelected(r.length > 1 ? r[1].version : r[0]?.version ?? null) })
      .catch((e) => setErr(errorMessage(e)))
  }, [postId])

  useEffect(() => {
    if (selected == null) return
    api.posts.revision(postId, selected).then(setDetail).catch((e) => toast(errorMessage(e), 'error'))
  }, [postId, selected, toast])

  const diff = useMemo(() => {
    if (!post || !detail) return []
    const a = `# ${detail.title}\n\n${contentToText(detail.content, detail.contentFormat)}`
    const b = `# ${post.title}\n\n${contentToText(post.content, post.contentFormat)}`
    return collapse(diffLines(a, b))
  }, [post, detail])

  const restore = async () => {
    if (selected == null) return
    setBusy(true)
    try {
      const p = await api.posts.restore(postId, selected)
      toast(`v${selected} 내용으로 복원했습니다 (v${p.version}).`, 'success')
      navigate(`/posts/${postId}`)
    } catch (e) { toast(errorMessage(e), 'error') } finally { setBusy(false); setConfirm(false) }
  }

  if (err) return <div className="shell shell--wide"><div className="alert alert--error">{err}</div></div>
  if (!post) return <div className="shell shell--wide"><div className="empty"><div className="spinner" style={{ margin: '0 auto' }} /></div></div>

  const isCurrent = selected === post.version

  return (
    <div className="shell shell--wide">
      <div className="page-head row row--between row--wrap">
        <div>
          <h2>변경 이력</h2>
          <p><Link to={`/posts/${postId}`} style={{ fontWeight: 600, color: 'var(--navy)' }}>{post.title}</Link> · 현재 v{post.version} · 총 {revs.length}개 버전</p>
        </div>
        <Link to={`/posts/${postId}`} className="btn btn--sm">글로 돌아가기</Link>
      </div>

      <div className="grid-rev">
        <div className="card">
          <div className="card__head"><h3>버전</h3></div>
          <div className="rev-list" role="listbox" aria-label="버전 목록">
            {revs.map((r) => (
              <button type="button" key={r.id} role="option" aria-selected={r.version === selected} onClick={() => setSelected(r.version)}>
                <span className="row" style={{ gap: 6 }}>
                  <span className="rev-v">v{r.version}</span>
                  <span className={`chip ${r.changeType === 'DELETE' ? 'chip--danger' : r.changeType === 'RESTORE' || r.changeType === 'UNDELETE' ? 'chip--success' : 'chip--neutral'}`} style={{ height: 20, fontSize: 11 }}>{changeTypeLabel[r.changeType]}</span>
                  {r.version === post.version && <span className="chip" style={{ height: 20, fontSize: 11 }}>현재</span>}
                  {r.status === 'DRAFT' && <span className="chip chip--draft" style={{ height: 20, fontSize: 11 }}>임시</span>}
                </span>
                <span className="small">{r.editedBy.displayName} · {formatDate(r.createdAt, true)}</span>
                {r.changeNote && <span className="small muted ellipsis" style={{ maxWidth: '100%' }}>{r.changeNote}</span>}
              </button>
            ))}
          </div>
        </div>

        <div className="card">
          <div className="card__head">
            <h3>{detail ? `v${detail.version} · ${detail.title}` : '버전을 선택하세요'}</h3>
            <div className="row">
              <div className="seg" role="group" aria-label="보기">
                <button type="button" aria-pressed={mode === 'diff'} onClick={() => setMode('diff')} disabled={isCurrent}>현재와 비교</button>
                <button type="button" aria-pressed={mode === 'content'} onClick={() => setMode('content')}>내용 보기</button>
              </div>
              {post.editable && !isCurrent && detail && <button type="button" className="btn btn--sm btn--sky" onClick={() => setConfirm(true)}>이 버전으로 복원</button>}
            </div>
          </div>
          <div className="card__body">
            {detail && (
              <p className="small muted" style={{ marginBottom: 12 }}>
                {detail.editedBy.displayName}{detail.editedBy.department ? ` (${detail.editedBy.department})` : ''}이(가) {formatDate(detail.createdAt, true)}에 {changeTypeLabel[detail.changeType]}
                {detail.changeNote ? ` · ${detail.changeNote}` : ''} · 카테고리 {detail.category}{detail.tags.length ? ` · ${detail.tags.map((t) => '#' + t).join(' ')}` : ''}
              </p>
            )}
            {detail && (mode === 'content' || isCurrent) && <RichContent content={detail.content} format={detail.contentFormat} />}
            {detail && mode === 'diff' && !isCurrent && (
              diff.every((l) => l.kind === 'ctx' || l.kind === 'gap')
                ? <div className="alert alert--info">제목과 본문은 현재 버전과 같습니다. (카테고리·태그·상태만 바뀐 수정일 수 있습니다)</div>
                : <div className="diff" aria-label={`v${detail.version}와 현재 버전의 차이`}>
                    {diff.map((l, i) => l.kind === 'gap'
                      ? <div key={i} className="gap">… {l.count}줄 동일 …</div>
                      : <div key={i} className={l.kind}>{l.kind === 'add' ? '+ ' : l.kind === 'del' ? '- ' : '  '}{l.text || ' '}</div>)}
                  </div>
            )}
          </div>
        </div>
      </div>

      {confirm && (
        <Modal title={`v${selected} 내용으로 복원할까요?`} onClose={() => setConfirm(false)}
               footer={<><button type="button" className="btn" onClick={() => setConfirm(false)}>취소</button><button type="button" className="btn btn--sky" disabled={busy} onClick={restore}>복원</button></>}>
          <p>현재 내용은 사라지지 않고 새 버전(v{post.version + 1})으로 이력에 남습니다. 제목, 본문, 요약, 카테고리, 태그, 상태가 선택한 버전으로 바뀝니다.</p>
        </Modal>
      )}
    </div>
  )
}
