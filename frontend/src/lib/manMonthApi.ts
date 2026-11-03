import { http, parseContentDispositionFilename, saveBlob } from './api'

// ---------- 공수(Man-month) 표 (com.workblog.manmonth) ----------
export interface MmUserRef { id: number; username: string; displayName: string; department: string | null }
export interface MmSheetSummary { id: number; title: string; description: string | null; startDate: string; weeks: number; featured: boolean; ownerName: string | null; updatedAt: string; canEdit: boolean }
export interface MmProject { id: number; name: string; sortOrder: number }
export interface MmRow { id: number; projectId: number; userId: number | null; personName: string; department: string | null; role: string | null; sortOrder: number; cells: Record<string, number>; notes: Record<string, string> }
export interface MmChange { actorName: string | null; summary: string; createdAt: string }
export interface MmSheet {
  id: number; title: string; description: string | null; startDate: string; weeks: number; featured: boolean
  ownerId: number | null; ownerName: string | null; updatedAt: string; canEdit: boolean; canManage: boolean
  editors: MmUserRef[]; projects: MmProject[]; rows: MmRow[]; changes: MmChange[]; events: MmEvent[]
}
export interface MmCellChange { rowId: number; week: number; value: number | null }
export interface MmNoteChange { rowId: number; week: number; text: string }
export type MmEventKind = 'MILESTONE' | 'MEETING' | 'RELEASE' | 'REVIEW' | 'HOLIDAY' | 'LEAVE' | 'ETC'
export interface MmEvent { id: number; startDate: string; endDate: string | null; kind: MmEventKind; title: string; related: string | null; note: string | null; createdBy: string | null; updatedAt: string; /** 이 사람이 고치거나 삭제할 수 있는지(만든 사람·관리자) */ canEdit: boolean; /** 고른 색(#rrggbb). 없으면 기본 색 */ color: string | null }
export interface MmEventRequest { startDate: string; endDate?: string | null; kind: MmEventKind; title: string; related?: string; note?: string; color?: string | null }
/** 이벤트 종류: 이름 · 색 · 모양(색약·흑백에서도 갈리게 색과 함께 씀) */
/** 이벤트 종류는 쓰지 않는다(사용자 지시) — 모든 이벤트가 같은 색·같은 표시. kind 값은 DB 에 남아 있어도 화면에서 구분하지 않음 */
export const EVENT_COLOR = '#5b6577'
/** 이벤트 색 고르기 목록 */
export const EVENT_COLORS = ['#5b6577', '#2f4a7a', '#3e6e6a', '#5d7a48', '#9a7434', '#9c5442', '#7d4f6b']
/** 이 이벤트의 색(고른 색, 없으면 기본) */
export const evColor = (e: { color?: string | null }) => e.color || EVENT_COLOR
const ONE = { label: '이벤트', color: EVENT_COLOR, sym: '●' }
export const EVENT_KINDS: Record<MmEventKind, { label: string; color: string; sym: string }> = {
  RELEASE: ONE, MILESTONE: ONE, REVIEW: ONE, MEETING: ONE, HOLIDAY: ONE, LEAVE: ONE, ETC: ONE,
}
export interface MmImportResult { applied: boolean; projectsCreated: number; rowsCreated: number; cellsSet: number; cellsCleared: number; warnings: string[] }
export interface MmRowRequest { projectId?: number; userId?: number | null; personName?: string; department?: string; role?: string }

const base = '/mm/sheets'
export const mmApi = {
  list: () => http.get<MmSheetSummary[]>(base).then((r) => r.data),
  get: (id: number) => http.get<MmSheet>(`${base}/${id}`).then((r) => r.data),
  create: (body: { title: string; description?: string; startDate: string; weeks: number }) => http.post<MmSheet>(base, body).then((r) => r.data),
  copy: (id: number, body: { title: string; startDate: string; weeks: number; withValues: boolean }) => http.post<MmSheet>(`${base}/${id}/copy`, body).then((r) => r.data),
  update: (id: number, body: { title?: string; description?: string; startDate?: string; weeks?: number }) =>
    http.patch<MmSheet>(`${base}/${id}`, body).then((r) => r.data),
  remove: (id: number) => http.delete(`${base}/${id}`),
  setEditors: (id: number, userIds: number[]) => http.put<MmSheet>(`${base}/${id}/editors`, { userIds }).then((r) => r.data),
  addProject: (id: number, name: string) => http.post<MmSheet>(`${base}/${id}/projects`, { name }).then((r) => r.data),
  renameProject: (id: number, projectId: number, name: string) => http.put<MmSheet>(`${base}/${id}/projects/${projectId}`, { name }).then((r) => r.data),
  removeProject: (id: number, projectId: number) => http.delete<MmSheet>(`${base}/${id}/projects/${projectId}`).then((r) => r.data),
  addRow: (id: number, body: MmRowRequest) => http.post<MmSheet>(`${base}/${id}/rows`, body).then((r) => r.data),
  updateRow: (id: number, rowId: number, body: MmRowRequest) => http.put<MmSheet>(`${base}/${id}/rows/${rowId}`, body).then((r) => r.data),
  removeRow: (id: number, rowId: number) => http.delete<MmSheet>(`${base}/${id}/rows/${rowId}`).then((r) => r.data),
  setCells: (id: number, changes: MmCellChange[]) => http.put<MmSheet>(`${base}/${id}/cells`, { changes }).then((r) => r.data),
  setNotes: (id: number, changes: MmNoteChange[]) => http.put<MmSheet>(`${base}/${id}/notes`, { changes }).then((r) => r.data),
  downloadExcel: async (id: number, template: boolean) => {
    const r = await http.get<Blob>(`${base}/${id}/excel`, { params: { template }, responseType: 'blob' })
    saveBlob(r.data, parseContentDispositionFilename(r.headers['content-disposition']) ?? (template ? '공수양식.xlsx' : '공수.xlsx'))
  },
  /** dryRun = 미리보기(바뀌는 것만 계산), keepBlank = 파일의 빈 칸은 기존 값 유지 */
  uploadExcel: (id: number, file: File, opts: { dryRun: boolean; keepBlank: boolean }) => {
    const fd = new FormData()
    fd.append('file', file)
    return http.post<MmImportResult>(`${base}/${id}/excel`, fd, { params: opts }).then((r) => r.data)
  },
}

// ---------- 계산 (백엔드 ManMonthExcel 과 같은 규칙) ----------

/** 고른 날의 월요일 (yyyy-mm-dd). 표는 늘 월요일부터 주 단위 */
export function mondayOf(date: string): string {
  if (!date) return ''
  const [y, m, d] = date.split('-').map(Number)
  const s = new Date(y, m - 1, d)
  s.setDate(s.getDate() - ((s.getDay() + 6) % 7))
  return ymd(s)
}
const ymd = (x: Date) => `${x.getFullYear()}-${String(x.getMonth() + 1).padStart(2, '0')}-${String(x.getDate()).padStart(2, '0')}`

/** 기간 표시: 월요일 ~ 마지막 주 일요일 · n주 */
export function periodText(start: string, weeks: number): string {
  if (!start) return '—'
  const s = mondayOf(start)
  const [y, m, d] = s.split('-').map(Number)
  const e = new Date(y, m - 1, d + weeks * 7 - 1)
  return `${s} (월) ~ ${ymd(e)} (일) · ${weeks}주`
}

export interface MmWeek { index: number; monday: Date; label: string; monthKey: string }

function parseDate(s: string): Date {
  const [y, m, d] = s.split('-').map(Number)
  return new Date(y, m - 1, d)
}

export function weeksOf(startDate: string, weeks: number): MmWeek[] {
  const start = parseDate(startDate)
  const out: MmWeek[] = []
  for (let i = 0; i < weeks; i++) {
    const d = new Date(start.getFullYear(), start.getMonth(), start.getDate() + i * 7)
    out.push({ index: i, monday: d, label: `${d.getMonth() + 1}/${d.getDate()}`, monthKey: `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}` })
  }
  return out
}

/** 같은 달끼리 묶은 머리줄 [월 키, 주 수] */
export function monthSpans(weeks: MmWeek[]): [string, number][] {
  const out: [string, number][] = []
  for (const w of weeks) {
    const last = out[out.length - 1]
    if (last && last[0] === w.monthKey) last[1]++
    else out.push([w.monthKey, 1])
  }
  return out
}

function mondaysInMonth(key: string): number {
  const [y, m] = key.split('-').map(Number)
  let n = 0
  for (let d = 1; d <= new Date(y, m, 0).getDate(); d++) if (new Date(y, m - 1, d).getDay() === 1) n++
  return n
}

/** 합계 MM = 달마다 (그 달 주 투입률 합 ÷ 그 달 월요일 수) 의 합. 1.0 으로 한 달 내내 = 1 MM */
export function totalMm(cells: Record<string, number>, weeks: MmWeek[]): number {
  const sum: Record<string, number> = {}
  for (const w of weeks) {
    const v = cells[String(w.index)]
    if (v) sum[w.monthKey] = (sum[w.monthKey] ?? 0) + v
  }
  return Math.round(Object.entries(sum).reduce((t, [k, v]) => t + v / mondaysInMonth(k), 0) * 100) / 100
}

/** 사람 구분 키: 계정이 있으면 계정, 없으면 이름 */
export function personKey(r: MmRow): string {
  return r.userId != null ? `u${r.userId}` : `n${r.personName.trim().toLowerCase()}`
}

export function fmtVal(v: number | undefined): string {
  if (!v) return ''
  return String(Math.round(v * 100) / 100)
}

/** 붙여넣기·입력 문자열 → 값. 아무 숫자(소수 둘째 자리), 빈 칸·0 = 비움, 50% → 0.5. 천 단위 쉼표(1,234)도 됨 */
export function parseVal(s: string): number | null | 'bad' {
  let t = s.trim().replace(/\s/g, '')
  if (!t) return null
  if (/^-?\d{1,3}(,\d{3})+(\.\d+)?%?$/.test(t)) t = t.replace(/,/g, '')
  else t = t.replace(',', '.')
  const pct = t.endsWith('%')
  const n = Number(pct ? t.slice(0, -1) : t)
  if (!Number.isFinite(n)) return 'bad'
  const v = Math.round((pct ? n / 100 : n) * 100) / 100
  if (Math.abs(v) >= 1e10) return 'bad'
  return v === 0 ? null : v
}

// ---------- 이벤트(공용 목록) ----------
export const mmEventApi = {
  list: (from?: string, to?: string) => http.get<{ events: MmEvent[]; canEdit: boolean }>('/mm/events', { params: { from, to } }).then((r) => r.data),
  create: (body: MmEventRequest) => http.post<MmEvent>('/mm/events', body).then((r) => r.data),
  update: (id: number, body: MmEventRequest) => http.put<MmEvent>(`/mm/events/${id}`, body).then((r) => r.data),
  remove: (id: number) => http.delete(`/mm/events/${id}`),
}
export const MM_EVENTS_CHANGED = 'workblog:mm-events-changed'
export const notifyEventsChanged = () => window.dispatchEvent(new Event(MM_EVENTS_CHANGED))

/** 날짜(yyyy-mm-dd) 사이 날 수 */
export function daysBetween(a: string, b: string): number {
  const p = (x: string) => { const [y, m, d] = x.split('-').map(Number); return Date.UTC(y, m - 1, d) }
  return Math.round((p(b) - p(a)) / 864e5)
}
/** 이벤트가 걸치는 주 번호들(표 기간 안만) */
export function eventWeeks(e: MmEvent, startDate: string, weeks: number): number[] {
  const a = Math.floor(daysBetween(startDate, e.startDate) / 7)
  const b = Math.floor(daysBetween(startDate, e.endDate ?? e.startDate) / 7)
  const out: number[] = []
  for (let w = Math.max(0, a); w <= Math.min(weeks - 1, b); w++) out.push(w)
  return out
}
/** 짧은 날짜: 11/20(금) */
export function shortDate(d: string): string {
  const [y, m, dd] = d.split('-').map(Number)
  return `${m}/${dd}(${'일월화수목금토'[new Date(y, m - 1, dd).getDay()]})`
}
export const eventPeriod = (e: MmEvent) => (e.endDate ? `${shortDate(e.startDate)} ~ ${shortDate(e.endDate)}` : shortDate(e.startDate))
