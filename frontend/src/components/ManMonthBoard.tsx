import { useState, type ReactNode } from 'react'
import { relativeTime } from '../lib/api'
import { type MmCellChange, type MmProject, type MmRow, type MmSheet } from '../lib/manMonthApi'
import { useToast } from '../lib/toast'
import ManMonthGrid, { type MmMode } from './ManMonthGrid'

interface BoardProps {
  sheet: MmSheet
  overlay: Record<string, number | null>
  change: (c: MmCellChange[]) => void
  saving: boolean
  compact?: boolean
  extra?: ReactNode
  onProjectMenu?: (p: MmProject) => void
  onRowMenu?: (r: MmRow) => void
}

/** 보기 전환 · 검색 · 접기/펴기 + 표. 프로젝트가 많으면(8개 초과) 처음엔 모두 접어서 연다. */
export default function ManMonthBoard({ sheet, overlay, change, saving, compact, extra, onProjectMenu, onRowMenu }: BoardProps) {
  const toast = useToast()
  const [mode, setMode] = useState<MmMode>('project')
  const [filter, setFilter] = useState('')
  const [collapsed, setCollapsed] = useState<Set<number>>(() => new Set(sheet.projects.length > 8 ? sheet.projects.map((p) => p.id) : []))
  const toggle = (pid: number) => setCollapsed((c) => { const n = new Set(c); if (n.has(pid)) n.delete(pid); else n.add(pid); return n })
  const end = sheet.weeks > 0 ? new Date(new Date(sheet.startDate).getTime() + (sheet.weeks * 7 - 1) * 864e5).toISOString().slice(0, 10) : sheet.startDate

  return (
    <div className="mm-board">
      <div className="mm-bar">
        <div className="seg" role="group" aria-label="보기">
          <button type="button" aria-pressed={mode === 'project'} onClick={() => setMode('project')}>프로젝트별</button>
          <button type="button" aria-pressed={mode === 'person'} onClick={() => setMode('person')}>사람별 부하</button>
        </div>
        <input className="input mm-search" value={filter} onChange={(e) => setFilter(e.target.value)} placeholder="프로젝트·이름·소속 찾기" aria-label="공수 표 검색" />
        {mode === 'project' && (
          <>
            <button type="button" className="btn btn--sm" onClick={() => setCollapsed(new Set())}>모두 펴기</button>
            <button type="button" className="btn btn--sm" onClick={() => setCollapsed(new Set(sheet.projects.map((p) => p.id)))}>모두 접기</button>
          </>
        )}
        <span className="small muted mm-meta">
          {sheet.startDate} ~ {end} · {sheet.weeks}주 · 프로젝트 {sheet.projects.length} · 인원 {new Set(sheet.rows.map((r) => r.userId ?? r.personName)).size}명
          {' · '}{saving ? '저장 중…' : `갱신 ${relativeTime(sheet.updatedAt)}`}
          {!sheet.canEdit && ' · 보기 전용'}
        </span>
        {extra}
      </div>
      {sheet.canEdit && mode === 'project' && !compact && <p className="small muted mm-hint">칸을 누르고 숫자 입력(0~1.0, 50% 도 됨) · Enter/방향키 이동 · Delete 비움 · 엑셀에서 복사한 영역을 Ctrl+V 로 붙여넣기</p>}
      <ManMonthGrid sheet={sheet} mode={mode} filter={filter} editable={sheet.canEdit} overlay={overlay} onChange={change}
                    collapsed={collapsed} onToggle={toggle} onError={(m) => toast(m, 'error')} onProjectMenu={onProjectMenu} onRowMenu={onRowMenu} compact={compact} />
    </div>
  )
}
