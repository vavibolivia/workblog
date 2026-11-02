import { http } from './api'

// ---------- 데이터 이전 (com.workblog.datamove, 관리자 전용) ----------
export type ImportAction = 'CREATE' | 'UPDATE' | 'SKIP' | 'CONFLICT' | 'FAIL'

export interface ImportCounts { created: number; updated: number; skipped: number; conflict: number; failed: number }

export interface ImportItem {
  entity: string
  action: ImportAction
  sourceId: string | null
  targetId: number | null
  label: string | null
  reason: string | null
}

export interface ImportReport {
  dumpName: string
  sourceInstanceId: string
  kind: 'FULL' | 'UPDATE'
  exportedAt: string
  since: string | null
  applied: boolean
  status: 'OK' | 'FAILED'
  completedStages: string[]
  failedStage: string | null
  error: string | null
  lastCommitted: string | null
  counts: Record<string, ImportCounts>
  items: ImportItem[]
  itemsOmitted: number
}

export interface TransferRow {
  id: number
  direction: 'EXPORT' | 'IMPORT'
  dumpKind: 'FULL' | 'UPDATE'
  action: 'EXPORT' | 'PREVIEW' | 'APPLY'
  dumpName: string
  sourceInstance: string
  since: string | null
  exportedAt: string | null
  baseline: boolean
  status: 'RUNNING' | 'OK' | 'FAILED'
  actorName: string
  startedAt: string
  finishedAt: string | null
  summary: string | null
}

export interface DataMoveInfo {
  instanceId: string
  lastExportAt: string | null
  importMaxMb: number
  history: TransferRow[]
}

export interface ManifestSummary {
  dumpName: string
  sourceInstanceId: string
  kind: 'FULL' | 'UPDATE'
  appVersion: string
  exportedAt: string
  since: string | null
  counts: Record<string, number>
  totalBytes: number
}

export interface UploadResult { uploadId: string; manifest: ManifestSummary; preview: ImportReport }

export const dataMoveApi = {
  info: () => http.get<DataMoveInfo>('/admin/data/info').then((r) => r.data),
  transfer: (id: number) => http.get<{ transfer: TransferRow; report: ImportReport | { counts: Record<string, number> } | null }>(`/admin/data/transfers/${id}`).then((r) => r.data),
  ticket: (body: { full: boolean; since?: string; markBaseline: boolean }) =>
    http.post<{ ticket: string; expiresInSeconds: number }>('/admin/data/export/ticket', body).then((r) => r.data),
  downloadUrl: (ticket: string) => `/api/admin/data/export/download?ticket=${encodeURIComponent(ticket)}`,
  /** zip 을 본문 그대로 올린다(큰 파일: 시간 제한 없음, 진행률 표시). */
  upload: (file: File, onProgress?: (pct: number) => void) =>
    http.post<UploadResult>('/admin/data/import', file, {
      timeout: 0,
      headers: { 'Content-Type': 'application/zip' },
      onUploadProgress: (e) => { if (onProgress && e.total) onProgress(Math.round((e.loaded / e.total) * 100)) },
    }).then((r) => r.data),
  apply: (uploadId: string) => http.post<ImportReport>(`/admin/data/import/${uploadId}/apply`, null, { timeout: 0 }).then((r) => r.data),
  discard: (uploadId: string) => http.delete(`/admin/data/import/${uploadId}`),
}

export const entityLabel: Record<string, string> = {
  users: '사용자', categories: '카테고리', templates: '템플릿', posts: '글', revisions: '글 이력', attachments: '첨부',
  comments: '댓글', likes: '공감', notifications: '알림', postViews: '열람 기록', apiTokens: 'API 토큰', files: '첨부 파일',
}

export const actionLabel: Record<ImportAction, string> = {
  CREATE: '추가', UPDATE: '갱신', SKIP: '건너뜀', CONFLICT: '충돌', FAIL: '실패',
}
