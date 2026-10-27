import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, errorMessage, formatDate, type PostSummary } from '../lib/api'
import { useAuth } from '../lib/auth'
import { useToast } from '../lib/toast'
import Sidebar from '../components/Sidebar'
import Modal from '../components/Modal'

export default function TrashPage() {
  const { user } = useAuth()
  const toast = useToast()
  const isAdmin = user?.role === 'ADMIN'
  const [posts, setPosts] = useState<PostSummary[] | null>(null)
  const [purging, setPurging] = useState<PostSummary | null>(null)
  const [busy, setBusy] = useState(false)
  const load = () => api.posts.deleted().then(setPosts).catch((e) => toast(errorMessage(e), 'error'))
  useEffect(() => { load() }, [])

  const undelete = async (id: number) => {
    try { await api.posts.undelete(id); toast('글을 복구했습니다.', 'success'); load() }
    catch (e) { toast(errorMessage(e), 'error') }
  }

  // 영구 삭제(관리자만): 이력·댓글·첨부까지 지워지고 되돌릴 수 없다.
  const purge = async () => {
    if (!purging) return
    setBusy(true)
    try { await api.posts.remove(purging.id, true); toast('글을 영구 삭제했습니다.', 'success'); load() }
    catch (e) { toast(errorMessage(e), 'error') } finally { setBusy(false); setPurging(null) }
  }

  return (
    <div className="shell">
      <Sidebar />
      <main>
        <div className="page-head"><h2>삭제한 글</h2><p>삭제한 글은 목록과 검색에서 숨겨지지만 이력은 남습니다. 여기서 되돌릴 수 있습니다.{isAdmin ? ' 관리자는 영구 삭제도 할 수 있습니다.' : ''}</p></div>
        {!posts && <div className="empty"><div className="spinner" style={{ margin: '0 auto' }} /></div>}
        {posts && posts.length === 0 && <div className="card empty"><strong>삭제한 글이 없습니다</strong></div>}
        {posts && posts.length > 0 && (
          <div className="card table-wrap">
            <table className="table">
              <thead><tr><th>제목</th><th>카테고리</th><th>작성자</th><th>마지막 수정</th><th></th></tr></thead>
              <tbody>
                {posts.map((p) => (
                  <tr key={p.id}>
                    <td><Link to={`/posts/${p.id}`} style={{ fontWeight: 600 }}>{p.title}</Link></td>
                    <td>{p.category.path.join(' › ')}</td>
                    <td>{p.author.displayName}</td>
                    <td className="small muted">{formatDate(p.updatedAt, true)}</td>
                    <td>
                      <div className="row" style={{ justifyContent: 'flex-end', gap: 6 }}>
                        <button type="button" className="btn btn--sm btn--sky" onClick={() => undelete(p.id)}>삭제 취소</button>
                        {isAdmin && <button type="button" className="btn btn--sm btn--danger" onClick={() => setPurging(p)}>영구 삭제</button>}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </main>

      {purging && (
        <Modal title="영구 삭제할까요?" onClose={() => setPurging(null)}
               footer={<>
                 <button type="button" className="btn" onClick={() => setPurging(null)}>취소</button>
                 <button type="button" className="btn btn--danger" disabled={busy} onClick={purge}>{busy ? '삭제 중…' : '영구 삭제'}</button>
               </>}>
          <p><strong>{purging.title}</strong></p>
          <p>이력·댓글·첨부까지 지워지며 되돌릴 수 없습니다.</p>
        </Modal>
      )}
    </div>
  )
}
