import { http, parseContentDispositionFilename, saveBlob } from './api'

// ---------- 공수(Man-month) 표 (com.workblog.manmonth) ----------
export interface MmUserRef { id: number; username: string; displayName: string; department: string | null }
export interface MmSheetSummary { id: number; title: string; description: string | null; startDate: string; weeks: number; featured: boolean; ownerName: string | null; updatedAt: string; canEdit: boolean }
export interface MmProject { id: number; name: string; sortOrder: number }
export interface MmRow { id: number; projectId: number; userId: number | null; personName: string; department: string | null; role: string | null; sortOrder: number; cells: Record<string, number> }
export interface MmChange { actorName: string | null; summary: string; createdAt: string }
export interface MmSheet {
  id: number; title: string; description: string | null; startDate: string; weeks: number; featured: boolean
  ownerId: number | null; ownerName: string | null; updatedAt: string; canEdit: boolean; canManage: boolean
  editors: MmUserRef[]; projects: MmProject[]; rows: MmRow[]; changes: MmChange[]
}
export interface MmCellChange { rowId: number; week: number; value: number | null }
export interface MmImportResult { applied: boolean; projectsCreated: number; rowsCreated: number; cellsSet: number; cellsCleared: number; warnings: string[] }
export interface MmRowRequest { projectId?: number; userId?: number | null; personName?: string; department?: string; role?: string }

const base = '/mm/sheets'
export const mmApi = {
  list: () => http.get<MmSheetSummary[]>(base).then((r) => r.data),
  get: (id: number) => http.get<MmSheet>(`${base}/${id}`).then((r) => r.data),
  create: (body: { title: string; description?: string; startDate: string; weeks: number }) => http.post<MmSheet>(base, body).then((r) => r.data),
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

/** 붙여넣기·입력 문자열 → 값. 빈 칸 = null, 50% → 0.5, 1 보다 크고 100 이하인 수(50)도 % 로 본다 */
export function parseVal(s: string): number | null | 'bad' {
  const t = s.trim().replace(',', '.')
  if (!t) return null
  const pct = t.endsWith('%')
  const n = Number(pct ? t.slice(0, -1) : t)
  if (!Number.isFinite(n)) return 'bad'
  const v = pct || (n > 1 && n <= 100) ? n / 100 : n
  if (v < 0 || v > 1) return 'bad'
  return v === 0 ? null : Math.round(v * 100) / 100
}
