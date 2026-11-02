import { useEffect, useMemo, useState, type ReactElement } from 'react'
import { errorMessage } from '../lib/api'
import { mmApi, periodText, type MmSheetSummary } from '../lib/manMonthApi'
import Modal from '../components/Modal'
import WeeksInput from '../components/WeeksInput'

const esc = (t: string) => t.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
const thisMonday = () => { const d = new Date(); d.setDate(d.getDate() - ((d.getDay() + 6) % 7)); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}` }

/**
 * 글쓰기 도구 [업무산정]: 공수 표(주별 인원 투입 + 할 일 메모 + 그 기간 이벤트)나 이벤트 표를 글에 넣는다.
 * 본문에는 링크 한 줄(`/mm/번호`, `/mm/events`)만 들어가고, 글을 볼 때 그 자리에 살아 있는 표가 보인다.
 */
export default function WorkPlanMenu({ insert, Button }: { insert: (html: string) => void; Button: (p: { onClick: () => void }) => ReactElement }) {
  const [menu, setMenu] = useState(false)
  const [dlg, setDlg] = useState<'new' | 'pick' | null>(null)
  const put = (id: number, title: string) => insert(`<p><a href="/mm/${id}">업무산정: ${esc(title)}</a></p><p></p>`)
  return (
    <>
      <Button onClick={() => setMenu((v) => !v)} />
      {menu && (
        <div className="rte__menu wp-menu" style={{ left: 'auto' }} onMouseDown={(e) => e.preventDefault()}>
          <button type="button" onClick={() => { setDlg('new'); setMenu(false) }}><strong>새 업무산정 표</strong><span className="small muted">주별 인원 투입 · 할 일 메모 · 이벤트</span></button>
          <button type="button" onClick={() => { setDlg('pick'); setMenu(false) }}><strong>만들어 둔 표 넣기</strong><span className="small muted">공수 표 목록에서 고르기</span></button>
          <button type="button" onClick={() => { insert('<p><a href="/mm/events">이벤트 표</a></p><p></p>'); setMenu(false) }}><strong>이벤트 표 넣기</strong><span className="small muted">릴리스·회의 등 날짜별 일정(공용)</span></button>
          <hr />
          <span className="small muted" style={{ padding: '4px 10px' }}>글에는 링크 한 줄이 들어가고, 저장한 글에서 살아 있는 표로 보입니다.</span>
        </div>
      )}
      {dlg === 'new' && <NewSheetDialog onClose={() => setDlg(null)} onDone={(id, t) => { put(id, t); setDlg(null) }} />}
      {dlg === 'pick' && <PickSheetDialog onClose={() => setDlg(null)} onPick={(s) => { put(s.id, s.title); setDlg(null) }} />}
    </>
  )
}

function NewSheetDialog({ onClose, onDone }: { onClose: () => void; onDone: (id: number, title: string) => void }) {
  const [title, setTitle] = useState('')
  const [start, setStart] = useState(thisMonday)
  const [weeks, setWeeks] = useState(26)
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const create = async () => {
    setBusy(true); setErr(null)
    try { const s = await mmApi.create({ title: title.trim(), startDate: start, weeks }); onDone(s.id, s.title) }
    catch (e) { setErr(errorMessage(e)) } finally { setBusy(false) }
  }
  return (
    <Modal title="새 업무산정 표" onClose={onClose}
           footer={<><span className="small" style={{ marginRight: 'auto', color: 'var(--danger)' }}>{err}</span>
             <button type="button" className="btn" onClick={onClose}>취소</button>
             <button type="button" className="btn btn--primary" disabled={busy || !title.trim() || !start} onClick={create}>만들어 글에 넣기</button></>}>
      <div className="field"><label htmlFor="wp-t">제목</label><input id="wp-t" className="input" value={title} onChange={(e) => setTitle(e.target.value)} maxLength={200} autoFocus placeholder="예) 2027 상반기 AirDeck 인력 운영" /></div>
      <div className="row" style={{ gap: 8 }}>
        <div className="field" style={{ flex: 1 }}><label htmlFor="wp-s">시작 주 (고른 날의 월요일로 맞춤)</label><input id="wp-s" type="date" className="input" value={start} onChange={(e) => setStart(e.target.value)} /></div>
        <div className="field" style={{ width: 120 }}><label htmlFor="wp-w">주 수 (최대 104)</label><WeeksInput id="wp-w" value={weeks} onChange={setWeeks} /></div>
      </div>
      <p className="small">기간: <strong>{periodText(start, weeks)}</strong></p>
      <p className="small muted">만든 사람이 표 주인이 됩니다. 프로젝트·인원은 글을 저장한 뒤 표에서 [+ 인원]·[프로젝트 추가] 로 넣거나, 표 화면의 [엑셀 ▾] 로 한 번에 넣습니다.</p>
    </Modal>
  )
}

function PickSheetDialog({ onClose, onPick }: { onClose: () => void; onPick: (s: MmSheetSummary) => void }) {
  const [list, setList] = useState<MmSheetSummary[] | null>(null)
  const [q, setQ] = useState('')
  useEffect(() => { mmApi.list().then(setList).catch(() => setList([])) }, [])
  const shown = useMemo(() => { const t = q.trim().toLowerCase(); return (list ?? []).filter((s) => !t || s.title.toLowerCase().includes(t)) }, [list, q])
  return (
    <Modal title="공수 표 고르기" onClose={onClose} wide footer={<button type="button" className="btn" onClick={onClose}>닫기</button>}>
      <input className="input" value={q} onChange={(e) => setQ(e.target.value)} placeholder="제목으로 찾기" autoFocus aria-label="공수 표 찾기" />
      {!list && <div className="spinner" aria-label="불러오는 중" />}
      <ul className="front-pick__results">
        {shown.map((s) => (
          <li key={s.id} onClick={() => onPick(s)}>
            <span className="front-pick__title">{s.title}<span className="muted small"> · {periodText(s.startDate, s.weeks)}{s.ownerName ? ` · ${s.ownerName}` : ''}</span></span>
            <button type="button" className="btn btn--sm" onClick={(e) => { e.stopPropagation(); onPick(s) }}>넣기</button>
          </li>
        ))}
        {list && shown.length === 0 && <li className="muted small">{list.length ? '찾는 표가 없습니다.' : '공수 표가 없습니다. [새 업무산정 표] 로 만드세요.'}</li>}
      </ul>
    </Modal>
  )
}
