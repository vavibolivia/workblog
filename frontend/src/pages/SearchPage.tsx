import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api, errorMessage, type PageResponse, type PostSummary } from '../lib/api'
import Sidebar from '../components/Sidebar'
import PostCard from '../components/PostCard'
import Pagination from '../components/Pagination'

export default function SearchPage() {
  const [params, setParams] = useSearchParams()
  const q = (params.get('q') ?? '').trim()
  const page = Number(params.get('page') ?? 0)
  const [data, setData] = useState<PageResponse<PostSummary> | null>(null)
  const [err, setErr] = useState<string | null>(null)

  useEffect(() => {
    if (!q) { setData(null); return }
    setErr(null); setData(null)
    api.posts.list({ q, page, size: 10 }).then(setData).catch((e) => setErr(errorMessage(e)))
  }, [q, page])

  return (
    <div className="shell">
      <Sidebar />
      <main>
        <div className="feed-head">
          <div>
            <h2>‘{q}’ 검색 결과</h2>
            <p>{data ? `${data.totalElements}건 · 제목, 본문, 태그, 카테고리, 작성자, 첨부 파일 내용에서 찾았습니다` : '제목, 본문, 태그, 카테고리, 작성자, 첨부 파일 내용에서 찾습니다'}</p>
          </div>
        </div>
        {err && <div className="alert alert--error">{err}</div>}
        {!q && <div className="card empty"><strong>검색어를 입력하세요</strong>위 검색창에서 글, 태그, 작성자를 찾을 수 있습니다.</div>}
        {q && !data && !err && <div className="empty"><div className="spinner" style={{ margin: '0 auto' }} /></div>}
        {data && data.content.length === 0 && (
          <div className="card empty">
            <strong>‘{q}’에 해당하는 글이 없습니다</strong>
            다른 단어나 더 짧은 검색어로 다시 찾아보세요.
            <div style={{ marginTop: 14 }}><Link to="/write" className="btn">이 주제로 글쓰기</Link></div>
          </div>
        )}
        {data && data.content.length > 0 && (
          <>
            <div className="feed">{data.content.map((p) => <PostCard key={p.id} post={p} q={q} />)}</div>
            <Pagination page={data.page} totalPages={data.totalPages} onChange={(p) => { const n = new URLSearchParams(params); n.set('page', String(p)); setParams(n) }} />
          </>
        )}
      </main>
    </div>
  )
}
