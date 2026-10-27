import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { formatDate } from '../lib/api'
import { knowledgeApi, type RelatedPost, type RelatedResponse } from '../lib/knowledgeApi'

function Item({ p }: { p: RelatedPost }) {
  return (
    <li className="related__item">
      <Link to={`/posts/${p.id}`} className="related__title ellipsis">{p.title}</Link>
      <span className="related__meta small muted">
        <span className="ellipsis">{p.category.path.join(' › ')}</span>
        <span>· {p.authorName}</span>
        <span>· {formatDate(p.publishedAt ?? p.updatedAt)}</span>
      </span>
      {p.commonTags.length > 0 && (
        <span className="related__tags">{p.commonTags.map((t) => <span key={t} className="chip chip--neutral chip--xs">#{t}</span>)}</span>
      )}
    </li>
  )
}

/** 글 상세 하단(태그 아래, 댓글 위): 관련 글 · 이 글을 언급한 글. 둘 다 없으면 아무것도 그리지 않는다. */
export default function RelatedPosts({ postId }: { postId: number }) {
  const [data, setData] = useState<RelatedResponse | null>(null)

  useEffect(() => {
    let alive = true
    setData(null)
    knowledgeApi.related(postId).then((d) => { if (alive) setData(d) }).catch(() => { /* 부가 정보: 실패하면 숨김 */ })
    return () => { alive = false }
  }, [postId])

  if (!data || (data.related.length === 0 && data.backlinks.length === 0)) return null
  return (
    <section className="related" aria-label="관련 글">
      {data.related.length > 0 && (
        <div className="related__card">
          <h4 className="related__head">관련 글 <span className="muted small">같은 태그·카테고리</span></h4>
          <ul className="related__list">{data.related.map((p) => <Item key={p.id} p={p} />)}</ul>
        </div>
      )}
      {data.backlinks.length > 0 && (
        <div className="related__card">
          <h4 className="related__head">이 글을 언급한 글 <span className="muted small">{data.backlinks.length}</span></h4>
          <ul className="related__list">{data.backlinks.map((p) => <Item key={p.id} p={p} />)}</ul>
        </div>
      )}
    </section>
  )
}
