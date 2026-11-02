import { useMemo, useRef, useState, type ClipboardEvent, type KeyboardEvent } from 'react'
import { fmtVal, monthSpans, parseVal, personKey, totalMm, weeksOf, type MmCellChange, type MmProject, type MmRow, type MmSheet } from '../lib/manMonthApi'

export type MmMode = 'project' | 'person'

interface Props {
  sheet: MmSheet
  mode: MmMode
  filter: string
  editable: boolean
  /** 아직 저장되지 않은 값(낙관적 표시). 키 = `${rowId}:${week}` */
  overlay: Record<string, number | null>
  onChange: (changes: MmCellChange[]) => void
  collapsed: Set<number>
  onToggle: (projectId: number) => void
  onError: (msg: string) => void
  onProjectMenu?: (p: MmProject) => void
  onRowMenu?: (r: MmRow) => void
  compact?: boolean
}

interface PersonAgg { key: string; name: string; department: string | null; projects: string[]; cells: Record<string, number> }

/** 칸 색: 투입률이 높을수록 진하게, 1.0 초과(사람별 합산)는 빨강 */
function cellBg(v: number | undefined): string | undefined {
  if (!v) return undefined
  if (v > 1.0001) return 'var(--mm-over)'
  return `color-mix(in srgb, var(--mm-on) ${Math.round(18 + v * 62)}%, transparent)`
}

export default function ManMonthGrid({ sheet, mode, filter, editable, overlay, onChange, collapsed, onToggle, onError, onProjectMenu, onRowMenu, compact }: Props) {
  const weeks = useMemo(() => weeksOf(sheet.startDate, sheet.weeks), [sheet.startDate, sheet.weeks])
  const spans = useMemo(() => monthSpans(weeks), [weeks])
  const [sel, setSel] = useState<{ row: number; week: number } | null>(null)
  const [edit, setEdit] = useState<string | null>(null)
  const wrap = useRef<HTMLDivElement>(null)
  const today = new Date()
  const thisWeek = weeks.findIndex((w) => today >= w.monday && today < new Date(w.monday.getTime() + 7 * 864e5))

  const val = (r: MmRow, w: number): number | undefined => {
    const k = `${r.id}:${w}`
    if (k in overlay) return overlay[k] ?? undefined
    return r.cells[String(w)]
  }
  const cellsOf = (r: MmRow): Record<string, number> => {
    const c: Record<string, number> = {}
    for (const w of weeks) { const v = val(r, w.index); if (v) c[String(w.index)] = v }
    return c
  }

  const q = filter.trim().toLowerCase()
  const projectName = useMemo(() => new Map(sheet.projects.map((p) => [p.id, p.name])), [sheet.projects])
  const groups = useMemo(() => sheet.projects.map((p) => {
    const pm = !q || p.name.toLowerCase().includes(q)
    const rows = sheet.rows.filter((r) => r.projectId === p.id && (pm || r.personName.toLowerCase().includes(q) || (r.department ?? '').toLowerCase().includes(q) || (r.role ?? '').toLowerCase().includes(q)))
    return { p, rows, show: pm || rows.length > 0 }
  }).filter((g) => g.show), [sheet.projects, sheet.rows, q])

  // 키보드로 오가는 순서 = 화면에 보이는 사람 행
  const navRows: MmRow[] = mode === 'project' ? groups.flatMap((g) => (collapsed.has(g.p.id) ? [] : g.rows)) : []

  const people: PersonAgg[] = useMemo(() => {
    if (mode !== 'person') return []
    const m = new Map<string, PersonAgg>()
    for (const r of sheet.rows) {
      const k = personKey(r)
      const a = m.get(k) ?? { key: k, name: r.personName, department: r.department, projects: [], cells: {} }
      a.projects.push(projectName.get(r.projectId) ?? '')
      for (const w of weeks) { const v = val(r, w.index); if (v) a.cells[String(w.index)] = Math.round(((a.cells[String(w.index)] ?? 0) + v) * 100) / 100 }
      m.set(k, a)
    }
    return [...m.values()].filter((a) => !q || a.name.toLowerCase().includes(q) || a.projects.some((p) => p.toLowerCase().includes(q)))
      .sort((a, b) => a.name.localeCompare(b.name, 'ko'))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mode, sheet.rows, overlay, weeks, q, projectName])

  const weekTotal = (w: number) => {
    const rows = mode === 'project' ? groups.flatMap((g) => g.rows) : sheet.rows
    return Math.round(rows.reduce((t, r) => t + (val(r, w) ?? 0), 0) * 100) / 100
  }

  // ---------- 편집 ----------
  const commit = (text: string, at = sel) => {
    if (!at) return
    const r = navRows[at.row]
    if (!r) return
    const v = parseVal(text)
    if (v === 'bad') { onError('투입률은 0 ~ 1.0 (또는 0~100%) 입니다.'); return }
    if ((val(r, at.week) ?? null) !== v) onChange([{ rowId: r.id, week: at.week, value: v }])
  }
  const move = (dr: number, dw: number) => {
    if (!sel) return
    setSel({ row: Math.max(0, Math.min(navRows.length - 1, sel.row + dr)), week: Math.max(0, Math.min(weeks.length - 1, sel.week + dw)) })
  }
  const onKey = (e: KeyboardEvent) => {
    if (!sel || mode !== 'project') return
    if (edit !== null) {
      if (e.key === 'Enter' || e.key === 'Tab') { e.preventDefault(); commit(edit); setEdit(null); move(e.key === 'Enter' ? 1 : 0, e.key === 'Tab' ? (e.shiftKey ? -1 : 1) : 0) }
      else if (e.key === 'Escape') { setEdit(null) }
      return
    }
    const arrows: Record<string, [number, number]> = { ArrowUp: [-1, 0], ArrowDown: [1, 0], ArrowLeft: [0, -1], ArrowRight: [0, 1], Tab: [0, e.shiftKey ? -1 : 1] }
    if (arrows[e.key]) { e.preventDefault(); move(...arrows[e.key]); return }
    if (!editable) return
    if (e.key === 'Enter' || e.key === 'F2') { e.preventDefault(); setEdit(fmtVal(val(navRows[sel.row], sel.week))); return }
    if (e.key === 'Delete' || e.key === 'Backspace') { e.preventDefault(); commit(''); return }
    if (/^[0-9.]$/.test(e.key)) { e.preventDefault(); setEdit(e.key) }
  }
  const onPaste = (e: ClipboardEvent) => {
    if (!sel || !editable || mode !== 'project' || edit !== null) return
    e.preventDefault()
    const lines = e.clipboardData.getData('text/plain').replace(/\r/g, '').split('\n').filter((l, i, a) => l !== '' || i < a.length - 1)
    const changes: MmCellChange[] = []
    let bad = 0
    lines.forEach((line, i) => line.split('\t').forEach((t, j) => {
      const r = navRows[sel.row + i]
      const w = sel.week + j
      if (!r || w >= weeks.length) return
      const v = parseVal(t)
      if (v === 'bad') bad++
      else changes.push({ rowId: r.id, week: w, value: v })
    }))
    if (changes.length) onChange(changes)
    if (bad) onError(`숫자가 아니거나 0 ~ 1.0 을 벗어난 ${bad}칸은 건너뛰었습니다.`)
  }

  // ---------- 그리기 ----------
  const head = (
    <thead>
      <tr>
        <th rowSpan={2} className="mm-sticky mm-c0">{mode === 'project' ? '프로젝트 / 이름' : '이름'}</th>
        <th rowSpan={2} className="mm-sticky mm-c1">{mode === 'project' ? '소속' : '소속'}</th>
        <th rowSpan={2} className="mm-sticky mm-c2">{mode === 'project' ? '역할' : '프로젝트 수'}</th>
        <th rowSpan={2} className="mm-sticky mm-c3" title="달마다 (투입률 합 ÷ 그 달 월요일 수) 의 합">합계 MM</th>
        {spans.map(([k, n]) => <th key={k} colSpan={n} className="mm-month">{k.replace('-', '.')}</th>)}
      </tr>
      <tr>{weeks.map((w) => <th key={w.index} className={`mm-week${w.index === thisWeek ? ' is-now' : ''}`} title={`${w.monday.toLocaleDateString('ko-KR')} 주`}>{w.label}</th>)}</tr>
    </thead>
  )

  const personRow = (r: MmRow, navIndex: number) => (
    <tr key={r.id} className="mm-person">
      <td className="mm-sticky mm-c0 mm-name" title={r.personName}>
        <span className="mm-indent" />{r.personName}{r.userId == null && <span className="mm-ext" title="계정 없는 인원">외부</span>}
        {onRowMenu && editable && <button type="button" className="mm-mini" onClick={() => onRowMenu(r)} aria-label={`${r.personName} 행 편집`}>⋯</button>}
      </td>
      <td className="mm-sticky mm-c1 mm-dim">{r.department ?? ''}</td>
      <td className="mm-sticky mm-c2 mm-dim">{r.role ?? ''}</td>
      <td className="mm-sticky mm-c3 mm-sum">{totalMm(cellsOf(r), weeks) || ''}</td>
      {weeks.map((w) => {
        const v = val(r, w.index)
        const isSel = sel?.row === navIndex && sel.week === w.index
        return (
          <td key={w.index} className={`mm-cell${isSel ? ' is-sel' : ''}${w.index === thisWeek ? ' is-now' : ''}${`${r.id}:${w.index}` in overlay ? ' is-pending' : ''}`}
              style={{ background: cellBg(v) }}
              onMouseDown={() => { if (edit !== null && sel) { commit(edit); setEdit(null) } setSel({ row: navIndex, week: w.index }); wrap.current?.focus() }}
              onDoubleClick={() => editable && setEdit(fmtVal(v))}>
            {isSel && edit !== null
              ? <input autoFocus className="mm-input" value={edit} onChange={(e) => setEdit(e.target.value)} onBlur={() => { commit(edit); setEdit(null) }} />
              : fmtVal(v)}
          </td>
        )
      })}
    </tr>
  )

  let nav = 0
  const body = mode === 'project' ? groups.map((g) => {
    const open = !collapsed.has(g.p.id)
    const sums = weeks.map((w) => Math.round(g.rows.reduce((t, r) => t + (val(r, w.index) ?? 0), 0) * 100) / 100)
    const mm = Math.round(g.rows.reduce((t, r) => t + totalMm(cellsOf(r), weeks), 0) * 100) / 100
    return [
      <tr key={`p${g.p.id}`} className="mm-project">
        <td className="mm-sticky mm-c0" colSpan={1}>
          <button type="button" className="mm-toggle" onClick={() => onToggle(g.p.id)} aria-expanded={open}>{open ? '▾' : '▸'} {g.p.name}</button>
          <span className="mm-count">{g.rows.length}명</span>
          {onProjectMenu && editable && <button type="button" className="mm-mini" onClick={() => onProjectMenu(g.p)} aria-label={`${g.p.name} 프로젝트 편집`}>⋯</button>}
        </td>
        <td className="mm-sticky mm-c1" /><td className="mm-sticky mm-c2" />
        <td className="mm-sticky mm-c3 mm-sum">{mm || ''}</td>
        {sums.map((s, i) => <td key={i} className={`mm-psum${i === thisWeek ? ' is-now' : ''}`}>{s ? s : ''}</td>)}
      </tr>,
      ...(open ? g.rows.map((r) => personRow(r, nav++)) : []),
    ]
  }) : people.map((a) => (
    <tr key={a.key} className="mm-person">
      <td className="mm-sticky mm-c0 mm-name" title={a.projects.join(', ')}>{a.name}</td>
      <td className="mm-sticky mm-c1 mm-dim">{a.department ?? ''}</td>
      <td className="mm-sticky mm-c2 mm-dim" title={a.projects.join(', ')}>{a.projects.length}</td>
      <td className="mm-sticky mm-c3 mm-sum">{totalMm(a.cells, weeks) || ''}</td>
      {weeks.map((w) => {
        const v = a.cells[String(w.index)]
        return <td key={w.index} className={`mm-cell mm-ro${v && v > 1.0001 ? ' is-over' : ''}${w.index === thisWeek ? ' is-now' : ''}`} style={{ background: cellBg(v) }} title={v && v > 1 ? '1.0 초과 — 과부하' : undefined}>{fmtVal(v)}</td>
      })}
    </tr>
  ))

  const empty = mode === 'project' ? groups.length === 0 : people.length === 0
  return (
    <div className={`mm-wrap${compact ? ' mm-wrap--compact' : ''}`} ref={wrap} tabIndex={0} onKeyDown={onKey} onPaste={onPaste}>
      <table className="mm-grid">
        {head}
        <tbody>
          {body}
          {empty && <tr><td className="mm-sticky mm-c0 mm-empty" colSpan={4}>{q ? '찾는 프로젝트·인원이 없습니다.' : '아직 프로젝트·인원이 없습니다.'}</td><td colSpan={weeks.length} /></tr>}
        </tbody>
        <tfoot>
          <tr className="mm-total">
            <td className="mm-sticky mm-c0">주별 합계 (투입 인원)</td><td className="mm-sticky mm-c1" /><td className="mm-sticky mm-c2" /><td className="mm-sticky mm-c3" />
            {weeks.map((w) => { const t = weekTotal(w.index); return <td key={w.index} className={w.index === thisWeek ? 'is-now' : undefined}>{t || ''}</td> })}
          </tr>
        </tfoot>
      </table>
    </div>
  )
}
