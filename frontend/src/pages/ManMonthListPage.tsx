import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { errorMessage, relativeTime } from '../lib/api'
import { mmApi, type MmSheetSummary, periodText } from '../lib/manMonthApi'
import Modal from '../components/Modal'
import WeeksInput from '../components/WeeksInput'

function thisMonday(): string {
  const d = new Date()
  d.setDate(d.getDate() - ((d.getDay() + 6) % 7))   // 일요일이면 6일 전
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/** 공수 표 목록 · 새 표 (시작 주와 주 수를 정해 만든다) */
export default function ManMonthListPage() {
  const navigate = useNavigate()
  const [list, setList] = useState<MmSheetSummary[] | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [title, setTitle] = useState('')
  const [start, setStart] = useState(thisMonday)
  const [weeks, setWeeks] = useState(26)
  const [busy, setBusy] = useState(false)

  useEffect(() => { mmApi.list().then(setList).catch((e) => setErr(errorMessage(e))) }, [])

  const create = async () => {
    setBusy(true)
    try {
      const s = await mmApi.create({ title: title.trim(), startDate: start, weeks })
      navigate(`/mm/${s.id}`)
    } catch (e) { setErr(errorMessage(e)) } finally { setBusy(false) }
  }

  return (
    <div className="shell shell--wide">
      <div className="page-head row row--between row--wrap">
        <div>
          <h2>업무산정 <span className="muted" style={{ fontWeight: 500, fontSize: 16 }}>공수 표 · 이벤트</span></h2>
          <p>가로는 주(월요일 시작), 세로는 프로젝트별 인원. 칸에는 그 주 값(보통 투입률 0~1.0, 아무 숫자)과 할 일 메모를 적습니다. 보기는 모두, 수정은 표마다 지정한 편집자.
            글쓰기 도구의 <strong>[업무산정]</strong> 으로 글에 바로 넣을 수 있습니다.</p>
        </div>
        <div className="row" style={{ gap: 6 }}>
          <Link to="/mm/events" className="btn">이벤트 관리</Link>
          <button type="button" className="btn btn--primary" onClick={() => setCreating(true)}>새 공수 표</button>
        </div>
      </div>
      {err && <div className="alert alert--error">{err}</div>}
      <div className="card">
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th>제목</th><th>기간</th><th>주인</th><th>갱신</th><th>권한</th></tr></thead>
            <tbody>
              {list?.map((s) => (
                <tr key={s.id}>
                  <td><Link to={`/mm/${s.id}`}>{s.title}</Link> {s.featured && <span className="chip">대문</span>}{s.description && <div className="small muted">{s.description}</div>}</td>
                  <td className="small">{s.startDate} 부터 {s.weeks}주</td>
                  <td className="small">{s.ownerName ?? '—'}</td>
                  <td className="small muted">{relativeTime(s.updatedAt)}</td>
                  <td>{s.canEdit ? <span className="chip">편집</span> : <span className="chip chip--neutral">보기</span>}</td>
                </tr>
              ))}
              {list && list.length === 0 && <tr><td colSpan={5} className="empty">아직 공수 표가 없습니다. [새 공수 표] 로 시작하세요.</td></tr>}
            </tbody>
          </table>
        </div>
      </div>
      {creating && (
        <Modal title="새 공수 표" onClose={() => setCreating(false)}
               footer={<><button type="button" className="btn" onClick={() => setCreating(false)}>취소</button><button type="button" className="btn btn--primary" disabled={busy || !title.trim()} onClick={create}>만들기</button></>}>
          <div className="field"><label htmlFor="mmn-t">제목</label><input id="mmn-t" className="input" value={title} onChange={(e) => setTitle(e.target.value)} maxLength={200} placeholder="예) 2027 상반기 프로젝트 인력 운영" autoFocus /></div>
          <div className="row" style={{ gap: 8 }}>
            <div className="field" style={{ flex: 1 }}><label htmlFor="mmn-s">시작 주 (고른 날의 월요일로 맞춤)</label><input id="mmn-s" type="date" className="input" value={start} onChange={(e) => setStart(e.target.value)} /></div>
            <div className="field" style={{ width: 120 }}><label htmlFor="mmn-w">주 수 (최대 104)</label><WeeksInput id="mmn-w" value={weeks} onChange={setWeeks} /></div>
          </div>
          <p className="small">기간: <strong>{periodText(start, weeks)}</strong></p>
          <p className="small muted">만든 사람이 표 주인이 되어 편집자를 지정합니다. 프로젝트 → 인원 순서로 추가하거나, 만든 뒤 [엑셀 ▾ → 양식 내려받기] 를 채워 [엑셀 ▾ → 올리기] 로 한 번에 넣을 수 있습니다.</p>
        </Modal>
      )}
    </div>
  )
}
