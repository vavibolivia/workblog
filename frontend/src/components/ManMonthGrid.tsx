import { memo, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type ClipboardEvent, type KeyboardEvent, type RefObject } from 'react'
import { fmtVal, monthSpans, parseVal, personKey, totalMm, weeksOf, type MmCellChange, type MmProject, type MmRow, type MmSheet, type MmWeek } from '../lib/manMonthApi'

export type MmMode = 'project' | 'person'

interface Props {
  sheet: MmSheet
  mode: MmMode
  filter: string
  editable: boolean
  /** 아직 저장되지 않은 값(낙관적 표시). 키 = `${rowId}:${week}` */
  overlay: Record<string, number | null>
  failed: Set<string>
  onChange: (changes: MmCellChange[]) => void
  onUndo?: () => number
  onRedo?: () => number
  canUndo?: boolean
  canRedo?: boolean
  collapsed: Set<number>
  onToggle: (projectId: number) => void
  onError: (msg: string) => void
  onInfo: (msg: string) => void
  /** 보기 전용 표에서 입력하려 할 때 */
  onReadOnly?: () => void
  onProjectMenu?: (p: MmProject) => void
  onRowMenu?: (r: MmRow) => void
  onAddPerson?: (projectId: number) => void
  compact?: boolean
}

interface Pos { rowId: number; week: number }
interface PersonAgg { key: string; name: string; department: string | null; projects: string[]; cells: Record<string, number>; parts: Record<string, string[]> }
const QUICK = [0.25, 0.5, 0.75, 1]
const r2 = (n: number) => Math.round(n * 100) / 100

/** 칸 색: 투입률이 높을수록 진하게, 1.0 초과(사람별 합산)는 빨강 */
function cellBg(v: number | undefined): string | undefined {
  if (!v) return undefined
  if (v > 1.0001) return 'var(--mm-over)'
  return `color-mix(in srgb, var(--mm-on) ${Math.round(18 + v * 62)}%, transparent)`
}

export default function ManMonthGrid(props: Props) {
  const { sheet, mode, filter, editable, overlay, failed, onChange, collapsed, onToggle, onError, onInfo, compact } = props
  const weeks = useMemo(() => weeksOf(sheet.startDate, sheet.weeks), [sheet.startDate, sheet.weeks])
  const spans = useMemo(() => monthSpans(weeks), [weeks])
  const [anchor, setAnchor] = useState<Pos | null>(null)
  const [focus, setFocus] = useState<Pos | null>(null)
  const [edit, setEdit] = useState<{ init: string; typed: boolean } | null>(null)
  const inputRef = useRef<HTMLInputElement | null>(null)
  const wrap = useRef<HTMLDivElement>(null)
  const dragging = useRef(false)
  const thisWeek = useMemo(() => {
    const today = new Date()
    return weeks.findIndex((w) => today >= w.monday && today < new Date(w.monday.getTime() + 7 * 864e5))
  }, [weeks])

  // ---------- 값 (저장 전 값 포함) ----------
  const projectName = useMemo(() => new Map(sheet.projects.map((p) => [p.id, p.name])), [sheet.projects])
  /** 행마다 지금 보이는 값. 저장 전 값이 없는 행은 원래 객체를 그대로 써서 다시 그리지 않게 한다. */
  const rowCells = useMemo(() => {
    const touched = new Map<number, [number, number | null][]>()
    for (const [k, v] of Object.entries(overlay)) {
      const [rid, w] = k.split(':').map(Number)
      const l = touched.get(rid) ?? []
      l.push([w, v]); touched.set(rid, l)
    }
    const m = new Map<number, Record<string, number>>()
    for (const r of sheet.rows) {
      const t = touched.get(r.id)
      if (!t) { m.set(r.id, r.cells); continue }
      const c = { ...r.cells }
      for (const [w, v] of t) { if (v) c[String(w)] = v; else delete c[String(w)] }
      m.set(r.id, c)
    }
    return m
  }, [sheet.rows, overlay])
  const val = useCallback((rowId: number, w: number): number | undefined => rowCells.get(rowId)?.[String(w)], [rowCells])

  /** 사람(계정 또는 이름) × 주 합계와 그 구성 — 프로젝트별 화면에서도 1.0 초과를 바로 보여 주기 위함 */
  const personLoad = useMemo(() => {
    const m = new Map<string, Map<number, { sum: number; parts: string[] }>>()
    for (const r of sheet.rows) {
      const k = personKey(r)
      const pm = m.get(k) ?? new Map()
      for (const [w, v] of Object.entries(rowCells.get(r.id) ?? {})) {
        const e = pm.get(Number(w)) ?? { sum: 0, parts: [] }
        e.sum = r2(e.sum + v); e.parts.push(`${projectName.get(r.projectId) ?? ''} ${fmtVal(v)}`)
        pm.set(Number(w), e)
      }
      m.set(k, pm)
    }
    return m
  }, [sheet.rows, rowCells, projectName])

  // ---------- 보이는 행 ----------
  const q = filter.trim().toLowerCase()
  const groups = useMemo(() => sheet.projects.map((p) => {
    const pm = !q || p.name.toLowerCase().includes(q)
    const rows = sheet.rows.filter((r) => r.projectId === p.id && (pm || r.personName.toLowerCase().includes(q) || (r.department ?? '').toLowerCase().includes(q) || (r.role ?? '').toLowerCase().includes(q)))
    return { p, rows, show: pm || rows.length > 0 }
  }).filter((g) => g.show), [sheet.projects, sheet.rows, q])
  // 찾는 중에는 접힌 프로젝트도 펼쳐서 보여 준다
  const isOpen = useCallback((pid: number) => !!q || !collapsed.has(pid), [q, collapsed])
  const navRows: MmRow[] = useMemo(() => (mode === 'project' ? groups.flatMap((g) => (isOpen(g.p.id) ? g.rows : [])) : []), [mode, groups, isOpen])
  const rowIndex = useMemo(() => new Map(navRows.map((r, i) => [r.id, i])), [navRows])

  const people: PersonAgg[] = useMemo(() => {
    if (mode !== 'person') return []
    const m = new Map<string, PersonAgg>()
    for (const r of sheet.rows) {
      const k = personKey(r)
      const a = m.get(k) ?? { key: k, name: r.personName, department: r.department, projects: [], cells: {}, parts: {} }
      const pn = projectName.get(r.projectId) ?? ''
      a.projects.push(pn)
      for (const [w, v] of Object.entries(rowCells.get(r.id) ?? {})) {
        a.cells[w] = r2((a.cells[w] ?? 0) + v)
        ;(a.parts[w] ??= []).push(`${pn} ${fmtVal(v)}`)
      }
      m.set(k, a)
    }
    return [...m.values()].filter((a) => !q || a.name.toLowerCase().includes(q) || a.projects.some((p) => p.toLowerCase().includes(q)))
      .sort((a, b) => a.name.localeCompare(b.name, 'ko'))
  }, [mode, sheet.rows, rowCells, q, projectName])

  // ---------- 선택 범위 ----------
  const range = useMemo(() => {
    if (!anchor || !focus) return null
    const a = rowIndex.get(anchor.rowId), f = rowIndex.get(focus.rowId)
    if (a === undefined || f === undefined) return null
    return { r0: Math.min(a, f), r1: Math.max(a, f), w0: Math.min(anchor.week, focus.week), w1: Math.max(anchor.week, focus.week) }
  }, [anchor, focus, rowIndex])
  const rangeCells = (): Pos[] => {
    if (!range) return []
    const out: Pos[] = []
    for (let i = range.r0; i <= range.r1; i++) for (let w = range.w0; w <= range.w1; w++) out.push({ rowId: navRows[i].id, week: w })
    return out
  }
  const count = range ? (range.r1 - range.r0 + 1) * (range.w1 - range.w0 + 1) : 0

  const scrollToCell = (p: Pos) => window.requestAnimationFrame(() => {
    wrap.current?.querySelector(`[data-k="${p.rowId}:${p.week}"]`)?.scrollIntoView({ block: 'nearest', inline: 'nearest' })
  })
  /** scroll=false: 마우스로 고를 때(이미 보이는 칸 — 스크롤하면 끌기 선택이 따라 움직임) */
  const select = (p: Pos, extend: boolean, scroll = true) => {
    setFocus(p)
    if (!extend) setAnchor(p)
    if (scroll) scrollToCell(p)
  }

  // ---------- 바꾸기 ----------
  /** 1.0 을 넘게 되는 사람·주를 알려 준다(막지는 않음) */
  const warnOver = (changes: MmCellChange[]) => {
    const delta = new Map<string, number>()
    const rows = new Map(sheet.rows.map((r) => [r.id, r]))
    for (const c of changes) {
      const r = rows.get(c.rowId)
      if (!r) continue
      const k = `${personKey(r)}|${c.week}`
      delta.set(k, (delta.get(k) ?? 0) + (c.value ?? 0) - (val(c.rowId, c.week) ?? 0))
    }
    const over: string[] = []
    for (const [k, d] of delta) {
      const [pk, w] = k.split('|')
      const now = personLoad.get(pk)?.get(Number(w))?.sum ?? 0
      const name = sheet.rows.find((r) => personKey(r) === pk)?.personName ?? ''
      if (r2(now + d) > 1.0001) over.push(`${name} ${weeks[Number(w)]?.label} 주 ${r2(now + d)}`)
    }
    if (over.length) onInfo(`1.0 을 넘습니다 — ${over.slice(0, 3).join(', ')}${over.length > 3 ? ` 외 ${over.length - 3}건` : ''}`)
  }
  const put = (changes: MmCellChange[]) => {
    const real = changes.filter((c) => (val(c.rowId, c.week) ?? null) !== c.value)
    if (!real.length) return
    warnOver(real)
    onChange(real)
  }
  /** 선택한 칸(여러 칸이면 모두)에 같은 값 */
  const fill = (v: number | null) => put(rangeCells().map((p) => ({ ...p, value: v })))
  /** 편집 중인 값 확정. 잘못된 값이면 편집을 이어 간다(false). */
  const commitEdit = (): boolean => {
    const el = inputRef.current
    if (edit === null || !el) return true
    const text = el.value
    const v = parseVal(text)
    if (v === 'bad') { onError('투입률은 0 ~ 1.0 입니다. 50 이나 50% 처럼 넣어도 됩니다.'); el.select(); return false }
    inputRef.current = null   // 아래 focus 이동으로 생기는 blur 에서 다시 확정하지 않도록
    setEdit(null)
    fill(v)
    if (v !== null && !text.includes('%') && Number(text.replace(',', '.')) > 1) onInfo(`${text.trim()} → ${fmtVal(v)} 로 넣었습니다.`)
    wrap.current?.focus()
    return true
  }
  const move = (dr: number, dw: number, extend = false, jump = false) => {
    if (!focus || !navRows.length) return
    const i = rowIndex.get(focus.rowId) ?? 0
    const ni = jump ? (dr < 0 ? 0 : dr > 0 ? navRows.length - 1 : i) : Math.max(0, Math.min(navRows.length - 1, i + dr))
    const nw = jump ? (dw < 0 ? 0 : dw > 0 ? weeks.length - 1 : focus.week) : Math.max(0, Math.min(weeks.length - 1, focus.week + dw))
    select({ rowId: navRows[ni].id, week: nw }, extend)
  }

  const readOnlyKey = (e: KeyboardEvent) => /^[0-9.]$/.test(e.key) || e.key === 'Delete' || e.key === 'Backspace'
  const onKey = (e: KeyboardEvent) => {
    if (!focus || mode !== 'project') return
    const mod = e.ctrlKey || e.metaKey
    if (edit !== null) {
      if (e.key === 'Enter') { e.preventDefault(); if (commitEdit()) move(e.shiftKey ? -1 : 1, 0) }
      else if (e.key === 'Tab') { e.preventDefault(); if (commitEdit()) move(0, e.shiftKey ? -1 : 1) }
      else if (e.key === 'Escape') { e.preventDefault(); inputRef.current = null; setEdit(null); wrap.current?.focus() }
      else if (edit.typed && e.key.startsWith('Arrow')) {
        // 숫자를 쳐서 시작한 편집은 엑셀처럼 방향키 = 확정 후 이동
        e.preventDefault()
        if (commitEdit()) move(e.key === 'ArrowUp' ? -1 : e.key === 'ArrowDown' ? 1 : 0, e.key === 'ArrowLeft' ? -1 : e.key === 'ArrowRight' ? 1 : 0)
      }
      return
    }
    if (mod && (e.key === 'z' || e.key === 'Z')) {
      e.preventDefault()
      if (!editable) return
      const n = e.shiftKey ? props.onRedo?.() : props.onUndo?.()
      onInfo(n ? `${n}칸 ${e.shiftKey ? '다시 함' : '되돌림'}` : e.shiftKey ? '다시 할 것이 없습니다.' : '되돌릴 것이 없습니다.')
      return
    }
    if (mod && (e.key === 'y' || e.key === 'Y')) {
      e.preventDefault()
      if (!editable) return
      const n = props.onRedo?.()
      onInfo(n ? `${n}칸 다시 함` : '다시 할 것이 없습니다.')
      return
    }
    if (mod && (e.key === 'a' || e.key === 'A') && navRows.length) {
      e.preventDefault()
      setAnchor({ rowId: navRows[0].id, week: 0 }); setFocus({ rowId: navRows[navRows.length - 1].id, week: weeks.length - 1 })
      return
    }
    const arrows: Record<string, [number, number]> = { ArrowUp: [-1, 0], ArrowDown: [1, 0], ArrowLeft: [0, -1], ArrowRight: [0, 1] }
    if (arrows[e.key]) { e.preventDefault(); move(...arrows[e.key], e.shiftKey, mod); return }
    if (e.key === 'Home' || e.key === 'End') { e.preventDefault(); move(0, e.key === 'Home' ? -1 : 1, e.shiftKey, true); return }
    if (e.key === 'Tab') {
      // 마지막(첫) 주에서는 표 밖으로 나간다
      if (e.shiftKey ? focus.week === 0 : focus.week === weeks.length - 1) return
      e.preventDefault(); move(0, e.shiftKey ? -1 : 1); return
    }
    if (e.key === 'Enter') { e.preventDefault(); move(e.shiftKey ? -1 : 1, 0); return }
    if (e.key === 'Escape') { setAnchor(focus); return }
    if (!editable) { if (readOnlyKey(e) && !mod) props.onReadOnly?.(); return }
    if (e.key === 'F2') { e.preventDefault(); setEdit({ init: fmtVal(val(focus.rowId, focus.week)), typed: false }); return }
    if (e.key === 'Delete' || e.key === 'Backspace') { e.preventDefault(); fill(null); return }
    if (!mod && /^[0-9.]$/.test(e.key)) { e.preventDefault(); setEdit({ init: e.key, typed: true }) }
  }

  const onCopy = (e: ClipboardEvent) => {
    if (!range || edit !== null || mode !== 'project') return
    e.preventDefault()
    const lines: string[] = []
    for (let i = range.r0; i <= range.r1; i++) {
      const cells: string[] = []
      for (let w = range.w0; w <= range.w1; w++) cells.push(fmtVal(val(navRows[i].id, w)))
      lines.push(cells.join('\t'))
    }
    e.clipboardData.setData('text/plain', lines.join('\n'))
    onInfo(`${count}칸 복사 — 엑셀에 붙여넣을 수 있습니다.`)
  }
  const onPaste = (e: ClipboardEvent) => {
    if (!range || mode !== 'project' || edit !== null) return
    e.preventDefault()
    if (!editable) { props.onReadOnly?.(); return }
    const lines = e.clipboardData.getData('text/plain').replace(/\r/g, '').split('\n').filter((l, i, a) => l !== '' || i < a.length - 1)
    const changes: MmCellChange[] = []
    let bad = 0
    if (lines.length === 1 && !lines[0].includes('\t') && count > 1) {
      // 값 하나를 여러 칸에 붙여넣으면 고른 칸을 모두 채운다(엑셀과 같음)
      const v = parseVal(lines[0])
      if (v === 'bad') bad = count
      else changes.push(...rangeCells().map((p) => ({ ...p, value: v })))
    } else {
      lines.forEach((line, i) => line.split('\t').forEach((t, j) => {
        const r = navRows[range.r0 + i]
        const w = range.w0 + j
        if (!r || w >= weeks.length) return
        const v = parseVal(t)
        if (v === 'bad') bad++
        else changes.push({ rowId: r.id, week: w, value: v })
      }))
    }
    if (changes.length) { put(changes); onInfo(`${changes.length}칸 붙여넣음 — Ctrl+Z 로 되돌릴 수 있습니다.`) }
    if (bad) onError(`숫자가 아니거나 0 ~ 1.0 을 벗어난 ${bad}칸은 건너뛰었습니다.`)
  }

  // ---------- 마우스 ----------
  const latest = useRef({ edit, commitEdit, select, editable })
  useLayoutEffect(() => { latest.current = { edit, commitEdit, select, editable } })
  const onCellDown = useCallback((p: Pos, shift: boolean) => {
    const l = latest.current
    if (l.edit !== null && !l.commitEdit()) return
    dragging.current = true
    l.select(p, shift, false)
    wrap.current?.focus({ preventScroll: true })
  }, [])
  const onCellEnter = useCallback((p: Pos) => { if (dragging.current) setFocus(p) }, [])
  const onCellDbl = useCallback((p: Pos, v: number | undefined) => {
    if (!latest.current.editable) return
    setAnchor(p); setFocus(p); setEdit({ init: fmtVal(v), typed: false })
  }, [])
  const onEditBlur = useCallback(() => { if (latest.current.edit !== null && inputRef.current) latest.current.commitEdit() }, [])
  useEffect(() => {
    const up = () => { dragging.current = false }
    window.addEventListener('mouseup', up)
    return () => window.removeEventListener('mouseup', up)
  }, [])

  // ---------- 이번 주로 ----------
  const jumpToNow = useCallback((smooth: boolean) => {
    const el = wrap.current
    const th = el?.querySelector<HTMLElement>('th.mm-week.is-now')
    const stick = el?.querySelector<HTMLElement>('thead .mm-stick-last')
    if (!el || !th) return
    const left = stick ? stick.getBoundingClientRect().right - el.getBoundingClientRect().left : 0
    el.scrollTo({ left: Math.max(0, th.offsetLeft - left - 46), behavior: smooth ? 'smooth' : 'auto' })
  }, [])
  useEffect(() => { jumpToNow(false) }, [sheet.id, jumpToNow])

  // ---------- 그리기 ----------
  const head = (
    <thead>
      <tr>
        <th rowSpan={2} className="mm-sticky mm-c0">{mode === 'project' ? '프로젝트 / 이름' : '이름'}</th>
        <th rowSpan={2} className="mm-sticky mm-c1">소속</th>
        <th rowSpan={2} className="mm-sticky mm-c2">{mode === 'project' ? '역할' : '프로젝트 수'}</th>
        <th rowSpan={2} className="mm-sticky mm-c3 mm-stick-last" title="달마다 (투입률 합 ÷ 그 달 월요일 수) 의 합">합계 MM</th>
        {spans.map(([k, n]) => <th key={k} colSpan={n} className="mm-month">{k.replace('-', '.')}</th>)}
      </tr>
      <tr>{weeks.map((w) => <th key={w.index} className={`mm-week${w.index === thisWeek ? ' is-now' : ''}`} title={`${w.monday.toLocaleDateString('ko-KR')} 주${w.index === thisWeek ? ' (이번 주)' : ''}`}>{w.label}</th>)}</tr>
    </thead>
  )

  const pendingOf = useMemo(() => {
    const m = new Map<number, string>()
    for (const k of Object.keys(overlay)) { const [rid, w] = k.split(':'); m.set(Number(rid), `${m.get(Number(rid)) ?? ''},${w}${failed.has(k) ? '!' : ''}`) }
    return m
  }, [overlay, failed])

  const personRow = (r: MmRow) => {
    const idx = rowIndex.get(r.id) ?? -1
    const inRange = range && idx >= range.r0 && idx <= range.r1
    const load = personLoad.get(personKey(r))
    let over = ''
    if (load) for (const [w, e] of load) if (e.sum > 1.0001) over += `,${w}=${e.sum} (${e.parts.join(' + ')})`
    return (
      <PersonRow key={r.id} r={r} cells={rowCells.get(r.id) ?? r.cells} weeks={weeks} thisWeek={thisWeek}
                 selFrom={inRange ? range.w0 : -1} selTo={inRange ? range.w1 : -1} focusWeek={focus?.rowId === r.id ? focus.week : -1}
                 edit={focus?.rowId === r.id && edit ? edit : null} inputRef={inputRef} pending={pendingOf.get(r.id) ?? ''} over={over}
                 editable={editable} onDown={onCellDown} onEnter={onCellEnter} onDbl={onCellDbl} onBlur={onEditBlur} onMenu={props.onRowMenu} />
    )
  }

  const body = mode === 'project' ? groups.map((g) => {
    const open = isOpen(g.p.id)
    const sums = weeks.map((w) => r2(g.rows.reduce((t, r) => t + (val(r.id, w.index) ?? 0), 0)))
    const mm = r2(g.rows.reduce((t, r) => t + totalMm(rowCells.get(r.id) ?? {}, weeks), 0))
    return [
      <tr key={`p${g.p.id}`} className="mm-project">
        <td className="mm-sticky mm-c0">
          <button type="button" className="mm-toggle" onClick={() => onToggle(g.p.id)} aria-expanded={open} disabled={!!q} title={q ? '찾는 중에는 모두 펼쳐 보입니다' : open ? '접기' : '펼치기'}>{open ? '▾' : '▸'} {g.p.name}</button>
          <span className="mm-count">{g.rows.length}명</span>
          {editable && props.onAddPerson && <button type="button" className="mm-mini mm-mini--add" onClick={() => props.onAddPerson?.(g.p.id)} title="이 프로젝트에 인원 추가">+ 인원</button>}
          {editable && props.onProjectMenu && <button type="button" className="mm-mini" onClick={() => props.onProjectMenu?.(g.p)} title="프로젝트 이름 고치기 · 삭제" aria-label={`${g.p.name} 프로젝트 편집`}>⋯</button>}
        </td>
        <td className="mm-sticky mm-c1" /><td className="mm-sticky mm-c2" />
        <td className="mm-sticky mm-c3 mm-sum">{mm || ''}</td>
        {sums.map((s, i) => <td key={i} className={`mm-psum${i === thisWeek ? ' is-now' : ''}`}>{s ? s : ''}</td>)}
      </tr>,
      ...(open ? g.rows.map(personRow) : []),
    ]
  }) : people.map((a) => (
    <tr key={a.key} className="mm-person">
      <td className="mm-sticky mm-c0 mm-name" title={a.projects.join(', ')}>{a.name}</td>
      <td className="mm-sticky mm-c1 mm-dim">{a.department ?? ''}</td>
      <td className="mm-sticky mm-c2 mm-dim" title={a.projects.join(', ')}>{a.projects.length}</td>
      <td className="mm-sticky mm-c3 mm-sum">{totalMm(a.cells, weeks) || ''}</td>
      {weeks.map((w) => {
        const v = a.cells[String(w.index)]
        const parts = a.parts[String(w.index)]
        return <td key={w.index} className={`mm-cell mm-ro${v && v > 1.0001 ? ' is-over' : ''}${w.index === thisWeek ? ' is-now' : ''}`} style={{ background: cellBg(v) }}
                   title={parts ? `${v && v > 1.0001 ? '1.0 초과 — ' : ''}${parts.join(' + ')}` : undefined}>{fmtVal(v)}</td>
      })}
    </tr>
  ))

  const empty = mode === 'project' ? groups.length === 0 : people.length === 0
  const focusRow = focus ? sheet.rows.find((r) => r.id === focus.rowId) : undefined
  return (
    <>
      {!compact && (
        <div className="mm-tools">
          {mode === 'project' && editable ? (
            <>
              <span className="mm-tools__sel">
                {range && focusRow ? <><strong>{focusRow.personName}</strong> · {weeks[focus!.week]?.label} 주{count > 1 ? ` 외 — ${count}칸 선택` : ''}</> : '칸을 누르세요 (끌거나 Shift 로 여러 칸)'}
              </span>
              {QUICK.map((v) => <button key={v} type="button" className="btn btn--sm mm-q" disabled={!range} onClick={() => { fill(v); wrap.current?.focus() }} title={`선택한 칸에 ${v} 넣기`}>{v}</button>)}
              <button type="button" className="btn btn--sm mm-q" disabled={!range} onClick={() => { fill(null); wrap.current?.focus() }} title="선택한 칸 비우기 (Delete)">비움</button>
              <span className="mm-tools__sep" />
              <button type="button" className="btn btn--sm btn--ghost" disabled={!props.canUndo} onClick={() => { props.onUndo?.(); wrap.current?.focus() }} title="되돌리기 (Ctrl+Z)">↶ 되돌리기</button>
              <button type="button" className="btn btn--sm btn--ghost" disabled={!props.canRedo} onClick={() => { props.onRedo?.(); wrap.current?.focus() }} title="다시 (Ctrl+Y)">↷ 다시</button>
            </>
          ) : <span className="mm-tools__sel">{mode === 'person' ? '사람별 부하는 보기 전용 — 1.0 넘는 칸은 빨강, 칸에 마우스를 올리면 구성이 보입니다. 고치려면 [프로젝트별].' : ''}</span>}
          {thisWeek >= 0 && <button type="button" className="btn btn--sm btn--ghost mm-tools__now" onClick={() => jumpToNow(true)}>이번 주로</button>}
        </div>
      )}
      <div className={`mm-wrap${compact ? ' mm-wrap--compact' : ''}`} ref={wrap} tabIndex={0} onKeyDown={onKey} onPaste={onPaste} onCopy={onCopy}
           aria-label="공수 표 — 방향키로 이동, 숫자로 입력">
        <table className="mm-grid">
          {head}
          <tbody>
            {body}
            {empty && <tr><td className="mm-sticky mm-c0 mm-empty" colSpan={4}>{q ? '찾는 프로젝트·인원이 없습니다.' : '아직 프로젝트·인원이 없습니다.'}</td><td colSpan={weeks.length} /></tr>}
          </tbody>
          <tfoot>
            <tr className="mm-total">
              <td className="mm-sticky mm-c0">주별 합계 (투입 인원)</td><td className="mm-sticky mm-c1" /><td className="mm-sticky mm-c2" /><td className="mm-sticky mm-c3" />
              {weeks.map((w) => {
                const rows = mode === 'project' ? groups.flatMap((g) => g.rows) : sheet.rows
                const t = r2(rows.reduce((s, r) => s + (val(r.id, w.index) ?? 0), 0))
                return <td key={w.index} className={w.index === thisWeek ? 'is-now' : undefined}>{t || ''}</td>
              })}
            </tr>
          </tfoot>
        </table>
      </div>
    </>
  )
}

interface RowProps {
  r: MmRow
  cells: Record<string, number>
  weeks: MmWeek[]
  thisWeek: number
  selFrom: number
  selTo: number
  focusWeek: number
  edit: { init: string; typed: boolean } | null
  inputRef: RefObject<HTMLInputElement | null>
  /** ",주[!]" 목록 — 저장 전(!: 저장 실패) */
  pending: string
  /** ",주=합계 (구성)" 목록 — 그 사람의 그 주 합계가 1.0 초과 */
  over: string
  editable: boolean
  onDown: (p: Pos, shift: boolean) => void
  onEnter: (p: Pos) => void
  onDbl: (p: Pos, v: number | undefined) => void
  onBlur: () => void
  onMenu?: (r: MmRow) => void
}

/** 사람 한 줄. 이 줄과 상관없는 변경(다른 줄 선택·입력)에는 다시 그리지 않는다. */
const PersonRow = memo(function PersonRow({ r, cells, weeks, thisWeek, selFrom, selTo, focusWeek, edit, inputRef, pending, over, editable, onDown, onEnter, onDbl, onBlur, onMenu }: RowProps) {
  const overMap = new Map<number, string>()
  for (const part of over.split(',').slice(1)) { const i = part.indexOf('='); overMap.set(Number(part.slice(0, i)), part.slice(i + 1)) }
  const pend = new Map<number, boolean>()
  for (const p of pending.split(',').slice(1)) pend.set(Number(p.replace('!', '')), p.endsWith('!'))
  return (
    <tr className="mm-person">
      <td className="mm-sticky mm-c0 mm-name" title={[r.personName, r.department, r.role].filter(Boolean).join(' · ')}>
        <span className="mm-indent" />
        {onMenu && editable
          ? <button type="button" className="mm-namebtn" onClick={() => onMenu(r)} title="이름·소속·역할 고치기 · 다른 프로젝트로 옮기기 · 삭제">{r.personName}</button>
          : r.personName}
        {r.userId == null && <span className="mm-ext" title="계정 없는 인원(직접 입력)">외부</span>}
        {onMenu && editable && <button type="button" className="mm-mini" onClick={() => onMenu(r)} aria-label={`${r.personName} 행 편집`}>⋯</button>}
      </td>
      <td className="mm-sticky mm-c1 mm-dim">{r.department ?? ''}</td>
      <td className="mm-sticky mm-c2 mm-dim">{r.role ?? ''}</td>
      <td className="mm-sticky mm-c3 mm-sum">{totalMm(cells, weeks) || ''}</td>
      {weeks.map((w) => {
        const v = cells[String(w.index)]
        const inSel = w.index >= selFrom && w.index <= selTo
        const isFocus = w.index === focusWeek
        const o = overMap.get(w.index)
        const p = pend.get(w.index)
        const cls = `mm-cell${inSel ? ' is-insel' : ''}${isFocus ? ' is-sel' : ''}${w.index === thisWeek ? ' is-now' : ''}${p !== undefined ? (p ? ' is-failed' : ' is-pending') : ''}${o ? ' is-overp' : ''}`
        return (
          <td key={w.index} data-k={`${r.id}:${w.index}`} className={cls} style={{ background: cellBg(v) }}
              title={p ? '저장 안 됨 — 표 위 [다시 시도]' : o ? `이 사람 이 주 합계 ${o} — 1.0 초과` : undefined}
              onMouseDown={(e) => { if (isFocus && edit) return; e.preventDefault(); onDown({ rowId: r.id, week: w.index }, e.shiftKey) }}
              onMouseEnter={() => onEnter({ rowId: r.id, week: w.index })}
              onDoubleClick={() => onDbl({ rowId: r.id, week: w.index }, v)}>
            {isFocus && edit
              ? <input ref={(el) => { inputRef.current = el; if (el && document.activeElement !== el) { el.focus(); el.setSelectionRange(el.value.length, el.value.length) } }}
                       className="mm-input" defaultValue={edit.init} onBlur={onBlur} inputMode="decimal" aria-label={`${r.personName} ${w.label} 주 투입률`} />
              : fmtVal(v)}
          </td>
        )
      })}
    </tr>
  )
})
