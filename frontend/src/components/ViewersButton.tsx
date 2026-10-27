import { useState } from 'react'
import { Link } from 'react-router-dom'
import { errorMessage, formatDate } from '../lib/api'
import { auditApi, type ViewersResponse } from '../lib/collabApi'
import { useAuth } from '../lib/auth'
import { IcEye } from './Icons'
import Modal from './Modal'

/** 글 상세의 '열람자' 버튼(작성자·관리자에게만 보인다): 최근 열람자 50명 표. */
export default function ViewersButton({ postId }: { postId: number }) {
  const { user } = useAuth()
  const [open, setOpen] = useState(false)
  const [data, setData] = useState<ViewersResponse | null>(null)
  const [err, setErr] = useState<string | null>(null)

  const show = async () => {
    setOpen(true); setData(null); setErr(null)
    try { setData(await auditApi.viewers(postId)) } catch (e) { setErr(errorMessage(e, '열람자 목록을 불러오지 못했습니다.')) }
  }

  return (
    <>
      <button type="button" className="btn btn--sm" onClick={show} title="이 글을 열어 본 사람">
        <IcEye size={15} /> 열람자
      </button>
      {open && (
        <Modal title="열람자" wide onClose={() => setOpen(false)}
               footer={<>
                 {user?.role === 'ADMIN' && <Link to={`/admin/audit?postId=${postId}`} className="btn" onClick={() => setOpen(false)}>전체 기록(감사 로그)</Link>}
                 <button type="button" className="btn btn--primary" onClick={() => setOpen(false)}>닫기</button>
               </>}>
          {err && <div className="alert alert--error">{err}</div>}
          {!data && !err && <div className="empty"><div className="spinner" style={{ margin: '0 auto' }} /></div>}
          {data && (
            <>
              <p className="small muted" style={{ marginTop: 0 }}>
                작성자를 뺀 {data.totalViewers.toLocaleString()}명이 읽었습니다{data.totalViewers > data.viewers.length ? ` (최근 ${data.viewers.length}명 표시)` : ''}. 같은 사람이 10분 안에 다시 연 것은 1회로 셉니다.
              </p>
              <div className="table-wrap">
                <table className="table">
                  <thead><tr><th>이름</th><th>부서</th><th style={{ textAlign: 'right' }}>횟수</th><th>마지막 열람</th></tr></thead>
                  <tbody>
                    {data.viewers.map((v) => (
                      <tr key={v.user.id}>
                        <td><strong>{v.user.displayName}</strong> <span className="small muted">{v.user.username}</span></td>
                        <td className="small">{v.user.department ?? <span className="muted">부서 미지정</span>}</td>
                        <td style={{ textAlign: 'right' }}>{v.count}</td>
                        <td className="small">{formatDate(v.lastViewedAt, true)}</td>
                      </tr>
                    ))}
                    {data.viewers.length === 0 && <tr><td colSpan={4} className="empty">아직 다른 사람이 읽지 않았습니다.</td></tr>}
                  </tbody>
                </table>
              </div>
            </>
          )}
        </Modal>
      )}
    </>
  )
}
