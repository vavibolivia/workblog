import { Link } from 'react-router-dom'
import { formatDate, type PostSummary } from '../lib/api'
import { Avatar } from './Avatar'
import CategoryPath from './CategoryPath'

export type ListView = 'list' | 'album'

export function Highlight({ text, q }: { text: string; q?: string }) {
  if (!q) return <>{text}</>
  const idx = text.toLowerCase().indexOf(q.toLowerCase())
  if (idx < 0) return <>{text}</>
  return <>{text.slice(0, idx)}<mark>{text.slice(idx, idx + q.length)}</mark><Highlight text={text.slice(idx + q.length)} q={q} /></>
}

/** 글 카드. 목록형(기본)과 앨범형(썸네일 격자)을 같은 데이터로 그린다. */
export default function PostCard({ post, q, view = 'list' }: { post: PostSummary; q?: string; view?: ListView }) {
  const date = post.publishedAt ?? post.createdAt
  const badges = (
    <>
      {post.pinned && <span className="chip chip--notice chip--xs">공지</span>}
      {post.visibility === 'PRIVATE' && <span className="chip chip--private chip--xs">비공개</span>}
      {post.status === 'DRAFT' && <span className="chip chip--draft chip--xs">임시 저장</span>}
    </>
  )

  if (view === 'album') {
    return (
      <article className={`album-card${post.pinned ? ' album-card--pinned' : ''}`}>
        <Link to={`/posts/${post.id}`} className="album-card__thumb" aria-label={post.title}>
          {post.thumbnailUrl
            ? <img src={post.thumbnailUrl} alt="" loading="lazy" />
            : <span className="album-card__noimg">{post.summary || post.snippet || post.title}</span>}
          {(post.pinned || post.visibility === 'PRIVATE' || post.status === 'DRAFT') && <span className="album-card__badges">{badges}</span>}
        </Link>
        <div className="album-card__body">
          <h3><Link to={`/posts/${post.id}`}><Highlight text={post.title} q={q} /></Link></h3>
          <p className="album-card__snippet"><Highlight text={post.summary || post.snippet} q={q} /></p>
          <div className="album-card__meta">
            <CategoryPath category={post.category} className="post-card__cat" />
            <span>{formatDate(date)}</span>
          </div>
          <div className="album-card__foot">
            <span className="post-card__author"><Avatar name={post.author.displayName} size="xs" /><span>{post.author.displayName}</span></span>
            <span className="post-card__stats">
              <span className={post.likedByMe ? 'is-liked' : undefined}>공감 {post.likeCount}</span>
              <span>댓글 {post.commentCount}</span>
              <span>조회 {post.viewCount}</span>
            </span>
          </div>
        </div>
      </article>
    )
  }

  return (
    <article className={`post-card${post.pinned ? ' post-card--pinned' : ''}`}>
      <div className="grow">
        <div className="post-card__meta">
          {badges}
          <CategoryPath category={post.category} className="post-card__cat" />
          <span>{formatDate(date)}</span>
          {post.version > 1 && <span>v{post.version}</span>}
        </div>
        <h3><Link to={`/posts/${post.id}`}><Highlight text={post.title} q={q} /></Link></h3>
        <p className="post-card__snippet"><Highlight text={post.summary || post.snippet} q={q} /></p>
        {post.matchedAttachment && <p className="post-card__attach-match"><span className="attach-kind">첨부</span><span className="ellipsis"><Highlight text={post.matchedAttachment} q={q} /></span></p>}
        <div className="post-card__foot">
          <div className="post-card__author">
            <Avatar name={post.author.displayName} size="xs" />
            <span>{post.author.displayName}</span>
            {post.author.department && <span className="muted">· {post.author.department}</span>}
          </div>
          {post.tags.length > 0 && (
            <div className="post-card__tags">
              {post.tags.slice(0, 4).map((t) => <Link key={t} to={`/?tag=${encodeURIComponent(t)}`} className="chip chip--neutral">#{t}</Link>)}
              {post.tags.length > 4 && <span className="chip chip--neutral">+{post.tags.length - 4}</span>}
            </div>
          )}
        </div>
      </div>
      <div className="post-card__side">
        {post.thumbnailUrl && <Link to={`/posts/${post.id}`} className="post-card__thumb" tabIndex={-1} aria-hidden="true"><img src={post.thumbnailUrl} alt="" loading="lazy" /></Link>}
        <span className="post-card__stats">
          <span className={post.likedByMe ? 'is-liked' : undefined}>공감 {post.likeCount}</span>
          <span>댓글 {post.commentCount}</span>
              <span>조회 {post.viewCount}</span>
        </span>
      </div>
    </article>
  )
}
