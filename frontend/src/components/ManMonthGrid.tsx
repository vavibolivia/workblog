import { memo, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type ClipboardEvent, type KeyboardEvent, type ReactNode, type RefObject } from 'react'
import { EVENT_KINDS, evColor, eventPeriod, eventWeeks, fmtVal, monthSpans, parseVal, personKey, shortDate, totalMm, weeksOf,
  type MmCellChange, type MmEvent, type MmNoteChange, type MmProject, type MmRow, type MmSheet, type MmWeek } from '../lib/manMonthApi'
import { IcNote, IcRedo, IcUndo } from './Icons'
import Modal from './Modal'

export type MmMode = 'project' | 'person'
export interface MmPos { rowId: number; week: number }

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
  onExpand?: (projectId: number) => void
  onError: (msg: string) => void
  onInfo: (msg: string) => void
  /** 보기 전용 표에서 입력하려 할 때 */
  onReadOnly?: () => void
  onProjectMenu?: (p: MmProject) => void
  onRowMenu?: (r: MmRow) => void
  onAddPerson?: (projectId: number) => void
  /** 이벤트 줄: 이벤트를 눌렀을 때 / 한 주의 이벤트 여럿 / 빈 곳을 눌러 새로 넣을 때 */
  onEventOpen?: (e: MmEvent) => void
  onWeekEvents?: (title: string, list: MmEvent[]) => void
  onEventAdd?: (date: string) => void
  /** 바깥(메모 목록 등)에서 이 칸으로 가기. n 이 바뀔 때마다 */
  jump?: MmPos & { n: number }
  compact?: boolean
}

type Pos = MmPos
interface Rect { r0: number; r1: number; w0: number; w1: number }
interface PersonAgg { key: string; name: string; department: string | null; projects: string[]; cells: Record<string, number>; parts: Record<string, string[]>; notes: Record<string, string[]> }
const QUICK = [0.25, 0.5, 0.75, 1]
const MAX_LANES = 3
const COL = 46            // 주 칸 너비(px) — CSS .mm-cell 과 같게
const BIG = 10            // 한 칸 값이 이보다 크면 "맞는지" 알림
const r2 = (n: number) => Math.round(n * 100) / 100
const kOf = (p: Pos) => `${p.rowId}:${p.week}`
const mod = (a: number, n: number) => ((a % n) + n) % n
const ymd = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
const coarse = () => typeof window !== 'undefined' && !!window.matchMedia?.('(pointer: coarse)').matches
/** 칸 색: 값이 클수록 진하게(1 이상은 가장 진하게, 그래도 글씨가 잘 보이도록 옅은 편). 사람별 합 1.0 초과는 빨강(over) */
function cellBg(v: number | undefined, over = false): string | undefined {
  if (!v || v < 0) return undefined
  if (over) return 'var(--mm-over)'
  // 회청색 단계(값이 클수록 진하게) — 글씨는 흰색(CSS)
  return `color-mix(in srgb, var(--mm-on) ${Math.round(35 + Math.min(v, 1) * 65)}%, var(--mm-off))`
}
/** 이름이 관련 이름과 같거나 "관련 이름 + 띄어쓰기" 로 시작할 때만 표시 (AirDeck → "AirDeck 2단계" O, "AirDeckX" X) */
export function relatedMatch(projectName: string, related: string | null): boolean {
  if (!related?.trim()) return false
  const n = projectName.trim().toLowerCase(), r = related.trim().toLowerCase()
  return n === r || n.startsWith(r + ' ')
}

export default function ManMonthGrid(props: Props) {
  const { sheet, mode, filter, editable, overlay, failed, onChange, collapsed, onToggle, onError, onInfo, compact } = props
  const weeks = useMemo(() => weeksOf(sheet.startDate, sheet.weeks), [sheet.startDate, sheet.weeks])
  const spans = useMemo(() => monthSpans(weeks), [weeks])
  const [anchor, setAnchor] = useState<Pos | null>(null)
  const [focus, setFocus] = useState<Pos | null>(null)
  /** Ctrl(⌘)+클릭으로 따로 고른 칸들. rangeOn=false 면 사각 범위 없이 이 칸들만 */
  const [extra, setExtra] = useState<Set<string>>(new Set())
  const [rangeOn, setRangeOn] = useState(true)
  const [edit, setEdit] = useState<{ init: string; typed: boolean } | null>(null)
  const [fillTo, setFillTo] = useState<Pos | null>(null)
  const [noteFor, setNoteFor] = useState<Pos[] | null>(null)
  const [live, setLive] = useState('')
  /** 메모가 있는 칸에 마우스를 올리면 바로 보이는 메모 */
  const [hoverNote, setHoverNote] = useState<{ text: string; x: number; y: number } | null>(null)
  /** 사람별 부하: 1.0 넘는 주가 있는 사람만 / 정렬(이름 · 가장 바쁜 주 · 기간 MM) */
  const [overOnly, setOverOnly] = useState(false)
  const [pSort, setPSort] = useState<'name' | 'peak' | 'mm'>('name')
  /** 터치 화면: 켜 두면 다음에 누르는 칸까지 범위로 고른다(Shift+클릭 대신) */
  const [touchRange, setTouchRange] = useState(false)
  const inputRef = useRef<HTMLInputElement | null>(null)
  const wrap = useRef<HTMLDivElement>(null)
  const dragging = useRef<'select' | 'fill' | null>(null)
  const thisWeek = useMemo(() => {
    const today = new Date()
    return weeks.findIndex((w) => today >= w.monday && today < new Date(w.monday.getTime() + 7 * 864e5))
  }, [weeks])

  // ---------- 값 (저장 전 값 포함) ----------
  const projectName = useMemo(() => new Map(sheet.projects.map((p) => [p.id, p.name])), [sheet.projects])
  const rowById = useMemo(() => new Map(sheet.rows.map((r) => [r.id, r])), [sheet.rows])
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
  const noteOf = useCallback((rowId: number, w: number): string | undefined => rowById.get(rowId)?.notes?.[String(w)], [rowById])

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

  // ---------- 이벤트: 줄(lane) 배치 · 주 머리 표시 · 프로젝트별 표시 ----------
  const ev = useMemo(() => {
    const start = weeks[0]?.monday
    const dayOf = (d: string) => { const [y, m, dd] = d.split('-').map(Number); return start ? Math.round((Date.UTC(y, m - 1, dd) - Date.UTC(start.getFullYear(), start.getMonth(), start.getDate())) / 864e5) : 0 }
    const total = weeks.length * 7
    const placed = (sheet.events ?? []).map((e) => ({ e, ws: eventWeeks(e, sheet.startDate, sheet.weeks) })).filter((x) => x.ws.length)
      .map(({ e, ws }) => {
        const r0 = dayOf(e.startDate), r1 = dayOf(e.endDate ?? e.startDate)
        const d0 = Math.max(0, r0), d1 = Math.min(total - 1, r1)
        const md = (s: string) => shortDate(s).replace(/\(.\)$/, '')
        // 여러 날이면 날 수도 적는다 — 앞의 옅은 막대가 그 기간(요일 위치까지)이라는 걸 글로도
        const label = `${e.endDate ? `${md(e.startDate)}~${md(e.endDate)} (${r1 - r0 + 1}일)` : shortDate(e.startDate)} ${e.title}`
        const textPx = [...label].reduce((t, ch) => t + (ch.charCodeAt(0) > 255 ? 12 : 7), 30)
        // 칠한 막대는 실제 기간만(요일 위치까지), 글자는 오른쪽으로 이어진다 — 겹치지 않게 글자 끝까지 자리 잡음
        const px0 = d0 * COL / 7, barPx = Math.max(8, (d1 - d0 + 1) * COL / 7)
        const room = total * COL / 7 - px0
        return { e, ws, label, px0, barPx, endPx: px0 + Math.min(room, Math.max(barPx, textPx)), cut0: r0 < 0, cut1: r1 > total - 1 }
      })
      .sort((a, b) => a.px0 - b.px0)
    const lanes: typeof placed[] = []
    const hidden: typeof placed = []
    for (const p of placed) {
      let lane = lanes.findIndex((l) => l.every((x) => x.endPx + 6 <= p.px0 || x.px0 >= p.endPx + 6))
      if (lane < 0 && lanes.length < MAX_LANES) { lanes.push([]); lane = lanes.length - 1 }
      if (lane < 0) { hidden.push(p); continue }
      lanes[lane].push(p)
    }
    const byWeek = new Map<number, MmEvent[]>()
    for (const { e, ws } of placed) for (const w of ws) byWeek.set(w, [...(byWeek.get(w) ?? []), e])
    const hiddenByWeek = new Map<number, MmEvent[]>()
    for (const { e, ws } of hidden) for (const w of ws) hiddenByWeek.set(w, [...(hiddenByWeek.get(w) ?? []), e])
    const mark = new Map<number, string>()
    for (const [w, list] of byWeek) mark.set(w, evColor(list[0]))
    return { lanes, byWeek, hiddenByWeek, mark }
  }, [sheet.events, sheet.startDate, sheet.weeks, weeks])
  const relatedEvents = useCallback((name: string) => {
    const m = new Map<number, MmEvent[]>()
    for (const [w, list] of ev.byWeek) {
      const hit = list.filter((e) => relatedMatch(name, e.related))
      if (hit.length) m.set(w, hit)
    }
    return m
  }, [ev.byWeek])

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

  // 찾기·접기로 보이는 줄이 바뀌면, 고른 범위를 한 칸으로 줄인다(숨은 줄까지 범위에 들어가 지워지는 사고 방지)
  const prevView = useRef(`${q}|${[...collapsed].join(',')}`)
  useEffect(() => {
    const key = `${q}|${[...collapsed].join(',')}`
    if (key === prevView.current) return
    prevView.current = key
    // eslint-disable-next-line react-hooks/set-state-in-effect -- 보이는 줄이 바뀌었을 때만 선택을 정리
    setExtra(new Set()); setRangeOn(true)
    setFocus((f) => { const keep = f && rowIndex.has(f.rowId) ? f : null; setAnchor(keep); return keep })
  }, [q, collapsed, rowIndex])

  const projectAgg = useMemo(() => new Map(groups.map((g) => [g.p.id, {
    sums: weeks.map((w) => r2(g.rows.reduce((t, r) => t + (rowCells.get(r.id)?.[String(w.index)] ?? 0), 0))),
    mm: r2(g.rows.reduce((t, r) => t + totalMm(rowCells.get(r.id) ?? {}, weeks), 0)),
    notes: weeks.map((w) => g.rows.flatMap((r) => { const t = r.notes?.[String(w.index)]; return t ? [`${r.personName}: ${t}`] : [] })),
    over: g.rows.some((r) => { const l = personLoad.get(personKey(r)); return !!l && [...l.values()].some((e) => e.sum > 1.0001) }),
  }])), [groups, weeks, rowCells, personLoad])

  const people: PersonAgg[] = useMemo(() => {
    if (mode !== 'person') return []
    const m = new Map<string, PersonAgg>()
    for (const r of sheet.rows) {
      const k = personKey(r)
      const a = m.get(k) ?? { key: k, name: r.personName, department: r.department, projects: [], cells: {}, parts: {}, notes: {} }
      const pn = projectName.get(r.projectId) ?? ''
      a.projects.push(pn)
      for (const [w, v] of Object.entries(rowCells.get(r.id) ?? {})) {
        a.cells[w] = r2((a.cells[w] ?? 0) + v)
        ;(a.parts[w] ??= []).push(`${pn} ${fmtVal(v)}`)
      }
      for (const [w, t] of Object.entries(r.notes ?? {})) (a.notes[w] ??= []).push(`${pn}: ${t}`)
      m.set(k, a)
    }
    const peak = (a: PersonAgg) => Math.max(0, ...Object.values(a.cells))
    const mmOf = (a: PersonAgg) => totalMm(a.cells, weeks)
    return [...m.values()].filter((a) => !q || a.name.toLowerCase().includes(q) || (a.department ?? '').toLowerCase().includes(q) || a.projects.some((p) => p.toLowerCase().includes(q)))
      .filter((a) => !overOnly || peak(a) > 1.0001)
      .sort((a, b) => (pSort === 'peak' ? peak(b) - peak(a) : pSort === 'mm' ? mmOf(b) - mmOf(a) : 0) || a.name.localeCompare(b.name, 'ko'))
  }, [mode, sheet.rows, rowCells, q, projectName, overOnly, pSort, weeks])

  const foot = useMemo(() => {
    const keys = new Set(people.map((a) => a.key))
    const rows = mode === 'project' ? groups.flatMap((g) => g.rows) : sheet.rows.filter((r) => keys.has(personKey(r)))
    return {
      weeks: weeks.map((w) => r2(rows.reduce((s, r) => s + (rowCells.get(r.id)?.[String(w.index)] ?? 0), 0))),
      mm: r2(rows.reduce((s, r) => s + totalMm(rowCells.get(r.id) ?? {}, weeks), 0)),
    }
  }, [mode, groups, sheet.rows, people, weeks, rowCells])

  // ---------- 선택 ----------
  const range: Rect | null = useMemo(() => {
    if (!rangeOn || !anchor || !focus) return null
    const a = rowIndex.get(anchor.rowId), f = rowIndex.get(focus.rowId)
    if (a === undefined || f === undefined) return null
    return { r0: Math.min(a, f), r1: Math.max(a, f), w0: Math.min(anchor.week, focus.week), w1: Math.max(anchor.week, focus.week) }
  }, [rangeOn, anchor, focus, rowIndex])
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
  const count = selected().length

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
    setExtra(new Set()); setRangeOn(true)
    if (scroll) scrollToCell(p)
  }
  // 고른 칸이 바뀌면 화면 읽기 프로그램에 알려 준다
  useEffect(() => {
    if (!focus || mode !== 'project') return
    const r = rowById.get(focus.rowId)
    if (!r) return
    const v = val(focus.rowId, focus.week)
    const load = personLoad.get(personKey(r))?.get(focus.week)
    const note = noteOf(focus.rowId, focus.week)
    // eslint-disable-next-line react-hooks/set-state-in-effect -- 고른 칸 설명(화면 읽기용)
    setLive(`${r.personName}, ${projectName.get(r.projectId) ?? ''}, ${weeks[focus.week]?.label} 주, ${v === undefined ? '빈 칸' : fmtVal(v)}`
      + `${load && load.sum > 1.0001 ? `, 이 사람 합계 ${load.sum} 초과` : ''}${note ? `, 메모: ${note}` : ''}${count > 1 ? `, ${count}칸 선택` : ''}`)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [focus, count])

  // ---------- 바꾸기 ----------
  /** 1.0 을 넘게 되는 사람·주, 지나치게 큰 값을 알려 준다(막지는 않음) */
  const warn = (changes: MmCellChange[]) => {
    const big = changes.find((c) => c.value !== null && Math.abs(c.value) > BIG)
    if (big) { onError(`큰 값 ${fmtVal(big.value!)} 이 들어갔습니다(보통 투입률 0~1). 잘못이면 Ctrl+Z 로 되돌리세요.`); return }
    const delta = new Map<string, number>()
    for (const c of changes) {
      const r = rowById.get(c.rowId)
      if (!r) continue
      const k = `${personKey(r)}|${c.week}`
      delta.set(k, (delta.get(k) ?? 0) + (c.value ?? 0) - (val(c.rowId, c.week) ?? 0))
    }
    const over: string[] = []
    for (const [k, d] of delta) {
      const [pk, w] = k.split('|')
      const now = personLoad.get(pk)?.get(Number(w))?.sum ?? 0
      if (d > 0 && r2(now + d) > 1.0001) {
        const name = sheet.rows.find((r) => personKey(r) === pk)?.personName ?? ''
        over.push(`${name} ${weeks[Number(w)]?.label} 주 합계 ${r2(now + d)}`)
      }
    }
    if (over.length) onInfo(`1.0 초과 — ${over.slice(0, 3).join(', ')}${over.length > 3 ? ` 외 ${over.length - 3}건` : ''}`)
  }
  const put = (changes: MmCellChange[], quiet = false) => {
    const real = changes.filter((c) => (val(c.rowId, c.week) ?? null) !== c.value)
    if (!real.length) return 0
    warn(real)
    onChange(real)
    if (!quiet && real.length > 1) onInfo(`${real.length}칸 바꿈 — Ctrl+Z 로 되돌리기`)
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
    put(ch)
    setRangeOn(true); setExtra(new Set())
    setAnchor({ rowId: navRows[dst.r0].id, week: dst.w0 }); setFocus({ rowId: navRows[dst.r1].id, week: dst.w1 })
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
      else onInfo('왼쪽에 복사할 칸이 없습니다.')
    } else {
      if (range.r1 > range.r0) tile({ ...range, r1: range.r0 }, range)
      else if (range.r0 > 0 && navRows[range.r0 - 1]?.projectId === navRows[range.r0]?.projectId) tile({ ...range, r0: range.r0 - 1, r1: range.r0 - 1 }, { ...range, r0: range.r0 - 1 })
      else onInfo('같은 프로젝트 안에 위 칸이 없습니다.')
    }
  }
  /** 편집 중인 값 확정. all=true(Ctrl+Enter) 면 고른 칸 모두, 아니면 지금 칸만(엑셀과 같음). 잘못된 값이면 이어 간다(false). */
  const commitEdit = (all = false): boolean => {
    const el = inputRef.current
    if (edit === null || !el || !focus) return true
    const v = parseVal(el.value)
    if (v === 'bad') { onError('숫자만 넣을 수 있습니다(예: 0.5 · 1 · 50% = 0.5).'); el.select(); return false }
    inputRef.current = null   // 아래 focus 이동으로 생기는 blur 에서 다시 확정하지 않도록
    setEdit(null)
    if (all && count > 1) fill(v); else put([{ ...focus, value: v }])
    wrap.current?.focus()
    return true
  }
  const move = (dr: number, dw: number, extend = false, jump = false) => {
    if (!navRows.length) return
    if (!focus) { select({ rowId: navRows[0].id, week: Math.max(0, thisWeek) }, false); return }
    const i = rowIndex.get(focus.rowId) ?? 0
    const ni = jump ? (dr < 0 ? 0 : dr > 0 ? navRows.length - 1 : i) : Math.max(0, Math.min(navRows.length - 1, i + dr))
    const nw = jump ? (dw < 0 ? 0 : dw > 0 ? weeks.length - 1 : focus.week) : Math.max(0, Math.min(weeks.length - 1, focus.week + dw))
    select({ rowId: navRows[ni].id, week: nw }, extend)
  }
  const openNotes = () => { const s = selected(); if (s.length) setNoteFor(s) }

  // 바깥에서 "이 칸으로" (메모 목록 누르기 등): 접힌 프로젝트는 펼치고 고른다
  useEffect(() => {
    const j = props.jump
    if (!j) return
    const r = rowById.get(j.rowId)
    if (r && collapsed.has(r.projectId)) props.onExpand?.(r.projectId)
    const t = window.setTimeout(() => {
      setFocus({ rowId: j.rowId, week: j.week }); setAnchor({ rowId: j.rowId, week: j.week }); setExtra(new Set()); setRangeOn(true)
      scrollToCell({ rowId: j.rowId, week: j.week }); wrap.current?.focus({ preventScroll: true })
    }, 40)
    return () => window.clearTimeout(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [props.jump?.n])

  const readOnlyKey = (e: KeyboardEvent) => /^[-0-9.]$/.test(e.key) || e.key === 'Delete' || e.key === 'Backspace'
  const onKey = (e: KeyboardEvent) => {
    if (mode !== 'project') return
    const ctrl = e.ctrlKey || e.metaKey
    if (!focus) {
      // 키보드로 들어온 경우: 방향키·Enter 로 첫 줄의 이번 주 칸을 고른다
      if (e.key.startsWith('Arrow') || e.key === 'Enter' || e.key === 'Home') { e.preventDefault(); move(0, 0) }
      return
    }
    if (edit !== null) {
      if (e.key === 'Enter' && !e.nativeEvent.isComposing) { e.preventDefault(); if (commitEdit(ctrl) && !ctrl) move(e.shiftKey ? -1 : 1, 0) }
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
    if (ctrl && (key === 'z' || key === 'y')) {
      e.preventDefault()
      if (!editable) { props.onReadOnly?.(); return }
      const redo = key === 'y' || e.shiftKey
      const n = redo ? props.onRedo?.() : props.onUndo?.()
      if (n) onInfo(`${n}칸 ${redo ? '다시 함' : '되돌림'}`)
      return
    }
    if (ctrl && (key === 'r' || key === 'd')) { e.preventDefault(); if (editable) fillDir(key === 'r' ? 'right' : 'down'); else props.onReadOnly?.(); return }
    if (ctrl && key === 'a' && navRows.length) {
      e.preventDefault()
      setExtra(new Set()); setRangeOn(true); setAnchor({ rowId: navRows[0].id, week: 0 }); setFocus({ rowId: navRows[navRows.length - 1].id, week: weeks.length - 1 })
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
    if (e.key === 'Escape') { if (dragging.current === 'fill') { dragging.current = null; setFillTo(null) } setAnchor(focus); setExtra(new Set()); setRangeOn(true); return }
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
    onInfo(`${(range.r1 - range.r0 + 1) * (range.w1 - range.w0 + 1)}칸 복사${extra.size ? ' (Ctrl+클릭으로 더 고른 칸은 빼고)' : ''} — 엑셀에 붙여넣을 수 있습니다.`)
  }
  const onPaste = (e: ClipboardEvent) => {
    if (!focus || mode !== 'project' || edit !== null) return
    e.preventDefault()
    if (!editable) { props.onReadOnly?.(); return }
    const fi = rowIndex.get(focus.rowId) ?? 0
    const top = range ?? { r0: fi, r1: fi, w0: focus.week, w1: focus.week }
    const lines = e.clipboardData.getData('text/plain').replace(/\r/g, '').split('\n').filter((l, i, a) => l !== '' || i < a.length - 1)
    const changes: MmCellChange[] = []
    let bad = 0, clipped = 0, crossed = false
    if (lines.length === 1 && !lines[0].includes('\t') && count > 1) {
      // 값 하나를 여러 칸에 붙여넣으면 고른 칸을 모두 채운다(엑셀과 같음)
      const v = parseVal(lines[0])
      if (v === 'bad') bad = count
      else changes.push(...selected().map((p) => ({ ...p, value: v })))
    } else {
      const startProject = navRows[top.r0]?.projectId
      let rEnd = top.r0, wEnd = top.w0
      lines.forEach((line, i) => line.split('\t').forEach((t, j) => {
        const r = navRows[top.r0 + i]
        const w = top.w0 + j
        if (!r || w >= weeks.length) { clipped++; return }
        if (r.projectId !== startProject) crossed = true
        const v = parseVal(t)
        if (v === 'bad') bad++
        else { changes.push({ rowId: r.id, week: w, value: v }); rEnd = Math.max(rEnd, top.r0 + i); wEnd = Math.max(wEnd, w) }
      }))
      if (changes.length) { setRangeOn(true); setExtra(new Set()); setAnchor({ rowId: navRows[top.r0].id, week: top.w0 }); setFocus({ rowId: navRows[rEnd].id, week: wEnd }) }
    }
    if (changes.length) put(changes, true)
    const msg = [`${changes.length}칸 붙여넣음`, clipped ? `표를 넘친 ${clipped}칸은 버림` : '', bad ? `숫자가 아닌 ${bad}칸은 건너뜀` : '', crossed ? '다른 프로젝트 줄까지 들어감' : ''].filter(Boolean).join(' · ')
    if (bad || clipped || crossed) onError(`${msg} — Ctrl+Z 로 되돌리기`); else onInfo(`${msg} — Ctrl+Z 로 되돌리기`)
  }

  // ---------- 마우스 ----------
  const latest = useRef({ edit, commitEdit, select, editable, range, fillRect, tile, focus, anchor, rangeOn, cellsOfRect, extra, touchRange })
  useLayoutEffect(() => { latest.current = { edit, commitEdit, select, editable, range, fillRect, tile, focus, anchor, rangeOn, cellsOfRect, extra, touchRange } })
  const onReadOnlyRef = useRef(props.onReadOnly)
  useLayoutEffect(() => { onReadOnlyRef.current = props.onReadOnly })
  const onCellDown = useCallback((p: Pos, shift: boolean, ctrl: boolean) => {
    const l = latest.current
    if (l.edit !== null && !l.commitEdit()) return
    wrap.current?.focus({ preventScroll: true })
    if (ctrl) {
      // Ctrl(⌘)+클릭: 지금 고른 범위는 묶음으로 남기고, 누른 칸을 더하거나(이미 있으면) 뺀다
      setExtra((s) => {
        const n = new Set(s)
        if (l.rangeOn && l.range) for (const c of l.cellsOfRect(l.range)) n.add(kOf(c))
        const k = kOf(p)
        if (n.has(k)) n.delete(k); else n.add(k)
        return n
      })
      setRangeOn(false); setFocus(p); setAnchor(p)
      return
    }
    // 터치 '범위 고르기': 처음 고른 칸부터 누른 칸까지 범위로 (한 번 고르면 꺼짐)
    if (l.touchRange && coarse() && l.anchor) { l.select(p, true, false); setTouchRange(false); return }
    // 터치에서는 이미 고른 칸을 다시 누르면 바로 입력
    if (coarse() && l.editable && !shift && l.focus && l.focus.rowId === p.rowId && l.focus.week === p.week && l.anchor && l.anchor.rowId === p.rowId && l.anchor.week === p.week) {
      setEdit({ init: '', typed: false }); return
    }
    dragging.current = 'select'
    l.select(p, shift, false)
  }, [])
  const onCellEnter = useCallback((p: Pos) => {
    if (dragging.current === 'select') setFocus(p)
    else if (dragging.current === 'fill') setFillTo(p)
  }, [])
  const onCellDbl = useCallback((p: Pos, v: number | undefined) => {
    if (!latest.current.editable) { onReadOnlyRef.current?.(); return }
    setAnchor(p); setFocus(p); setExtra(new Set()); setRangeOn(true); setEdit({ init: fmtVal(v), typed: false })
  }, [])
  // 칸 오른쪽 클릭: 고른 칸 밖이면 그 칸을 고르고, 그 자리에 메모 입력 칸
  const [ctx, setCtx] = useState<{ p: Pos; v: number | undefined; x: number; y: number } | null>(null)
  const onCellCtx = useCallback((p: Pos, v: number | undefined, x: number, y: number) => {
    const l = latest.current
    if (l.edit !== null && !l.commitEdit()) return
    const inRect = l.rangeOn && l.range && l.cellsOfRect(l.range).some((c) => c.rowId === p.rowId && c.week === p.week)
    if (!inRect && !l.extra.has(kOf(p))) { setExtra(new Set()); l.select(p, false, false) }
    wrap.current?.focus({ preventScroll: true })
    setCtx({ p, v, x, y })
  }, [])
  useEffect(() => {
    if (!ctx) return
    const close = (e: Event) => { if (!(e.target instanceof Node && document.querySelector('.mm-ctx')?.contains(e.target))) setCtx(null) }
    const esc = (e: globalThis.KeyboardEvent) => { if (e.key === 'Escape') { e.stopPropagation(); setCtx(null); wrap.current?.focus() } }
    const off = () => setCtx(null)
    window.addEventListener('mousedown', close, true); window.addEventListener('keydown', esc, true)
    window.addEventListener('resize', off); wrap.current?.addEventListener('scroll', off)
    const w = wrap.current
    return () => { window.removeEventListener('mousedown', close, true); window.removeEventListener('keydown', esc, true); window.removeEventListener('resize', off); w?.removeEventListener('scroll', off) }
  }, [ctx])
  const onFillStart = useCallback(() => { dragging.current = 'fill'; setFillTo(null) }, [])
  const onEditBlur = useCallback(() => { if (latest.current.edit !== null && inputRef.current) latest.current.commitEdit() }, [])
  useEffect(() => {
    const up = () => {
      const l = latest.current
      if (dragging.current === 'fill' && l.range && l.fillRect) l.tile(l.range, l.fillRect)
      if (dragging.current === 'fill') setFillTo(null)
      dragging.current = null
    }
    const cancel = () => { if (dragging.current === 'fill') setFillTo(null); dragging.current = null }
    // 끄는 중 표 가장자리에 가면 저절로 넘긴다
    const autoScroll = (e: MouseEvent) => {
      const el = wrap.current
      if (!dragging.current || !el || !(e.buttons & 1)) return
      const b = el.getBoundingClientRect()
      const dx = e.clientX > b.right - 36 ? 24 : e.clientX < b.left + 140 && el.scrollLeft > 0 ? -24 : 0
      const dy = e.clientY > b.bottom - 30 ? 20 : e.clientY < b.top + 90 && el.scrollTop > 0 ? -20 : 0
      if (dx || dy) el.scrollBy(dx, dy)
    }
    window.addEventListener('mouseup', up); window.addEventListener('blur', cancel); window.addEventListener('mousemove', autoScroll)
    return () => { window.removeEventListener('mouseup', up); window.removeEventListener('blur', cancel); window.removeEventListener('mousemove', autoScroll) }
  }, [])

  // ---------- 이번 주로 ----------
  const jumpToNow = useCallback((smooth: boolean) => {
    const el = wrap.current
    const th = el?.querySelector<HTMLElement>('th.mm-week.is-now')
    if (!el || !th) return
    // 보이는 고정 열의 오른쪽 끝(좁은 화면에서는 소속·역할 열이 숨겨짐)
    const sticks = [...el.querySelectorAll<HTMLElement>('thead tr:first-child .mm-sticky')].filter((s) => s.offsetWidth > 0)
    const right = sticks.reduce((m, s) => Math.max(m, s.offsetLeft + s.offsetWidth), 0)
    el.scrollTo({ left: Math.max(0, th.offsetLeft - right - COL), behavior: smooth ? 'smooth' : 'auto' })
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
        <th rowSpan={2} className="mm-sticky mm-c2 mm-stick-last" title={mode === 'project' ? '사람 줄은 역할, 프로젝트 줄은 이 표 기간의 MM(달마다 값 합 ÷ 그 달 월요일 수)' : undefined}>{mode === 'project' ? '역할 · 기간 MM' : '프로젝트 수'}</th>
        {spans.map(([k, n]) => <th key={k} colSpan={n} className="mm-month"><span className="mm-month__l">{k.replace('-', '.')}</span></th>)}
      </tr>
      <tr>{weeks.map((w) => {
        const list = ev.byWeek.get(w.index)
        const hid = ev.hiddenByWeek.get(w.index)
        const c = ev.mark.get(w.index)
        return (
          <th key={w.index} className={`mm-week${w.index === thisWeek ? ' is-now' : ''}${c ? ' has-ev' : ''}`} style={c ? { ['--ev' as string]: c } : undefined}
              title={`${w.monday.toLocaleDateString('ko-KR')} 주${w.index === thisWeek ? ' (이번 주)' : ''}${list ? '\n' + list.map((e) => `${EVENT_KINDS[e.kind].sym} ${eventPeriod(e)} ${e.title}`).join('\n') : ''}`}>
            {w.label}
            {hid && <button type="button" className="mm-evmore" onClick={() => props.onWeekEvents?.(`${w.label} 주 이벤트`, list ?? hid)} aria-label={`${w.label} 주 이벤트 ${list?.length ?? hid.length}개 보기`}>+{hid.length}</button>}
          </th>
        )
      })}</tr>
      {lanes.map((lane, li) => (
        <tr key={`lane${li}`} className="mm-evrow">
          <th className="mm-sticky mm-c0 mm-evlabel" style={{ top: laneTop(li) }}>
            {li === 0 && <>
              <span>이벤트</span>
              {props.onEventAdd && <button type="button" className="mm-mini mm-mini--add" onClick={() => props.onEventAdd?.(weekDate(Math.max(0, thisWeek)))} title="이벤트 넣기 — 공용: 기간이 걸친 모든 공수 표에 보입니다">+ 이벤트</button>}
              {!ev.lanes.length && <span className="muted small"> 이 기간에 없음</span>}
            </>}
          </th>
          <th className="mm-sticky mm-c1 mm-evlabel" style={{ top: laneTop(li) }} />
          <th className="mm-sticky mm-c2 mm-evlabel" style={{ top: laneTop(li) }} />
          {/* 막대는 주 칸 위에 날짜 위치대로 겹쳐 그린다(칸 너비 고정) */}
          <th colSpan={weeks.length} className="mm-evtrack" style={{ top: laneTop(li) }}
              onDoubleClick={props.onEventAdd ? (e) => {
                const x = e.clientX - (e.currentTarget as HTMLElement).getBoundingClientRect().left
                props.onEventAdd?.(weekDate(Math.max(0, Math.min(weeks.length - 1, Math.floor(x / COL)))))
              } : undefined}
              title={props.onEventAdd ? '빈 곳을 두 번 누르면 그 주에 이벤트 넣기' : undefined}>
            {lane.map((it) => {
              const k = EVENT_KINDS[it.e.kind]
              return (
                <button key={it.e.id} type="button" className={`mm-ev2${it.e.endDate ? '' : ' is-day'}${it.cut0 ? ' cut0' : ''}${it.cut1 ? ' cut1' : ''}`}
                        style={{ ['--ev' as string]: evColor(it.e), ['--bar' as string]: `${it.barPx}px`, left: it.px0 }}
                        onClick={() => props.onEventOpen?.(it.e)} onDoubleClick={(e) => e.stopPropagation()}
                        aria-label={`이벤트 ${eventPeriod(it.e)} ${it.e.title}`}
                        title={`${eventPeriod(it.e)}\n${it.e.title}${it.e.related ? `\n관련: ${it.e.related}` : ''}${it.e.note ? `\n${it.e.note}` : ''}`}>
                  <span className="mm-ev2__bar" aria-hidden />
                  <span className="mm-ev2__txt"><b aria-hidden>{it.cut0 ? '◀ ' : ''}{k.sym}</b>{it.label}{it.cut1 ? ' ▶' : ''}</span>
                </button>
              )
            })}
          </th>
        </tr>
      ))}
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
  const handleAt = range && !extra.size && editable && edit === null && mode === 'project' && !coarse() ? { rowId: navRows[range.r1]?.id, week: range.w1 } : null

  // 사람별로 이 표에서 맡은 프로젝트(역할) — 이름 칸 툴팁용
  const whereOf = useMemo(() => {
    const m = new Map<string, string[]>()
    for (const r of sheet.rows) { const k = personKey(r); m.set(k, [...(m.get(k) ?? []), `${projectName.get(r.projectId) ?? ''}${r.role ? `(${r.role})` : ''}`]) }
    return m
  }, [sheet.rows, projectName])
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
                 edit={focus?.rowId === r.id && edit ? edit : null} inputRef={inputRef} pending={pendingOf.get(r.id) ?? ''} over={over}
                 who={`${[r.personName, r.department, r.role].filter(Boolean).join(' · ')}${r.userId == null ? ' (외부)' : ''}\n이 표: ${whereOf.get(personKey(r))?.join(', ') ?? ''}`}
                 editable={editable} onDown={onCellDown} onEnter={onCellEnter} onDbl={onCellDbl} onBlur={onEditBlur} onFillStart={onFillStart} onCtx={onCellCtx} onMenu={props.onRowMenu} />
    )
  }

  const body = mode === 'project' ? groups.map((g) => {
    const open = isOpen(g.p.id)
    const agg = projectAgg.get(g.p.id)!
    const rel = relatedEvents(g.p.name)
    return [
      <tr key={`p${g.p.id}`} className="mm-project">
        <th scope="rowgroup" className="mm-sticky mm-c0">
          <button type="button" className="mm-toggle" onClick={() => onToggle(g.p.id)} aria-expanded={open} disabled={!!q} title={q ? '찾는 중에는 모두 펼쳐 보입니다' : open ? '접기' : '펼치기'}>{open ? '▾' : '▸'} {g.p.name}</button>
          <span className="mm-count">{g.rows.length}명</span>
          {agg.over && !open && <span className="mm-overtag" title="이 프로젝트에 사람별 합 1.0 을 넘는 주가 있습니다">1.0 초과</span>}
          {editable && props.onAddPerson && <button type="button" className="mm-mini mm-mini--add" onClick={() => props.onAddPerson?.(g.p.id)} title="이 프로젝트에 인원 추가">+ 인원</button>}
          {editable && props.onProjectMenu && <button type="button" className="mm-mini" onClick={() => props.onProjectMenu?.(g.p)} title="프로젝트 이름 고치기 · 삭제" aria-label={`${g.p.name} 프로젝트 편집`}>⋯</button>}
        </th>
        <td className="mm-sticky mm-c1" />
        <td className="mm-sticky mm-c2 mm-pmm" title="이 표 기간의 MM (달마다 값 합 ÷ 그 달 월요일 수)">{agg.mm ? `${agg.mm} MM` : ''}</td>
        {agg.sums.map((s, i) => {
          const re = rel.get(i)
          return (
            <td key={i} className={`mm-psum${i === thisWeek ? ' is-now' : ''}${agg.notes[i].length ? ' has-note' : ''}`}
                title={s ? String(s) : undefined} data-note={agg.notes[i].length ? agg.notes[i].join('\n') : undefined}>
              {re && <button type="button" className="mm-diamond" style={{ ['--ev' as string]: evColor(re[0]) }}
                             onClick={() => (re.length > 1 ? props.onWeekEvents?.(`${g.p.name} · ${weeks[i].label} 주`, re) : props.onEventOpen?.(re[0]))}
                             title={re.map((e) => `${eventPeriod(e)} ${e.title}`).join('\n')}
                             aria-label={re.map((e) => `이벤트 ${e.title}`).join(', ')}>{EVENT_KINDS[re[0].kind].sym}</button>}
              {s ? s : ''}
            </td>
          )
        })}
      </tr>,
      ...(open ? g.rows.map(personRow) : []),
    ]
  }) : people.map((a) => (
    <tr key={a.key} className="mm-person">
      <th scope="row" className="mm-sticky mm-c0 mm-name" title={a.projects.join(', ')}>{a.name}</th>
      <td className="mm-sticky mm-c1 mm-dim">{a.department ?? ''}</td>
      <td className="mm-sticky mm-c2 mm-dim" title={a.projects.join(', ')}>{a.projects.length}</td>
      {weeks.map((w) => {
        const v = a.cells[String(w.index)]
        const parts = a.parts[String(w.index)]
        const over = !!v && v > 1.0001
        const notes = a.notes[String(w.index)]
        return <td key={w.index} className={`mm-cell mm-ro${over ? ' is-over' : ''}${w.index === thisWeek ? ' is-now' : ''}${notes ? ' has-note' : ''}`}
                   style={{ backgroundColor: cellBg(v, over) }}
                   title={parts ? `${over ? '1.0 초과 — ' : ''}${parts.join(' + ')}` : undefined} data-note={notes ? notes.join('\n') : undefined}>{fmtVal(v)}</td>
      })}
    </tr>
  ))

  const empty = mode === 'project' ? groups.length === 0 : people.length === 0
  const focusRow = focus ? rowById.get(focus.rowId) : undefined
  const focusNote = focus ? noteOf(focus.rowId, focus.week) : undefined
  const focusLoad = focusRow && focus ? personLoad.get(personKey(focusRow))?.get(focus.week) : undefined
  const selInfo = focusRow && focus
    ? <><strong>{focusRow.personName}</strong> · {weeks[focus.week]?.label} 주{count > 1 ? <> — <strong>{count}칸</strong></> : ''}</>
    : <span className="muted" title={editable ? '숫자 입력 후 Enter · 칸 오른쪽 클릭 = 메모 · 끌거나 Shift 로 범위 · Ctrl+클릭으로 여러 칸 · Delete 비움 · Ctrl+Z 되돌리기 · 바로 저장됨' : undefined}>
        {coarse() ? '칸을 누르세요 (다시 누르면 입력)' : editable ? '칸을 누르고 숫자 입력 · 오른쪽 클릭 = 메모' : '칸을 누르세요'}</span>
  const showBar = !compact && mode === 'project'
  return (
    <>
      <div className="sr-only" aria-live="polite">{live}</div>
      {!compact && (
        <div className="mm-tools">
          <span className="mm-tools__sel">{mode === 'person' ? <span className="muted">사람별 부하 — 보기 전용 · 1.0 초과는 빨간 칸</span> : selInfo}</span>
          {mode === 'person' && (
            <span className="mm-tools__grp">
              <label className="mm-chk"><input type="checkbox" checked={overOnly} onChange={(e) => setOverOnly(e.target.checked)} /> 1.0 초과인 사람만</label>
              <label className="mm-chk">정렬{' '}
                <select value={pSort} onChange={(e) => setPSort(e.target.value as typeof pSort)} aria-label="사람 정렬">
                  <option value="name">이름</option>
                  <option value="peak">가장 바쁜 주(큰 순)</option>
                  <option value="mm">기간 MM(큰 순)</option>
                </select>
              </label>
              <span className="muted small">{people.length}명</span>
            </span>
          )}
          {mode === 'project' && coarse() && (
            <span className="mm-tools__grp">
              <button type="button" className="btn btn--sm" aria-pressed={touchRange} disabled={!focus} onClick={() => setTouchRange((v) => !v)}
                      title="켠 뒤 끝 칸을 누르면 지금 칸부터 그 칸까지 범위로 고릅니다">{touchRange ? '끝 칸을 누르세요' : '범위 고르기'}</button>
            </span>
          )}
          {mode === 'project' && editable && (
            <>
              <span className="mm-tools__grp" role="group" aria-label="빠른 값">
                {QUICK.map((v) => <button key={v} type="button" className="btn btn--sm mm-q" disabled={!count} onClick={() => { fill(v); wrap.current?.focus() }} title={`고른 칸에 ${v} 넣기`}>{v}</button>)}
                <button type="button" className="btn btn--sm mm-q" disabled={!count} onClick={() => { fill(null); wrap.current?.focus() }} title="고른 칸 비우기 (Delete)">비움</button>
              </span>
              <span className="mm-tools__grp">
                <button type="button" className="btn btn--sm" disabled={!range || !!extra.size} onClick={() => { fillToEnd(); wrap.current?.focus() }}
                        title="고른 칸(여러 칸이면 그 모양)을 되풀이해 마지막 주까지 채우기. 범위 오른쪽 아래 ■ 를 끌어도 됩니다">끝 주까지 채우기</button>
              </span>
              <span className="mm-tools__grp">
                <button type="button" className="btn btn--sm btn--ghost" disabled={!props.canUndo} onClick={() => { const n = props.onUndo?.(); if (n) onInfo(`${n}칸 되돌림`); wrap.current?.focus() }} title="되돌리기 (Ctrl+Z)" aria-label="되돌리기"><IcUndo /></button>
                <button type="button" className="btn btn--sm btn--ghost" disabled={!props.canRedo} onClick={() => { const n = props.onRedo?.(); if (n) onInfo(`${n}칸 다시 함`); wrap.current?.focus() }} title="다시 (Ctrl+Y)" aria-label="다시"><IcRedo /></button>
              </span>
            </>
          )}
          {thisWeek >= 0 && <button type="button" className="btn btn--sm mm-tools__now" onClick={() => jumpToNow(true)}>이번 주로</button>}
        </div>
      )}
      {showBar && (
        <div className={`mm-notebar${focusNote ? ' has-note' : ''}`} role="note">
          {focusRow && focus ? <>
            {focusLoad && focusLoad.parts.length > 0 && (
              <span className={`mm-notebar__load${focusLoad.sum > 1.0001 ? ' is-over' : ''}`}>이 사람 이 주 합계 <strong>{focusLoad.sum}</strong>{focusLoad.parts.length > 1 ? ` = ${focusLoad.parts.join(' + ')}` : ''}{focusLoad.sum > 1.0001 ? ' — 1.0 초과' : ''}</span>
            )}
            {focusNote
              ? <><span className="mm-notebar__tag"><IcNote /> 메모</span><span className="mm-notebar__text">{focusNote}</span>{editable && <button type="button" className="btn btn--sm btn--ghost" onClick={openNotes}>고치기</button>}</>
              : <span className="muted">메모 없음{editable ? ' — 칸을 오른쪽 클릭해 바로 적기' : ''}</span>}
          </> : <span className="muted">{editable
            ? (coarse() ? '칸을 누르고 다시 누르면 입력' : '숫자 입력 후 Enter · 칸을 오른쪽 클릭하면 바로 메모 · 끌거나 Shift 로 범위 · Ctrl+클릭으로 여러 칸 · Delete 비움 · Ctrl+Z 되돌리기 · 바로 저장됨')
            : '칸을 누르면 그 주 합계와 메모가 여기에 보입니다.'}</span>}
        </div>
      )}
      <div className={`mm-wrap${compact ? ' mm-wrap--compact' : ''}`} ref={wrap} tabIndex={0} onKeyDown={onKey} onPaste={onPaste} onCopy={onCopy}
           onMouseOver={(e) => {
             const td = (e.target as HTMLElement).closest<HTMLElement>('td[data-note]')
             if (!td || dragging.current || ctx) { if (hoverNote) setHoverNote(null); return }
             const r = td.getBoundingClientRect()
             if (hoverNote?.x === r.right && hoverNote.y === r.top) return
             setHoverNote({ text: td.dataset.note ?? '', x: r.right, y: r.top })
           }}
           onMouseLeave={() => setHoverNote(null)} onScroll={() => { if (hoverNote) setHoverNote(null) }}
           onFocus={(e) => { if (e.target === e.currentTarget && !focus && mode === 'project' && !compact) setLive('공수 표 — 방향키를 누르면 이번 주 첫 칸부터 고릅니다.') }}
           style={{ scrollPaddingTop: laneTop(lanes.length) }} role="region" aria-label="공수 표 — 방향키로 이동, 숫자로 입력, Shift+F2 메모">
        <table className="mm-grid">
          {head}
          <tbody>
            {body}
            {empty && <tr><td className="mm-c0 mm-empty" colSpan={3 + weeks.length}>{q ? '찾는 프로젝트·인원이 없습니다.' : mode === 'person' && overOnly ? '1.0 을 넘는 사람이 없습니다.' : '아직 프로젝트·인원이 없습니다.'}</td></tr>}
          </tbody>
          <tfoot>
            <tr className="mm-total">
              <th scope="row" className="mm-sticky mm-c0" title="주 칸 = 그 주 투입 합(1.0 = 한 사람 한 주)">주별 합계 <span className="muted small">(투입 인원)</span></th><td className="mm-sticky mm-c1" />
              <td className="mm-sticky mm-c2 mm-pmm" title="표 전체 기간 MM">{mode === 'project' && foot.mm ? `${foot.mm} MM` : ''}</td>
              {foot.weeks.map((t, i) => <td key={i} className={i === thisWeek ? 'is-now' : undefined} title={t ? String(t) : undefined}>{t || ''}</td>)}
            </tr>
          </tfoot>
        </table>
      </div>
      {ctx && (() => {
        // 오른쪽 클릭 = 메모만(그 자리에서 쓰고 Enter)
        const row = rowById.get(ctx.p.rowId)
        const cells = selected()
        return <MemoPop key={`${ctx.p.rowId}:${ctx.p.week}`} x={ctx.x} y={ctx.y} cells={cells} rows={sheet.rows} editable={editable}
                        title={`${row?.personName ?? ''} · ${weeks[ctx.p.week]?.label} 주${cells.length > 1 ? ` 외 — ${cells.length}칸` : ''}`}
                        onClose={() => { setCtx(null); wrap.current?.focus() }}
                        onSave={async (text) => { const ok = await props.onNotes?.(cells.map((p) => ({ ...p, text }))); if (ok) { setCtx(null); wrap.current?.focus(); onInfo(text ? `메모 저장${cells.length > 1 ? ` (${cells.length}칸)` : ''}` : '메모 지움') } }} />
      })()}
      {hoverNote && !ctx && (
        <div className="mm-hovernote" role="tooltip" style={{ left: Math.min(hoverNote.x + 4, window.innerWidth - 300), top: Math.max(8, hoverNote.y - 4) }}>
          <span className="mm-hovernote__tag">메모</span>{hoverNote.text}
        </div>
      )}
      {noteFor && <NoteDialog cells={noteFor} rows={sheet.rows} projects={projectName} weeks={weeks} onClose={() => { setNoteFor(null); wrap.current?.focus() }}
                              onSave={async (text) => { const ok = await props.onNotes?.(noteFor.map((p) => ({ ...p, text }))); if (ok) { setNoteFor(null); wrap.current?.focus() } }} />}
    </>
  )
}

/** 오른쪽 클릭 메모: 누른 자리에 작은 입력 칸. Enter 저장 · Shift+Enter 줄바꿈 · Esc 닫기 */
function MemoPop({ x, y, cells, rows, title, editable, onSave, onClose }:
  { x: number; y: number; cells: Pos[]; rows: MmRow[]; title: string; editable: boolean; onSave: (text: string) => Promise<void>; onClose: () => void }) {
  const byId = new Map(rows.map((r) => [r.id, r]))
  const texts = cells.map((p) => byId.get(p.rowId)?.notes?.[String(p.week)] ?? '')
  const distinct = [...new Set(texts.filter(Boolean))]
  const filled = texts.filter(Boolean).length
  // 모든 칸이 같은 메모일 때만 미리 채운다(일부 칸 메모를 다른 칸에 퍼뜨리지 않게)
  const original = distinct.length === 1 && filled === cells.length ? distinct[0] : ''
  const [text, setText] = useState(original)
  const [busy, setBusy] = useState(false)
  const save = async (t: string) => { if (busy) return; setBusy(true); try { await onSave(t) } finally { setBusy(false) } }
  const left = Math.max(8, Math.min(x, window.innerWidth - 300)), top = Math.max(8, Math.min(y, window.innerHeight - 230))
  return (
    <div className="mm-ctx mm-memo" role="dialog" aria-label="메모" style={{ left, top }} onContextMenu={(e) => e.preventDefault()}>
      <div className="mm-ctx__head">{title}</div>
      {editable ? <>
        <textarea className="textarea mm-memo__text" rows={3} value={text} maxLength={2000} autoFocus disabled={busy}
                  placeholder="이 주에 할 일 (Enter 저장 · Shift+Enter 줄바꿈)" aria-label="메모"
                  onFocus={(e) => e.currentTarget.setSelectionRange(e.currentTarget.value.length, e.currentTarget.value.length)}
                  onChange={(e) => setText(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); if (text.trim() && text !== original) void save(text.trim()); else if (text === original) onClose() }
                  }} />
        {filled > 0 && cells.length > 1 && !original && <p className="small mm-memo__warn">메모가 있는 {filled}칸도 이 메모로 바뀝니다.</p>}
        <div className="mm-memo__bar">
          {distinct.length > 0 && <button type="button" className="btn btn--sm btn--ghost" disabled={busy} onClick={() => save('')}>지우기</button>}
          <button type="button" className="btn btn--sm btn--primary" disabled={busy || !text.trim() || text === original} onClick={() => save(text.trim())}>저장</button>
        </div>
      </> : <>
        <p className="mm-memo__view">{distinct.length ? distinct.join(' / ') : <span className="muted">메모 없음</span>}</p>
        <p className="small muted">보기 전용 표 — 메모는 편집자만</p>
      </>}
    </div>
  )
}

/** 고른 칸(한 칸 또는 여러 칸)의 할 일 메모 */
function NoteDialog({ cells, rows, projects, weeks, onClose, onSave }:
  { cells: Pos[]; rows: MmRow[]; projects: Map<number, string>; weeks: MmWeek[]; onClose: () => void; onSave: (text: string) => Promise<void> }) {
  const byId = new Map(rows.map((r) => [r.id, r]))
  const texts = cells.map((p) => byId.get(p.rowId)?.notes?.[String(p.week)] ?? '')
  const distinct = [...new Set(texts.filter(Boolean))]
  const empties = texts.filter((t) => !t).length
  // 모든 칸이 같은 메모일 때만 미리 채운다(일부 칸 메모를 모든 칸에 퍼뜨리지 않게)
  const original = distinct.length === 1 && empties === 0 ? distinct[0] : ''
  const [text, setText] = useState(original)
  const [busy, setBusy] = useState(false)
  const [confirmDel, setConfirmDel] = useState(false)
  const people = [...new Set(cells.map((p) => { const r = byId.get(p.rowId); return r ? `${r.personName}(${projects.get(r.projectId) ?? ''})` : '' }))]
  const ws = [...new Set(cells.map((p) => p.week))].sort((a, b) => a - b)
  const target = cells.length === 1
    ? `${people[0]} · ${weeks[cells[0].week]?.label} 주`
    : `${cells.length}칸 — ${people.slice(0, 3).join(', ')}${people.length > 3 ? ` 외 ${people.length - 3}` : ''} · ${weeks[ws[0]]?.label}${ws.length > 1 ? ` ~ ${weeks[ws[ws.length - 1]]?.label}` : ''} 주`
  const existing = cells.map((p, i) => ({ p, t: texts[i] })).filter((x) => x.t)
  const save = async (t: string) => { if (busy) return; setBusy(true); try { await onSave(t) } finally { setBusy(false) } }
  return (
    <Modal title="할 일 메모" onClose={onClose} dirty={text !== original} busy={busy}
           footer={<>
             {distinct.length > 0 && <button type="button" className="btn btn--danger" style={{ marginRight: 'auto' }} disabled={busy}
                                             onClick={() => (confirmDel ? save('') : setConfirmDel(true))}>{confirmDel ? `정말 지우기 (메모 ${existing.length}칸)` : '메모 지우기'}</button>}
             <button type="button" className="btn" onClick={onClose}>취소</button>
             <button type="button" className="btn btn--primary" disabled={busy || !text.trim()} onClick={() => save(text)}>저장</button>
           </>}>
      <p className="small muted" style={{ marginTop: 0 }}>{target}</p>
      {existing.length > 0 && cells.length > 1 && (distinct.length > 1 || empties > 0) && (
        <div className="mm-note-exist">
          <p className="small"><strong>이미 메모가 있는 칸 {existing.length}개</strong> — 저장하면 고른 {cells.length}칸 모두 아래 메모로 바뀝니다.</p>
          <ul>{existing.slice(0, 6).map(({ p, t }, i) => { const r = byId.get(p.rowId); return <li key={i}><span className="muted">{r?.personName} · {weeks[p.week]?.label}</span> {t}</li> })}
            {existing.length > 6 && <li className="muted">외 {existing.length - 6}칸</li>}</ul>
        </div>
      )}
      <label htmlFor="mm-note-text" className="field__label">메모</label>
      <textarea id="mm-note-text" className="textarea" rows={5} value={text} onChange={(e) => setText(e.target.value)} maxLength={2000} autoFocus
                placeholder="이 주에 할 일 — 예) 2단계 통합 시험, 릴리스 노트 작성"
                onKeyDown={(e) => { if ((e.ctrlKey || e.metaKey) && e.key === 'Enter' && !e.nativeEvent.isComposing && text.trim()) save(text) }} />
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
  /** 이름 칸 툴팁: 이름·소속·역할 + 이 표에서 맡은 프로젝트(누가 누군지) */
  who: string
  editable: boolean
  onDown: (p: Pos, shift: boolean, ctrl: boolean) => void
  onEnter: (p: Pos) => void
  onDbl: (p: Pos, v: number | undefined) => void
  onBlur: () => void
  onFillStart: () => void
  /** 칸 오른쪽 클릭 — 메모 입력 칸 */
  onCtx: (p: Pos, v: number | undefined, x: number, y: number) => void
  onMenu?: (r: MmRow) => void
}

/** 사람 한 줄. 이 줄과 상관없는 변경(다른 줄 선택·입력)에는 다시 그리지 않는다. */
const PersonRow = memo(function PersonRow({ r, cells, weeks, thisWeek, selFrom, selTo, edgeTop, edgeBottom, fillFrom, fillTo, extra, handleWeek, focusWeek,
  edit, inputRef, pending, over, who, editable, onDown, onEnter, onDbl, onBlur, onFillStart, onCtx, onMenu }: RowProps) {
  const overMap = new Map<number, string>()
  for (const part of over.split(',').slice(1)) { const i = part.indexOf('='); overMap.set(Number(part.slice(0, i)), part.slice(i + 1)) }
  const pend = new Map<number, boolean>()
  for (const p of pending.split(',').slice(1)) pend.set(Number(p.replace('!', '')), p.endsWith('!'))
  const extras = new Set(extra.split(',').slice(1).map(Number))
  const nodes: ReactNode[] = []
  for (const w of weeks) {
    const v = cells[String(w.index)]
    const inSel = w.index >= selFrom && w.index <= selTo
    const inFill = !inSel && w.index >= fillFrom && w.index <= fillTo
    const isFocus = w.index === focusWeek
    const o = overMap.get(w.index)
    const p = pend.get(w.index)
    const note = r.notes?.[String(w.index)]
    const cls = 'mm-cell' + (inSel ? ' is-insel' : '') + (extras.has(w.index) ? ' is-extra' : '') + (inFill ? ' is-fillprev' : '') + (isFocus ? ' is-sel' : '')
      + (w.index === thisWeek ? ' is-now' : '') + (p !== undefined ? (p ? ' is-failed' : ' is-pending') : '') + (o ? ' is-overp' : '') + (note ? ' has-note' : '')
      + (v !== undefined && Math.abs(v) >= 100 ? ' is-wide' : '')
    const edges = inSel ? `${w.index === selFrom ? ' l' : ''}${w.index === selTo ? ' r' : ''}${edgeTop ? ' t' : ''}${edgeBottom ? ' b' : ''}` : ''
    const tip = [v !== undefined && Math.abs(v) >= 100 ? `값 ${fmtVal(v)}` : '', p ? '저장 안 됨 — 표 위 [다시 시도]' : '', o ? `이 사람 이 주 합계 ${o} — 1.0 초과` : ''].filter(Boolean).join('\n')
    nodes.push(
      <td key={w.index} data-k={`${r.id}:${w.index}`} data-note={note || undefined} className={cls} style={{ backgroundColor: cellBg(v) }} title={tip || undefined}
          aria-selected={inSel || extras.has(w.index) || undefined}
          onMouseDown={(e) => { if (isFocus && edit) return; e.preventDefault(); onDown({ rowId: r.id, week: w.index }, e.shiftKey, e.ctrlKey || e.metaKey) }}
          onMouseEnter={() => onEnter({ rowId: r.id, week: w.index })}
          onDoubleClick={() => onDbl({ rowId: r.id, week: w.index }, v)}
          onContextMenu={(e) => { if (isFocus && edit) return; e.preventDefault(); onCtx({ rowId: r.id, week: w.index }, v, e.clientX, e.clientY) }}>
        {edges && <span className={`mm-edge${edges}`} aria-hidden />}
        {isFocus && edit
          ? <input ref={(el) => { inputRef.current = el; if (el && document.activeElement !== el) { el.focus(); el.setSelectionRange(el.value.length, el.value.length) } }}
                   className="mm-input" defaultValue={edit.init} onBlur={onBlur} inputMode="decimal" enterKeyHint="next" aria-label={`${r.personName} ${w.label} 주 값`} />
          : fmtVal(v)}
        {handleWeek === w.index && (
          <span className="mm-handle" title="끌어서 채우기 — 고른 값을 되풀이해 채웁니다(Esc 취소)"
                onMouseDown={(e) => { e.preventDefault(); e.stopPropagation(); onFillStart() }} />
        )}
      </td>,
    )
  }
  return (
    <tr className="mm-person">
      <th scope="row" className="mm-sticky mm-c0 mm-name" title={who}>
        <span className="mm-indent" />
        {onMenu && editable
          ? <button type="button" className="mm-namebtn" onClick={() => onMenu(r)} title="이름·소속·역할 고치기 · 다른 프로젝트로 옮기기 · 삭제">{r.personName}</button>
          : r.personName}
        {r.userId == null && <span className="mm-ext" title="계정 없는 인원(직접 입력)">외부</span>}
        {onMenu && editable && <button type="button" className="mm-mini" onClick={() => onMenu(r)} aria-label={`${r.personName} 행 편집`}>⋯</button>}
      </th>
      <td className="mm-sticky mm-c1 mm-dim">{r.department ?? ''}</td>
      <td className="mm-sticky mm-c2 mm-dim mm-rowmm" title={`${r.role ? `${r.role} · ` : ''}이 표 기간 ${totalMm(cells, weeks)} MM`}>
        <span className="mm-rowmm__role">{r.role ?? ''}</span>{totalMm(cells, weeks) > 0 && <b>{totalMm(cells, weeks)}</b>}
      </td>
      {nodes}
    </tr>
  )
})
