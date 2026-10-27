import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, errorMessage, formatDate, formatFileSize, type PostDetail } from '../lib/api'
import { useToast } from '../lib/toast'
import Sidebar from '../components/Sidebar'
import RichContent from '../components/RichContent'
import Modal from '../components/Modal'
import { Avatar } from '../components/Avatar'
import CategoryPath from '../components/CategoryPath'
import CommentSection from '../components/CommentSection'
import ExportMenu from '../components/ExportMenu'
import ViewersButton from '../components/ViewersButton'
import Toc from '../components/Toc'
import RelatedPosts from '../components/RelatedPosts'
import StaleNotice from '../components/StaleNotice'

export default function PostDetailPage() {
  const { id } = useParams()
  const postId = Number(id)
  const navigate = useNavigate()
  const toast = useToast()
  const [post, setPost] = useState<PostDetail | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [busy, setBusy] = useState(false)
  const bodyRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    setPost(null); setErr(null)
    api.posts.get(postId).then(setPost).catch((e) => setErr(errorMessage(e, '글을 불러오지 못했습니다.')))
  }, [postId])

  const remove = async () => {
    setBusy(true)
    try {
      await api.posts.remove(postId)
      toast('글을 삭제했습니다. 삭제한 글 목록에서 되돌릴 수 있습니다.', 'success')
      navigate('/')
    } catch (e) { toast(errorMessage(e), 'error') } finally { setBusy(false); setConfirmDelete(false) }
  }

  const undelete = async () => {
    setBusy(true)
    try { setPost(await api.posts.undelete(postId)); toast('글을 복구했습니다.', 'success') }
    catch (e) { toast(errorMessage(e), 'error') } finally { setBusy(false) }
  }

  const [liking, setLiking] = useState(false)
  const toggleLike = async () => {
    if (liking) return
    setLiking(true)
    try {
      const r = await api.posts.like(postId)
      setPost((p) => p ? { ...p, likedByMe: r.liked, likeCount: r.count } : p)
    } catch (e) { toast(errorMessage(e, '공감을 처리하지 못했습니다.'), 'error') } finally { setLiking(false) }
  }

  return (
    <div className="shell">
      <Sidebar />
      <main>
        {err && <div className="alert alert--error">{err} <Link to="/" style={{ textDecoration: 'underline' }}>홈으로</Link></div>}
        {!post && !err && <div className="empty"><div className="spinner" style={{ margin: '0 auto' }} /></div>}
        {post && (
          <div className="post-wrap">
          <Toc bodyRef={bodyRef} deps={post.content} />
          <article className="post">
            <header className="post__head">
              {post.deletedAt && <div className="alert alert--warn" style={{ marginBottom: 14 }}>이 글은 {formatDate(post.deletedAt, true)}에 삭제되었습니다. 작성자와 관리자만 볼 수 있습니다.</div>}
              {!post.deletedAt && post.status === 'PUBLISHED' && <StaleNotice postId={post.id} />}
              <div className="row row--between row--wrap">
                <div className="row row--wrap" style={{ gap: 8 }}>
                  {post.pinned && <span className="chip chip--notice">공지</span>}
                  <CategoryPath category={post.category} className="post__cat" />
                </div>
                <div className="row row--wrap" style={{ gap: 6 }}>
                  {post.visibility === 'PRIVATE' && <span className="chip chip--private">비공개 · 나와 관리자만 볼 수 있음</span>}
                  {post.status === 'DRAFT' && <span className="chip chip--draft">임시 저장 · 작성자와 관리자만 볼 수 있음</span>}
                </div>
              </div>
              <h1 className="post__title">{post.title}</h1>
              <div className="post__byline">
                <div className="post__author">
                  <Avatar name={post.author.displayName} />
                  <div>
                    <strong>{post.author.displayName}{post.author.department ? ` · ${post.author.department}` : ''}</strong>
                    <span>{formatDate(post.publishedAt ?? post.createdAt, true)}{post.version > 1 ? ` · ${formatDate(post.updatedAt, true)} 수정 (v${post.version})` : ''} · 조회 {post.viewCount}</span>
                  </div>
                </div>
                <div className="post__actions">
                  {!post.deletedAt && (
                    <button type="button" className={`btn btn--sm like-btn${post.likedByMe ? ' like-btn--on' : ''}`} aria-pressed={post.likedByMe} disabled={liking} onClick={toggleLike} title={post.likedByMe ? '공감 취소' : '공감'}>
                      <span className="like-btn__icon" aria-hidden="true">{post.likedByMe ? '♥' : '♡'}</span> 공감 {post.likeCount}
                    </button>
                  )}
                  <Link to={`/posts/${post.id}/history`} className="btn btn--sm">변경 이력{post.version > 1 ? ` ${post.version}` : ''}</Link>
                  <ExportMenu postId={post.id} title={post.title} />
                  {post.editable && <ViewersButton postId={post.id} />}
                  {post.editable && !post.deletedAt && <>
                    <Link to={`/posts/${post.id}/edit`} className="btn btn--sm btn--primary">수정</Link>
                    <button type="button" className="btn btn--sm btn--danger" onClick={() => setConfirmDelete(true)}>삭제</button>
                  </>}
                  {post.editable && post.deletedAt && <button type="button" className="btn btn--sm btn--sky" disabled={busy} onClick={undelete}>삭제 취소</button>}
                </div>
              </div>
              {post.summary && <p className="post__summary">{post.summary}</p>}
            </header>
            <div className="post__body" ref={bodyRef}><RichContent content={post.content} format={post.contentFormat} /></div>
            {post.attachments.length > 0 && (
              <div className="post__attach">
                <strong className="small">첨부파일 {post.attachments.length}</strong>
                <ul className="attach-list">
                  {post.attachments.map((a) => (
                    <li key={a.id}>
                      <span className="attach-kind">{a.kind === 'IMAGE' ? '사진' : a.kind === 'EXCEL' ? '엑셀' : '파일'}</span>
                      <a href={`${a.url}?download=true`} className="ellipsis grow">{a.name}</a>
                      <span className="small muted">{formatFileSize(a.size)}</span>
                    </li>
                  ))}
                </ul>
              </div>
            )}
            {post.tags.length > 0 && (
              <div className="post__tags">{post.tags.map((t) => <Link key={t} to={`/?tag=${encodeURIComponent(t)}`} className="chip">#{t}</Link>)}</div>
            )}
            {!post.deletedAt && <RelatedPosts postId={post.id} />}
            <CommentSection postId={post.id} />
          </article>
          </div>
        )}
      </main>

      {confirmDelete && (
        <Modal title="글을 삭제할까요?" onClose={() => setConfirmDelete(false)}
               footer={<>
                 <button type="button" className="btn" onClick={() => setConfirmDelete(false)}>취소</button>
                 <button type="button" className="btn btn--danger" disabled={busy} onClick={remove}>삭제</button>
               </>}>
          <p>목록에서 숨겨지고 변경 이력에 삭제 기록이 남습니다. 작성자나 관리자는 '삭제한 글'에서 되돌릴 수 있습니다.</p>
        </Modal>
      )}
    </div>
  )
}
