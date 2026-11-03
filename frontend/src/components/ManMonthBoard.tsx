import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { relativeTime } from '../lib/api'
import { useAuth } from '../lib/auth'
import { evColor, eventPeriod, personKey, weeksOf, type MmEvent, type MmProject, type MmRow } from '../lib/manMonthApi'
import { EventDialog, EventsTable } from './MmEvents'
import { type MmSheetState } from '../lib/useMmSheet'
import { useToast } from '../lib/toast'
import ManMonthGrid, { type MmMode, type MmPos } from './ManMonthGrid'
import Modal from './Modal'

interface BoardProps {
  state: MmSheetState
  /** 대문 등 좁은 곳: 보기 전용, 도구 줄 없음 */
  compact?: boolean
  extra?: ReactNode
  onProjectMenu?: (p: MmProject) => void
  onRowMenu?: (r: MmRow) => void
  onAddPerson?: (projectId: number) => void
}

function load<T>(key: string, fallback: T): T {
  try { const v = localStorage.getItem(key); return v ? (JSON.parse(v) as T) : fallback } catch { return fallback }
}
function save(key: string, v: unknown) {
  try { localStorage.setItem(key, JSON.stringify(v)) } catch { /* 저장소 없음 */ }
}
const hhmm = (d: Date) => `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`

/** 보기 전환 · 검색 · 접기/펴기 · 저장 상태 + 표. 프로젝트가 많으면(8개 초과) 처음엔 모두 접어서 연다. 접기·보기는 표마다 기억. */
export default function ManMonthBoard({ state, compact, extra, onProjectMenu, onRowMenu, onAddPerson }: BoardProps) {
  const sheet = state.sheet!
  const toast = useToast()
  const { user } = useAuth()
  const key = `workblog.mm.${sheet.id}`
  const [mode, setMode] = useState<MmMode>(() => (compact ? 'project' : load<MmMode>(`${key}.mode`, 'project')))
  const [filter, setFilter] = useState('')
  const [collapsed, setCollapsed] = useState<Set<number>>(() => {
    const saved = load<number[] | null>(`${key}.collapsed`, null)
    return new Set(saved ?? (sheet.projects.length > 8 ? sheet.projects.map((p) => p.id) : []))
  })
  const toldReadOnly = useRef(false)
  const [eventOpen, setEventOpen] = useState<MmEvent | { date: string } | null>(null)
  const [weekList, setWeekList] = useState<{ title: string; list: MmEvent[] } | null>(null)
  // 표의 특정 칸으로 옮겨 가기(메모 목록을 없앤 뒤로는 쓰는 곳이 없어 늘 비어 있음 — 그리드 쪽 기능은 남겨 둠)
  const [jump] = useState<(MmPos & { n: number }) | undefined>(undefined)
  useEffect(() => { if (!compact) save(`${key}.mode`, mode) }, [key, mode, compact])
  useEffect(() => { save(`${key}.collapsed`, [...collapsed]) }, [key, collapsed])
  const toggle = (pid: number) => setCollapsed((c) => { const n = new Set(c); if (n.has(pid)) n.delete(pid); else n.add(pid); return n })
  const end = sheet.weeks > 0 ? new Date(new Date(sheet.startDate).getTime() + (sheet.weeks * 7 - 1) * 864e5).toISOString().slice(0, 10) : sheet.startDate
  const editable = sheet.canEdit && !compact
  const failed = state.failed.size

  const status = state.saving ? <span className="mm-status mm-status--busy">● 저장 중…</span>
    : failed ? <span className="mm-status mm-status--bad">⚠ 저장 안 됨 {failed}칸 <button type="button" className="btn btn--sm btn--danger" onClick={state.retry}>다시 시도</button></span>
    : state.savedAt ? <span className="mm-status mm-status--ok">✓ 모두 저장됨 ({hhmm(state.savedAt)})</span>
    : <span className="mm-status">갱신 {relativeTime(sheet.updatedAt)}</span>

  return (
    <div className="mm-board">
      {!sheet.canEdit && !compact && (
        <div className="alert alert--info mm-ro-note">이 표는 <strong>보기 전용</strong>입니다. 고쳐야 하면 표 주인({sheet.ownerName ?? '—'})이나 관리자에게 편집자로 넣어 달라고 요청하세요.</div>
      )}
      <Summary state={state} />
      <div className="mm-bar">
        <div className="seg" role="group" aria-label="보기">
          <button type="button" aria-pressed={mode === 'project'} onClick={() => setMode('project')}>프로젝트별</button>
          <button type="button" aria-pressed={mode === 'person'} onClick={() => setMode('person')}>사람별 부하</button>
        </div>
        <input className="input mm-search" type="search" value={filter} onChange={(e) => setFilter(e.target.value)} placeholder="프로젝트·이름·소속 찾기" aria-label="공수 표 검색" />
        {mode === 'project' && !filter && sheet.projects.length > 1 && (
          <>
            <button type="button" className="btn btn--sm" onClick={() => setCollapsed(new Set())}>모두 펴기</button>
            <button type="button" className="btn btn--sm" onClick={() => setCollapsed(new Set(sheet.projects.map((p) => p.id)))}>모두 접기</button>
          </>
        )}
        <span className="small muted mm-meta">
          {sheet.startDate} ~ {end} · {sheet.weeks}주 · 프로젝트 {sheet.projects.length} · 인원 {new Set(sheet.rows.map((r) => r.userId ?? r.personName)).size}명
          {compact && ' · 여기서는 보기만 — 고치려면 [공수 표 열기]'}
        </span>
        {!compact && status}
        {extra}
      </div>
      <ManMonthGrid sheet={sheet} mode={mode} filter={filter} editable={editable} overlay={state.overlay} failed={state.failed} onChange={state.change}
                    onNotes={state.saveNotes} onEventOpen={(e) => setEventOpen(e)} onWeekEvents={(title, list) => setWeekList({ title, list })}
                    onEventAdd={!compact && user ? (d) => setEventOpen({ date: d }) : undefined}
                    onUndo={state.undo} onRedo={state.redo} canUndo={state.canUndo} canRedo={state.canRedo}
                    collapsed={collapsed} onToggle={toggle} onExpand={(pid) => setCollapsed((c) => { const n = new Set(c); n.delete(pid); return n })} jump={jump}
                    onError={(m) => toast(m, 'error')} onInfo={(m) => toast(m, 'info')}
                    onReadOnly={() => {
                      if (toldReadOnly.current) return
                      toldReadOnly.current = true
                      toast(compact ? '여기서는 보기만 됩니다. [공수 표 열기] 에서 고치세요.' : '보기 전용 표입니다. 표 주인이나 관리자에게 편집자 지정을 요청하세요.', 'info')
                    }}
                    onProjectMenu={onProjectMenu} onRowMenu={onRowMenu} onAddPerson={onAddPerson} compact={compact} />
      {!compact && <BoardExtras state={state} canAddEvent={!!user} onEvent={(e) => setEventOpen(e)} />}
      {eventOpen && <EventDialog event={'id' in eventOpen ? eventOpen : undefined} initialDate={'date' in eventOpen ? eventOpen.date : undefined}
                                 canEdit={'id' in eventOpen ? eventOpen.canEdit : !!user} projectNames={sheet.projects.map((p) => p.name)} onClose={() => setEventOpen(null)} />}
      {weekList && (
        <Modal title={weekList.title} onClose={() => setWeekList(null)} footer={<button type="button" className="btn btn--primary" onClick={() => setWeekList(null)}>닫기</button>}>
          <EventsTable events={weekList.list} canEdit={false} onOpen={(e) => { setWeekList(null); setEventOpen(e) }} />
        </Modal>
      )}
    </div>
  )
}

/** 한 줄 요약: 다음 이벤트 · 이번 주 1.0 초과 인원 — 숫자 칸을 읽지 않아도 바로 보이게 */
function Summary({ state }: { state: MmSheetState }) {
  const sheet = state.sheet!
  const s = useMemo(() => {
    const today = new Date()
    const t = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`
    const next = sheet.events.find((e) => (e.endDate ?? e.startDate) >= t)
    const weeks = weeksOf(sheet.startDate, sheet.weeks)
    const w = weeks.findIndex((x) => today >= x.monday && today < new Date(x.monday.getTime() + 7 * 864e5))
    const load = new Map<string, { name: string; sum: number }>()
    if (w >= 0) for (const r of sheet.rows) {
      const v = (state.overlay[`${r.id}:${w}`] ?? r.cells[String(w)]) ?? 0
      if (!v) continue
      const k = personKey(r)
      const cur = load.get(k) ?? { name: r.personName, sum: 0 }
      cur.sum = Math.round((cur.sum + v) * 100) / 100
      load.set(k, cur)
    }
    const over = [...load.values()].filter((x) => x.sum > 1.0001).sort((a, b) => b.sum - a.sum)
    return { next, over, inSheet: w >= 0 }
  }, [sheet, state.overlay])
  if (!s.next && !s.inSheet) return null
  return (
    <div className="mm-summary">
      {s.next && <span><span className="muted">다음 일정</span> <b style={{ color: evColor(s.next) }}>●</b> <strong>{eventPeriod(s.next)}</strong> {s.next.title}</span>}
      {s.inSheet && <span><span className="muted">이번 주 1.0 초과</span> {s.over.length
        ? s.over.slice(0, 4).map((o, i) => <span key={i} className="mm-summary__over">{o.name} {o.sum}</span>)
        : <strong>없음</strong>}{s.over.length > 4 && <span className="muted"> 외 {s.over.length - 4}명</span>}</span>}
    </div>
  )
}

/** 표 아래: 이 표 기간의 이벤트(공수 표와 같은 모양의 표). 할 일 메모 목록은 두지 않는다 — 메모는 칸 오른쪽 클릭으로 */
function BoardExtras({ state, canAddEvent, onEvent }:
  { state: MmSheetState; canAddEvent: boolean; onEvent: (e: MmEvent | { date: string }) => void }) {
  const sheet = state.sheet!
  return (
    <div className="mm-extras mm-extras--one">
      <section className="mm-extra">
        <div className="mm-extra__head">
          <h4>이 기간 이벤트 <span className="muted small">{sheet.events.length}건 · 공용</span></h4>
          <div className="row" style={{ gap: 6 }}>
            {canAddEvent && <button type="button" className="btn btn--sm btn--primary" onClick={() => onEvent({ date: sheet.startDate })}>+ 이벤트</button>}
            <Link to="/mm/events" className="btn btn--sm">이벤트 관리</Link>
          </div>
        </div>
        <EventsTable events={sheet.events} canEdit={canAddEvent} onOpen={(e) => onEvent(e)}
                     empty={canAddEvent ? '이 기간에 이벤트가 없습니다. 릴리스·회의 같은 일정을 넣으면 표의 이벤트 줄에 날짜대로 보입니다.' : '이 기간에 이벤트가 없습니다.'} />
      </section>
    </div>
  )
}
