import { useEffect, useRef, useState, type ReactNode } from 'react'
import { relativeTime } from '../lib/api'
import { type MmProject, type MmRow } from '../lib/manMonthApi'
import { type MmSheetState } from '../lib/useMmSheet'
import { useToast } from '../lib/toast'
import ManMonthGrid, { type MmMode } from './ManMonthGrid'

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
  const key = `workblog.mm.${sheet.id}`
  const [mode, setMode] = useState<MmMode>(() => (compact ? 'project' : load<MmMode>(`${key}.mode`, 'project')))
  const [filter, setFilter] = useState('')
  const [collapsed, setCollapsed] = useState<Set<number>>(() => {
    const saved = load<number[] | null>(`${key}.collapsed`, null)
    return new Set(saved ?? (sheet.projects.length > 8 ? sheet.projects.map((p) => p.id) : []))
  })
  const toldReadOnly = useRef(false)
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
          {compact && ' · 대문에서는 보기만 — 고치려면 [전체 화면 · 관리]'}
        </span>
        {!compact && status}
        {extra}
      </div>
      {editable && mode === 'project' && (
        <p className="small muted mm-hint">칸을 누르고 숫자 입력 → Enter (0.5 · 50 · 50% 모두 0.5) · 끌거나 Shift+방향키로 여러 칸 골라 한 번에 · Delete 비움 · Ctrl+C/V 엑셀과 복사·붙여넣기 · Ctrl+Z 되돌리기 · 바로 저장됨</p>
      )}
      <ManMonthGrid sheet={sheet} mode={mode} filter={filter} editable={editable} overlay={state.overlay} failed={state.failed} onChange={state.change}
                    onUndo={state.undo} onRedo={state.redo} canUndo={state.canUndo} canRedo={state.canRedo}
                    collapsed={collapsed} onToggle={toggle} onError={(m) => toast(m, 'error')} onInfo={(m) => toast(m, 'info')}
                    onReadOnly={() => {
                      if (toldReadOnly.current) return
                      toldReadOnly.current = true
                      toast(compact ? '대문에서는 보기만 됩니다. [전체 화면 · 관리] 에서 고치세요.' : '보기 전용 표입니다. 표 주인이나 관리자에게 편집자 지정을 요청하세요.', 'info')
                    }}
                    onProjectMenu={onProjectMenu} onRowMenu={onRowMenu} onAddPerson={onAddPerson} compact={compact} />
    </div>
  )
}
