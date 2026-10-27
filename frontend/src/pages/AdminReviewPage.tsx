import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { errorMessage, formatDate, type PageResponse } from '../lib/api'
import { knowledgeApi, type StaleRow } from '../lib/knowledgeApi'
import { useToast } from '../lib/toast'
import Pagination from '../components/Pagination'

/** 관리자: 오래 수정·확인되지 않은 발행 글(재검토 대상). 기준일 = max(마지막 수정, 내용 확인). */
export default function AdminReviewPage() {
  const toast = useToast()
  const [params, setParams] = useSearchParams()
  const page = Number(params.get('page') ?? 0)
  const [data, setData] = useState<PageResponse<StaleRow> | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const [busyId, setBusyId] = useState<number | null>(null)

  const load = () => { setErr(null); knowledgeApi.review.stale(page, 20).then(setData).catch((e) => setErr(errorMessage(e))) }
  useEffect(load, [page])

  const confirm = async (row: StaleRow) => {
    setBusyId(row.id)
    try { await knowledgeApi.review.confirm(row.id); toast(`'${row.title}' 내용 확인을 기록했습니다.`, 'success'); load() }
    catch (e) { toast(errorMessage(e), 'error') } finally { setBusyId(null) }
  }

  return (
    <div className="shell shell--wide">
      <div className="page-head">
        <h2>재검토</h2>
        <p>마지막 수정이나 내용 확인 뒤 오래 지난 발행 글입니다(설정 <code>REVIEW_STALE_DAYS</code>, 기본 180일). 내용이 그대로 맞으면 [내용 확인 완료]를 누르세요. 버전은 바뀌지 않습니다.</p>
      </div>
      {err && <div className="alert alert--error">{err}</div>}
      <div className="card">
        <div className="card__head"><span className="small muted">{data ? `재검토 대상 ${data.totalElements.toLocaleString()}건 · 오래된 순` : '불러오는 중…'}</span></div>
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th>제목</th><th>카테고리</th><th>작성자</th><th>마지막 수정</th><th>내용 확인</th><th style={{ textAlign: 'right' }}>경과</th><th style={{ textAlign: 'right' }}>조회</th><th></th></tr></thead>
            <tbody>
              {data?.content.map((r) => (
                <tr key={r.id}>
                  <td><Link to={`/posts/${r.id}`} className="ellipsis" style={{ display: 'inline-block', maxWidth: 360 }}>{r.title}</Link></td>
                  <td className="small">{r.categoryName}</td>
                  <td className="small">{r.authorName}</td>
                  <td className="small muted">{formatDate(r.lastModifiedAt)}</td>
                  <td className="small muted">{r.reviewedAt ? formatDate(r.reviewedAt) : '–'}</td>
                  <td className="small" style={{ textAlign: 'right' }}><strong>{r.daysSince.toLocaleString()}일</strong></td>
                  <td className="small muted" style={{ textAlign: 'right' }}>{r.viewCount.toLocaleString()}</td>
                  <td><div className="row" style={{ justifyContent: 'flex-end' }}>
                    <button type="button" className="btn btn--sm" disabled={busyId === r.id} onClick={() => confirm(r)}>{busyId === r.id ? '기록 중…' : '내용 확인 완료'}</button>
                  </div></td>
                </tr>
              ))}
              {data && data.content.length === 0 && <tr><td colSpan={8} className="empty">재검토할 글이 없습니다.</td></tr>}
            </tbody>
          </table>
        </div>
      </div>
      {data && <Pagination page={data.page} totalPages={data.totalPages} onChange={(p) => { const n = new URLSearchParams(params); n.set('page', String(p)); setParams(n) }} />}
    </div>
  )
}
