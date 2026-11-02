import { memo, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type ClipboardEvent, type CSSProperties, type KeyboardEvent, type ReactNode, type RefObject } from 'react'
import { EVENT_KINDS, eventPeriod, eventWeeks, fmtVal, monthSpans, parseVal, personKey, totalMm, weeksOf,
  type MmCellChange, type MmEvent, type MmNoteChange, type MmProject, type MmRow, type MmSheet, type MmWeek } from '../lib/manMonthApi'
import Modal from './Modal'

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
  onNotes?: (changes: MmNoteChange[]) => Promise<boolean>
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
  /** 이벤트 줄: 이벤트를 눌렀을 때 / 빈 주를 눌러 새로 넣을 때(고칠 수 있을 때만) */
  onEventOpen?: (e: MmEvent) => void
  onEventAdd?: (date: string) => void
  compact?: boolean
}

interface Pos { rowId: number; week: number }
interface Rect { r0: number; r1: number; w0: number; w1: number }
interface PersonAgg { key: string; name: string; department: string | null; projects: string[]; cells: Record<string, number>; parts: Record<string, string[]> }
const QUICK = [0.25, 0.5, 0.75, 1]
const MAX_LANES = 3
const r2 = (n: number) => Math.round(n * 100) / 100
const kOf = (p: Pos) => `${p.rowId}:${p.week}`
const mod = (a: number, n: number) => ((a % n) + n) % n
const ymd = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
/** 칸 색: 값이 클수록 진하게(1 이상은 가장 진하게). 사람별 합 1.0 초과는 빨강(over) */
function cellBg(v: number | undefined, over = false): string | undefined {
  if (!v || v < 0) return undefined
  if (over) return 'var(--mm-over)'
  return `color-mix(in srgb, var(--mm-on) ${Math.round(18 + Math.min(v, 1) * 62)}%, transparent)`
}
/** 주에 이벤트가 있으면 그 주 열 왼쪽에 색 줄(가장 중요한 종류의 색) */
const KIND_RANK: Record<string, number> = { RELEASE: 0, MILESTONE: 1, REVIEW: 2, MEETING: 3, HOLIDAY: 4, LEAVE: 5, ETC: 6 }

export default function ManMonthGrid(props: Props) {
  const { sheet, mode, filter, editable, overlay, failed, onChange, collapsed, onToggle, onError, onInfo, compact } = props
  const weeks = useMemo(() => weeksOf(sheet.startDate, sheet.weeks), [sheet.startDate, sheet.weeks])
  const spans = useMemo(() => monthSpans(weeks), [weeks])
  const [anchor, setAnchor] = useState<Pos | null>(null)
  const [focus, setFocus] = useState<Pos | null>(null)
  /** Ctrl(⌘)+클릭으로 따로 고른 칸들 */
  const [extra, setExtra] = useState<Set<string>>(new Set())
  const [edit, setEdit] = useState<{ init: string; typed: boolean } | null>(null)
  const [fillTo, setFillTo] = useState<Pos | null>(null)
  const [noteFor, setNoteFor] = useState<Pos[] | null>(null)
  const inputRef = useRef<HTMLInputElement | null>(null)
  const wrap = useRef<HTMLDivElement>(null)
  const dragging = useRef<'select' | 'fill' | null>(null)
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
  const noteOf = useCallback((rowId: number, w: number): string | undefined => sheet.rows.find((r) => r.id === rowId)?.notes?.[String(w)], [sheet.rows])

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

  // ---------- 이벤트: 줄(lane) 배치 · 주별 색 줄 · 프로젝트별 ◆ ----------
  const ev = useMemo(() => {
    const placed = (sheet.events ?? []).map((e) => ({ e, ws: eventWeeks(e, sheet.startDate, sheet.weeks) })).filter((x) => x.ws.length)
      .sort((a, b) => a.ws[0] - b.ws[0] || b.ws.length - a.ws.length)
    // 제목이 다 보이도록: 막대는 실제 기간보다 길게(글자 수만큼) 그려질 수 있고, 겹치지 않게 그 길이로 줄을 나눈다
    const lanes: { e: MmEvent; a: number; b: number; px: number; vb: number }[][] = []
    const hidden = new Map<number, MmEvent[]>()   // 줄이 모자라 못 그린 이벤트(주별)
    for (const { e, ws } of placed) {
      const a = ws[0], b = ws[ws.length - 1]
      const px = [...e.title].reduce((t, ch) => t + (ch.charCodeAt(0) > 255 ? 12 : 7), 26)
      const vb = Math.max(b, a + Math.ceil(px / 46) - 1)
      let lane = lanes.findIndex((l) => l.every((x) => x.vb < a || x.a > vb))
      if (lane < 0 && lanes.length < MAX_LANES) { lanes.push([]); lane = lanes.length - 1 }
      if (lane < 0) { for (const w of ws) hidden.set(w, [...(hidden.get(w) ?? []), e]); continue }
      lanes[lane].push({ e, a, b, px, vb })
    }
    const byWeek = new Map<number, MmEvent[]>()
    for (const { e, ws } of placed) for (const w of ws) byWeek.set(w, [...(byWeek.get(w) ?? []), e])
    const line = new Map<number, string>()
    for (const [w, list] of byWeek) line.set(w, EVENT_KINDS[[...list].sort((x, y) => KIND_RANK[x.kind] - KIND_RANK[y.kind])[0].kind].color)
    return { lanes, hidden, byWeek, line }
  }, [sheet.events, sheet.startDate, sheet.weeks])
  const relatedEvents = useCallback((projectNameText: string) => {
    const n = projectNameText.toLowerCase()
    const m = new Map<number, MmEvent[]>()
    for (const [w, list] of ev.byWeek) {
      const hit = list.filter((e) => e.related && n.includes(e.related.trim().toLowerCase()))
      if (hit.length) m.set(w, hit)
    }
    return m
  }, [ev.byWeek])
  const lineStyle = (w: number): CSSProperties | undefined => {
    const c = ev.line.get(w)
    return c ? ({ ['--ev-ln' as string]: `linear-gradient(to right, ${c} 0 2px, transparent 2px)` }) : undefined
  }

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

  // ---------- 선택 ----------
  const range: Rect | null = useMemo(() => {
    if (!anchor || !focus) return null
    const a = rowIndex.get(anchor.rowId), f = rowIndex.get(focus.rowId)
    if (a === undefined || f === undefined) return null
    return { r0: Math.min(a, f), r1: Math.max(a, f), w0: Math.min(anchor.week, focus.week), w1: Math.max(anchor.week, focus.week) }
  }, [anchor, focus, rowIndex])
  const cellsOfRect = (r: Rect): Pos[] => {
    const out: Pos[] = []
    for (let i = r.r0; i <= r.r1; i++) for (let w = r.w0; w <= r.w1; w++) if (navRows[i]) out.push({ rowId: navRows[i].id, week: w })
    return out
  }
  /** 고른 칸 전부(사각 범위 + Ctrl 로 더 고른 칸) */
  const selected = (): Pos[] => {
    const m = new Map<string, Pos>()
    if (range) for (const p of cellsOfRect(range)) m.set(kOf(p), p)
    for (const k of extra) { const [rowId, week] = k.split(':').map(Number); if (rowIndex.has(rowId)) m.set(k, { rowId, week }) }
    return [...m.values()]
  }
  const count = (range ? (range.r1 - range.r0 + 1) * (range.w1 - range.w0 + 1) : 0) + [...extra].filter((k) => {
    if (!range) return true
    const [rowId, week] = k.split(':').map(Number); const i = rowIndex.get(rowId) ?? -1
    return !(i >= range.r0 && i <= range.r1 && week >= range.w0 && week <= range.w1)
  }).length

  /** 채우기 손잡이를 끄는 중이면 늘어날 사각형(긴 쪽 방향으로만) */
  const fillRect: Rect | null = useMemo(() => {
    if (!range || !fillTo) return null
    const i = rowIndex.get(fillTo.rowId)
    if (i === undefined) return null
    const dr = i < range.r0 ? i - range.r0 : i > range.r1 ? i - range.r1 : 0
    const dw = fillTo.week < range.w0 ? fillTo.week - range.w0 : fillTo.week > range.w1 ? fillTo.week - range.w1 : 0
    if (!dr && !dw) return null
    return Math.abs(dw) >= Math.abs(dr)
      ? { ...range, w0: Math.min(range.w0, fillTo.week), w1: Math.max(range.w1, fillTo.week) }
      : { ...range, r0: Math.min(range.r0, i), r1: Math.max(range.r1, i) }
  }, [range, fillTo, rowIndex])

  const scrollToCell = (p: Pos) => window.requestAnimationFrame(() => {
    wrap.current?.querySelector(`[data-k="${p.rowId}:${p.week}"]`)?.scrollIntoView({ block: 'nearest', inline: 'nearest' })
  })
  /** scroll=false: 마우스로 고를 때(이미 보이는 칸 — 스크롤하면 끌기 선택이 따라 움직임) */
  const select = (p: Pos, extend: boolean, scroll = true) => {
    setFocus(p)
    if (!extend) setAnchor(p)
    setExtra(new Set())
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
      if (now <= 1.0001 && r2(now + d) > 1.0001) {
        const name = sheet.rows.find((r) => personKey(r) === pk)?.personName ?? ''
        over.push(`${name} ${weeks[Number(w)]?.label} 주 ${r2(now + d)}`)
      }
    }
    if (over.length) onInfo(`사람별 합이 1.0 을 넘습니다 — ${over.slice(0, 3).join(', ')}${over.length > 3 ? ` 외 ${over.length - 3}건` : ''}`)
  }
  const put = (changes: MmCellChange[]) => {
    const real = changes.filter((c) => (val(c.rowId, c.week) ?? null) !== c.value)
    if (!real.length) return 0
    warnOver(real)
    onChange(real)
    return real.length
  }
  /** 고른 칸 모두에 같은 값 */
  const fill = (v: number | null) => put(selected().map((p) => ({ ...p, value: v })))
  /** 원본 사각형(src)의 값을 되풀이해서 dst 를 채운다(엑셀 채우기 손잡이·끝까지 채우기) */
  const tile = (src: Rect, dst: Rect) => {
    const h = src.r1 - src.r0 + 1, wd = src.w1 - src.w0 + 1
    const ch: MmCellChange[] = []
    for (let i = dst.r0; i <= dst.r1; i++) for (let w = dst.w0; w <= dst.w1; w++) {
      if (i >= src.r0 && i <= src.r1 && w >= src.w0 && w <= src.w1) continue
      const si = src.r0 + mod(i - src.r0, h), sw = src.w0 + mod(w - src.w0, wd)
      if (!navRows[i] || !navRows[si]) continue
      ch.push({ rowId: navRows[i].id, week: w, value: val(navRows[si].id, sw) ?? null })
    }
    const n = put(ch)
    setAnchor({ rowId: navRows[dst.r0].id, week: dst.w0 }); setFocus({ rowId: navRows[dst.r1].id, week: dst.w1 })
    onInfo(n ? `${n}칸 채움 — Ctrl+Z 로 되돌리기` : '바뀐 칸이 없습니다.')
  }
  const fillToEnd = () => {
    if (!range) return
    if (range.w1 >= weeks.length - 1) { onInfo('이미 마지막 주까지 골랐습니다.'); return }
    tile(range, { ...range, w1: weeks.length - 1 })
  }
  /** Ctrl+R(오른쪽)/Ctrl+D(아래): 고른 범위의 첫 열(첫 줄)로 나머지를 채움. 한 칸이면 왼쪽(위) 칸을 복사 */
  const fillDir = (dir: 'right' | 'down') => {
    if (!range) return
    if (dir === 'right') {
      if (range.w1 > range.w0) tile({ ...range, w1: range.w0 }, range)
      else if (range.w0 > 0) tile({ ...range, w0: range.w0 - 1, w1: range.w0 - 1 }, { ...range, w0: range.w0 - 1 })
    } else {
      if (range.r1 > range.r0) tile({ ...range, r1: range.r0 }, range)
      else if (range.r0 > 0) tile({ ...range, r0: range.r0 - 1, r1: range.r0 - 1 }, { ...range, r0: range.r0 - 1 })
    }
  }
  /** 편집 중인 값 확정. 잘못된 값이면 편집을 이어 간다(false). */
  const commitEdit = (): boolean => {
    const el = inputRef.current
    if (edit === null || !el) return true
    const v = parseVal(el.value)
    if (v === 'bad') { onError('숫자만 넣을 수 있습니다(예: 0.5 · 2 · 40 · 50%).'); el.select(); return false }
    inputRef.current = null   // 아래 focus 이동으로 생기는 blur 에서 다시 확정하지 않도록
    setEdit(null)
    fill(v)
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
  const openNotes = () => { const s = selected(); if (s.length) setNoteFor(s) }

  const readOnlyKey = (e: KeyboardEvent) => /^[-0-9.]$/.test(e.key) || e.key === 'Delete' || e.key === 'Backspace'
  const onKey = (e: KeyboardEvent) => {
    if (!focus || mode !== 'project') return
    const ctrl = e.ctrlKey || e.metaKey
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
    const key = e.key.toLowerCase()
    if (ctrl && key === 'z') {
      e.preventDefault()
      if (!editable) return
      const n = e.shiftKey ? props.onRedo?.() : props.onUndo?.()
      onInfo(n ? `${n}칸 ${e.shiftKey ? '다시 함' : '되돌림'}` : e.shiftKey ? '다시 할 것이 없습니다.' : '되돌릴 것이 없습니다.')
      return
    }
    if (ctrl && key === 'y') {
      e.preventDefault()
      if (!editable) return
      const n = props.onRedo?.()
      onInfo(n ? `${n}칸 다시 함` : '다시 할 것이 없습니다.')
      return
    }
    if (ctrl && (key === 'r' || key === 'd')) { e.preventDefault(); if (editable) fillDir(key === 'r' ? 'right' : 'down'); else props.onReadOnly?.(); return }
    if (ctrl && key === 'a' && navRows.length) {
      e.preventDefault()
      setExtra(new Set()); setAnchor({ rowId: navRows[0].id, week: 0 }); setFocus({ rowId: navRows[navRows.length - 1].id, week: weeks.length - 1 })
      return
    }
    const arrows: Record<string, [number, number]> = { ArrowUp: [-1, 0], ArrowDown: [1, 0], ArrowLeft: [0, -1], ArrowRight: [0, 1] }
    if (arrows[e.key]) { e.preventDefault(); move(...arrows[e.key], e.shiftKey, ctrl); return }
    if (e.key === 'Home' || e.key === 'End') { e.preventDefault(); move(0, e.key === 'Home' ? -1 : 1, e.shiftKey, true); return }
    if (e.key === 'Tab') {
      // 마지막(첫) 주에서는 표 밖으로 나간다
      if (e.shiftKey ? focus.week === 0 : focus.week === weeks.length - 1) return
      e.preventDefault(); move(0, e.shiftKey ? -1 : 1); return
    }
    if (e.key === 'F2' && e.shiftKey) { e.preventDefault(); if (editable) openNotes(); else props.onReadOnly?.(); return }
    if (e.key === 'Enter') { e.preventDefault(); move(e.shiftKey ? -1 : 1, 0); return }
    if (e.key === 'Escape') { setAnchor(focus); setExtra(new Set()); return }
    if (!editable) { if (readOnlyKey(e) && !ctrl) props.onReadOnly?.(); return }
    if (e.key === 'F2') { e.preventDefault(); setEdit({ init: fmtVal(val(focus.rowId, focus.week)), typed: false }); return }
    if (e.key === 'Delete' || e.key === 'Backspace') { e.preventDefault(); fill(null); return }
    if (!ctrl && !e.altKey && /^[-0-9.]$/.test(e.key)) { e.preventDefault(); setEdit({ init: e.key, typed: true }) }
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
    onInfo(`${(range.r1 - range.r0 + 1) * (range.w1 - range.w0 + 1)}칸 복사 — 엑셀에 붙여넣을 수 있습니다.`)
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
      else changes.push(...selected().map((p) => ({ ...p, value: v })))
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
    if (bad) onError(`숫자가 아닌 ${bad}칸은 건너뛰었습니다.`)
  }

  // ---------- 마우스 ----------
  const latest = useRef({ edit, commitEdit, select, editable, range, fillRect, tile, extra })
  useLayoutEffect(() => { latest.current = { edit, commitEdit, select, editable, range, fillRect, tile, extra } })
  const onCellDown = useCallback((p: Pos, shift: boolean, ctrl: boolean) => {
    const l = latest.current
    if (l.edit !== null && !l.commitEdit()) return
    wrap.current?.focus({ preventScroll: true })
    if (ctrl) {
      // Ctrl(⌘)+클릭: 떨어진 칸을 하나씩 더 고르기(다시 누르면 빼기)
      setExtra((s) => { const n = new Set(s); const k = kOf(p); if (n.has(k)) n.delete(k); else n.add(k); return n })
      setFocus(p)
      return
    }
    dragging.current = 'select'
    l.select(p, shift, false)
  }, [])
  const onCellEnter = useCallback((p: Pos) => {
    if (dragging.current === 'select') setFocus(p)
    else if (dragging.current === 'fill') setFillTo(p)
  }, [])
  const onCellDbl = useCallback((p: Pos, v: number | undefined) => {
    if (!latest.current.editable) return
    setAnchor(p); setFocus(p); setExtra(new Set()); setEdit({ init: fmtVal(v), typed: false })
  }, [])
  const onFillStart = useCallback(() => { dragging.current = 'fill'; setFillTo(null) }, [])
  const onEditBlur = useCallback(() => { if (latest.current.edit !== null && inputRef.current) latest.current.commitEdit() }, [])
  useEffect(() => {
    const up = () => {
      const l = latest.current
      if (dragging.current === 'fill' && l.range && l.fillRect) l.tile(l.range, l.fillRect)
      if (dragging.current === 'fill') setFillTo(null)
      dragging.current = null
    }
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
  const lanes = ev.lanes.length ? ev.lanes : [[]]
  const laneTop = (i: number) => 54 + i * 24
  const weekDate = (w: number) => ymd(weeks[w].monday)
  const head = (
    <thead>
      <tr>
        <th rowSpan={2} className="mm-sticky mm-c0">{mode === 'project' ? '프로젝트 / 이름' : '이름'}</th>
        <th rowSpan={2} className="mm-sticky mm-c1">소속</th>
        <th rowSpan={2} className="mm-sticky mm-c2">{mode === 'project' ? '역할' : '프로젝트 수'}</th>
        <th rowSpan={2} className="mm-sticky mm-c3 mm-stick-last" title="달마다 (값 합 ÷ 그 달 월요일 수) 의 합. 투입률로 적으면 한 달 내내 1.0 = 1 MM">합계 MM</th>
        {spans.map(([k, n]) => <th key={k} colSpan={n} className="mm-month">{k.replace('-', '.')}</th>)}
      </tr>
      <tr>{weeks.map((w) => (
        <th key={w.index} className={`mm-week${w.index === thisWeek ? ' is-now' : ''}`} style={lineStyle(w.index)}
            title={`${w.monday.toLocaleDateString('ko-KR')} 주${w.index === thisWeek ? ' (이번 주)' : ''}${ev.byWeek.get(w.index) ? '\n' + ev.byWeek.get(w.index)!.map((e) => `· ${eventPeriod(e)} ${e.title}`).join('\n') : ''}`}>{w.label}</th>
      ))}</tr>
      {lanes.map((lane, li) => {
        const cells: ReactNode[] = []
        for (let w = 0; w < weeks.length;) {
          const it = lane.find((x) => x.a === w)
          if (it) {
            const k = EVENT_KINDS[it.e.kind]
            const span = it.b - it.a + 1
            cells.push(
              <th key={w} colSpan={span} className="mm-ev" style={{ top: laneTop(li), zIndex: 3 }}>
                <button type="button" className="mm-evbar" style={{ ['--ev' as string]: k.color, minWidth: it.px }} onClick={() => props.onEventOpen?.(it.e)}
                        title={`${eventPeriod(it.e)} · ${k.label}\n${it.e.title}${it.e.related ? `\n관련: ${it.e.related}` : ''}${it.e.note ? `\n${it.e.note}` : ''}`}>
                  <i /><span>{it.e.title}</span>
                </button>
              </th>,
            )
            w += span
          } else {
            const hid = li === lanes.length - 1 ? ev.hidden.get(w) : undefined
            const wk = w
            cells.push(
              <th key={w} className={`mm-ev mm-ev--empty${props.onEventAdd ? ' is-addable' : ''}`} style={{ top: laneTop(li) }}
                  onDoubleClick={props.onEventAdd ? () => props.onEventAdd?.(weekDate(wk)) : undefined}
                  title={hid ? hid.map((e) => `· ${eventPeriod(e)} ${e.title}`).join('\n') : props.onEventAdd ? '두 번 눌러 이 주에 이벤트 넣기' : undefined}>
                {hid ? <button type="button" className="mm-evmore" onClick={() => props.onEventOpen?.(hid[0])}>+{hid.length}</button> : null}
              </th>,
            )
            w++
          }
        }
        return (
          <tr key={`lane${li}`} className="mm-evrow">
            <th colSpan={4} className="mm-sticky mm-c0 mm-evlabel" style={{ top: laneTop(li) }}>
              {li === 0 && <>
                <span>이벤트</span>
                {props.onEventAdd && <button type="button" className="mm-mini mm-mini--add" onClick={() => props.onEventAdd?.(weekDate(Math.max(0, thisWeek)))} title="이벤트 넣기 — 모든 공수 표에 함께 보입니다">+ 이벤트</button>}
                {!ev.lanes.length && <span className="muted small"> 이 기간에 없음</span>}
              </>}
            </th>
            {cells}
          </tr>
        )
      })}
    </thead>
  )

  const pendingOf = useMemo(() => {
    const m = new Map<number, string>()
    for (const k of Object.keys(overlay)) { const [rid, w] = k.split(':'); m.set(Number(rid), `${m.get(Number(rid)) ?? ''},${w}${failed.has(k) ? '!' : ''}`) }
    return m
  }, [overlay, failed])
  const extraOf = useMemo(() => {
    const m = new Map<number, string>()
    for (const k of extra) { const [rid, w] = k.split(':'); m.set(Number(rid), `${m.get(Number(rid)) ?? ''},${w}`) }
    return m
  }, [extra])
  const lineKey = useMemo(() => [...ev.line.entries()].map(([w, c]) => `${w}=${c}`).join(','), [ev.line])
  const handleAt = range && !extra.size && editable && edit === null && mode === 'project' ? { rowId: navRows[range.r1]?.id, week: range.w1 } : null

  const personRow = (r: MmRow) => {
    const idx = rowIndex.get(r.id) ?? -1
    const inRange = !!range && idx >= range.r0 && idx <= range.r1
    const inFill = !!fillRect && idx >= fillRect.r0 && idx <= fillRect.r1
    const load = personLoad.get(personKey(r))
    let over = ''
    if (load) for (const [w, e] of load) if (e.sum > 1.0001) over += `,${w}=${e.sum} (${e.parts.join(' + ')})`
    return (
      <PersonRow key={r.id} r={r} cells={rowCells.get(r.id) ?? r.cells} weeks={weeks} thisWeek={thisWeek}
                 selFrom={inRange ? range!.w0 : -1} selTo={inRange ? range!.w1 : -1}
                 edgeTop={inRange && idx === range!.r0} edgeBottom={inRange && idx === range!.r1}
                 fillFrom={inFill ? fillRect!.w0 : -1} fillTo={inFill ? fillRect!.w1 : -1}
                 extra={extraOf.get(r.id) ?? ''} handleWeek={handleAt?.rowId === r.id ? handleAt.week : -1}
                 focusWeek={focus?.rowId === r.id ? focus.week : -1}
                 edit={focus?.rowId === r.id && edit ? edit : null} inputRef={inputRef} pending={pendingOf.get(r.id) ?? ''} over={over} lines={lineKey}
                 editable={editable} onDown={onCellDown} onEnter={onCellEnter} onDbl={onCellDbl} onBlur={onEditBlur} onFillStart={onFillStart} onMenu={props.onRowMenu} />
    )
  }

  const body = mode === 'project' ? groups.map((g) => {
    const open = isOpen(g.p.id)
    const sums = weeks.map((w) => r2(g.rows.reduce((t, r) => t + (val(r.id, w.index) ?? 0), 0)))
    const mm = r2(g.rows.reduce((t, r) => t + totalMm(rowCells.get(r.id) ?? {}, weeks), 0))
    const rel = relatedEvents(g.p.name)
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
        {sums.map((s, i) => {
          const re = rel.get(i)
          return (
            <td key={i} className={`mm-psum${i === thisWeek ? ' is-now' : ''}`} style={lineStyle(i)}>
              {re && <button type="button" className="mm-diamond" style={{ ['--ev' as string]: EVENT_KINDS[re[0].kind].color }} onClick={() => props.onEventOpen?.(re[0])}
                             title={re.map((e) => `◆ ${eventPeriod(e)} ${e.title}`).join('\n')} aria-label={re[0].title}>◆</button>}
              {s ? s : ''}
            </td>
          )
        })}
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
        const over = !!v && v > 1.0001
        return <td key={w.index} className={`mm-cell mm-ro${over ? ' is-over' : ''}${w.index === thisWeek ? ' is-now' : ''}`}
                   style={{ backgroundColor: cellBg(v, over), ...lineStyle(w.index) }}
                   title={parts ? `${over ? '1.0 초과 — ' : ''}${parts.join(' + ')}` : undefined}>{fmtVal(v)}</td>
      })}
    </tr>
  ))

  const empty = mode === 'project' ? groups.length === 0 : people.length === 0
  const focusRow = focus ? sheet.rows.find((r) => r.id === focus.rowId) : undefined
  const focusNote = focus ? noteOf(focus.rowId, focus.week) : undefined
  const selInfo = focusRow && focus
    ? <><strong>{focusRow.personName}</strong> · {weeks[focus.week]?.label} 주{count > 1 ? <> 외 — <strong>{count}칸</strong> 선택</> : ''}</>
    : <>칸을 누르세요 <span className="muted">(끌기·Shift 로 범위, Ctrl+클릭으로 여러 칸)</span></>
  return (
    <>
      {!compact && (
        <div className="mm-tools">
          {mode === 'project' && editable ? (
            <>
              <span className="mm-tools__sel">{selInfo}</span>
              <span className="mm-tools__grp" role="group" aria-label="빠른 값">
                {QUICK.map((v) => <button key={v} type="button" className="btn btn--sm mm-q" disabled={!focus} onClick={() => { fill(v); wrap.current?.focus() }} title={`고른 칸에 ${v} 넣기`}>{v}</button>)}
                <button type="button" className="btn btn--sm mm-q" disabled={!focus} onClick={() => { fill(null); wrap.current?.focus() }} title="고른 칸 비우기 (Delete)">비움</button>
              </span>
              <span className="mm-tools__grp">
                <button type="button" className="btn btn--sm" disabled={!range || !!extra.size} onClick={() => { fillToEnd(); wrap.current?.focus() }}
                        title="고른 칸(여러 칸이면 그 모양)을 되풀이해 마지막 주까지 채우기. 끌어서 채우려면 범위 오른쪽 아래 ■ 를 끄세요">⇥ 끝까지</button>
                <button type="button" className={`btn btn--sm${focusNote ? ' btn--on' : ''}`} disabled={!focus} onClick={openNotes} title="고른 칸에 할 일 메모 (Shift+F2)">📝 메모</button>
              </span>
              <span className="mm-tools__grp">
                <button type="button" className="btn btn--sm btn--ghost" disabled={!props.canUndo} onClick={() => { props.onUndo?.(); wrap.current?.focus() }} title="되돌리기 (Ctrl+Z)">↶</button>
                <button type="button" className="btn btn--sm btn--ghost" disabled={!props.canRedo} onClick={() => { props.onRedo?.(); wrap.current?.focus() }} title="다시 (Ctrl+Y)">↷</button>
              </span>
            </>
          ) : <span className="mm-tools__sel">{mode === 'person' ? '사람별 부하는 보기 전용 — 1.0 넘는 칸은 빨강, 칸에 마우스를 올리면 구성이 보입니다. 고치려면 [프로젝트별].' : focusRow ? selInfo : ''}</span>}
          {thisWeek >= 0 && <button type="button" className="btn btn--sm btn--ghost mm-tools__now" onClick={() => jumpToNow(true)}>이번 주로</button>}
        </div>
      )}
      {!compact && focusNote && mode === 'project' && (
        <div className="mm-notebar" role="note">
          <span className="mm-notebar__tag">📝 {focusRow?.personName} · {weeks[focus!.week]?.label} 주</span>
          <span className="mm-notebar__text">{focusNote}</span>
          {editable && <button type="button" className="btn btn--ghost btn--sm" onClick={openNotes}>고치기</button>}
        </div>
      )}
      <div className={`mm-wrap${compact ? ' mm-wrap--compact' : ''}`} ref={wrap} tabIndex={0} onKeyDown={onKey} onPaste={onPaste} onCopy={onCopy}
           style={{ scrollPaddingTop: laneTop(lanes.length) }} aria-label="공수 표 — 방향키로 이동, 숫자로 입력">
        <table className="mm-grid">
          {head}
          <tbody>
            {body}
            {empty && <tr><td className="mm-sticky mm-c0 mm-empty" colSpan={4}>{q ? '찾는 프로젝트·인원이 없습니다.' : '아직 프로젝트·인원이 없습니다.'}</td><td colSpan={weeks.length} /></tr>}
          </tbody>
          <tfoot>
            <tr className="mm-total">
              <td className="mm-sticky mm-c0">주별 합계</td><td className="mm-sticky mm-c1" /><td className="mm-sticky mm-c2" /><td className="mm-sticky mm-c3" />
              {weeks.map((w) => {
                const rows = mode === 'project' ? groups.flatMap((g) => g.rows) : sheet.rows
                const t = r2(rows.reduce((s, r) => s + (val(r.id, w.index) ?? 0), 0))
                return <td key={w.index} className={w.index === thisWeek ? 'is-now' : undefined} style={lineStyle(w.index)}>{t || ''}</td>
              })}
            </tr>
          </tfoot>
        </table>
      </div>
      {noteFor && <NoteDialog cells={noteFor} rows={sheet.rows} weeks={weeks} onClose={() => { setNoteFor(null); wrap.current?.focus() }}
                              onSave={async (text) => { const ok = await props.onNotes?.(noteFor.map((p) => ({ ...p, text }))); if (ok) { setNoteFor(null); wrap.current?.focus() } }} />}
    </>
  )
}

/** 고른 칸(한 칸 또는 여러 칸)의 할 일 메모 */
function NoteDialog({ cells, rows, weeks, onClose, onSave }:
  { cells: Pos[]; rows: MmRow[]; weeks: MmWeek[]; onClose: () => void; onSave: (text: string) => Promise<void> }) {
  const byId = new Map(rows.map((r) => [r.id, r]))
  const texts = cells.map((p) => byId.get(p.rowId)?.notes?.[String(p.week)] ?? '')
  const distinct = [...new Set(texts.filter(Boolean))]
  const [text, setText] = useState(distinct.length === 1 && texts.every((t) => t === distinct[0]) ? distinct[0] : distinct.length === 1 ? distinct[0] : '')
  const [busy, setBusy] = useState(false)
  const people = [...new Set(cells.map((p) => byId.get(p.rowId)?.personName ?? ''))]
  const ws = [...new Set(cells.map((p) => p.week))].sort((a, b) => a - b)
  const target = cells.length === 1
    ? `${people[0]} · ${weeks[cells[0].week]?.label} 주`
    : `${cells.length}칸 — ${people.slice(0, 3).join(', ')}${people.length > 3 ? ` 외 ${people.length - 3}명` : ''} · ${weeks[ws[0]]?.label}${ws.length > 1 ? ` ~ ${weeks[ws[ws.length - 1]]?.label}` : ''} 주`
  const save = async (t: string) => { setBusy(true); try { await onSave(t) } finally { setBusy(false) } }
  return (
    <Modal title="할 일 메모" onClose={onClose}
           footer={<>
             {distinct.length > 0 && <button type="button" className="btn btn--danger" style={{ marginRight: 'auto' }} disabled={busy} onClick={() => save('')}>메모 지우기</button>}
             <button type="button" className="btn" onClick={onClose}>취소</button>
             <button type="button" className="btn btn--primary" disabled={busy || !text.trim()} onClick={() => save(text)}>저장</button>
           </>}>
      <p className="small"><strong>{target}</strong></p>
      {distinct.length > 1 && <p className="small mm-note-warn">칸마다 다른 메모가 {distinct.length}개 있습니다. 저장하면 고른 칸 모두 아래 메모로 바뀝니다.</p>}
      <textarea className="input" rows={5} value={text} onChange={(e) => setText(e.target.value)} maxLength={2000} autoFocus
                placeholder={'이 주에 할 일\n예) 2단계 통합 시험, 릴리스 노트 작성'}
                onKeyDown={(e) => { if ((e.ctrlKey || e.metaKey) && e.key === 'Enter' && text.trim()) save(text) }} />
      <p className="small muted">Ctrl+Enter 로 저장 · 메모가 있는 칸은 왼쪽 위에 노란 표시가 붙고, 칸을 고르면 표 위에 내용이 보입니다.</p>
    </Modal>
  )
}

interface RowProps {
  r: MmRow
  cells: Record<string, number>
  weeks: MmWeek[]
  thisWeek: number
  selFrom: number
  selTo: number
  edgeTop: boolean
  edgeBottom: boolean
  fillFrom: number
  fillTo: number
  /** ",주" 목록 — Ctrl+클릭으로 따로 고른 칸 */
  extra: string
  /** 채우기 손잡이를 붙일 주(-1 이면 없음) */
  handleWeek: number
  focusWeek: number
  edit: { init: string; typed: boolean } | null
  inputRef: RefObject<HTMLInputElement | null>
  /** ",주[!]" 목록 — 저장 전(!: 저장 실패) */
  pending: string
  /** ",주=합계 (구성)" 목록 — 그 사람의 그 주 합계가 1.0 초과 */
  over: string
  /** "주=색,…" — 이벤트가 있는 주의 색 줄 */
  lines: string
  editable: boolean
  onDown: (p: Pos, shift: boolean, ctrl: boolean) => void
  onEnter: (p: Pos) => void
  onDbl: (p: Pos, v: number | undefined) => void
  onBlur: () => void
  onFillStart: () => void
  onMenu?: (r: MmRow) => void
}

/** 사람 한 줄. 이 줄과 상관없는 변경(다른 줄 선택·입력)에는 다시 그리지 않는다. */
const PersonRow = memo(function PersonRow({ r, cells, weeks, thisWeek, selFrom, selTo, edgeTop, edgeBottom, fillFrom, fillTo, extra, handleWeek, focusWeek,
  edit, inputRef, pending, over, lines, editable, onDown, onEnter, onDbl, onBlur, onFillStart, onMenu }: RowProps) {
  const overMap = new Map<number, string>()
  for (const part of over.split(',').slice(1)) { const i = part.indexOf('='); overMap.set(Number(part.slice(0, i)), part.slice(i + 1)) }
  const pend = new Map<number, boolean>()
  for (const p of pending.split(',').slice(1)) pend.set(Number(p.replace('!', '')), p.endsWith('!'))
  const extras = new Set(extra.split(',').slice(1).map(Number))
  const lineMap = new Map(lines ? lines.split(',').map((x) => { const i = x.indexOf('='); return [Number(x.slice(0, i)), x.slice(i + 1)] as [number, string] }) : [])
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
        const inFill = !inSel && w.index >= fillFrom && w.index <= fillTo
        const isFocus = w.index === focusWeek
        const o = overMap.get(w.index)
        const p = pend.get(w.index)
        const note = r.notes?.[String(w.index)]
        const ln = lineMap.get(w.index)
        const cls = 'mm-cell' + (inSel ? ' is-insel' : '') + (extras.has(w.index) ? ' is-extra' : '') + (inFill ? ' is-fillprev' : '') + (isFocus ? ' is-sel' : '')
          + (w.index === thisWeek ? ' is-now' : '') + (p !== undefined ? (p ? ' is-failed' : ' is-pending') : '') + (o ? ' is-overp' : '') + (note ? ' has-note' : '')
          + (v !== undefined && Math.abs(v) >= 1000 ? ' is-wide' : '')
        const style: CSSProperties = { backgroundColor: cellBg(v) }
        if (ln) (style as Record<string, string>)['--ev-ln'] = `linear-gradient(to right, ${ln} 0 2px, transparent 2px)`
        const edges = inSel ? `${w.index === selFrom ? ' l' : ''}${w.index === selTo ? ' r' : ''}${edgeTop ? ' t' : ''}${edgeBottom ? ' b' : ''}` : ''
        const tip = [p ? '저장 안 됨 — 표 위 [다시 시도]' : '', o ? `이 사람 이 주 합계 ${o} — 1.0 초과` : '', note ? `📝 ${note}` : ''].filter(Boolean).join('\n')
        return (
          <td key={w.index} data-k={`${r.id}:${w.index}`} className={cls} style={style} title={tip || undefined}
              onMouseDown={(e) => { if (isFocus && edit) return; e.preventDefault(); onDown({ rowId: r.id, week: w.index }, e.shiftKey, e.ctrlKey || e.metaKey) }}
              onMouseEnter={() => onEnter({ rowId: r.id, week: w.index })}
              onDoubleClick={() => onDbl({ rowId: r.id, week: w.index }, v)}>
            {edges && <span className={`mm-edge${edges}`} aria-hidden />}
            {isFocus && edit
              ? <input ref={(el) => { inputRef.current = el; if (el && document.activeElement !== el) { el.focus(); el.setSelectionRange(el.value.length, el.value.length) } }}
                       className="mm-input" defaultValue={edit.init} onBlur={onBlur} inputMode="decimal" aria-label={`${r.personName} ${w.label} 주 값`} />
              : fmtVal(v)}
            {handleWeek === w.index && (
              <span className="mm-handle" title="끌어서 채우기 — 고른 값을 되풀이해 채웁니다"
                    onMouseDown={(e) => { e.preventDefault(); e.stopPropagation(); onFillStart() }} />
            )}
          </td>
        )
      })}
    </tr>
  )
})
