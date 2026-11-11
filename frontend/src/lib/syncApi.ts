import { http } from './api'

// ---------- 업데이트 (com.workblog.datamove.SyncController, 관리자 전용) ----------
export interface SyncSettings { intervalMinutes: number; program: boolean; data: boolean }

export interface SyncStatus {
  state: 'running' | 'done'
  trigger: 'timer' | 'button' | 'manual'
  at: string
  intervalMinutes: number
  program: { current: string; latest: string; result: string }
  data: { repo: string; tag: string; result: string }
  log: string[]
}

export interface SyncInfo {
  installed: boolean
  appVersion: string
  settings: SyncSettings
  requested: boolean
  status: SyncStatus | null
}

export const syncApi = {
  get: () => http.get<SyncInfo>('/admin/sync').then((r) => r.data),
  save: (s: SyncSettings) => http.put<SyncSettings>('/admin/sync/settings', s).then((r) => r.data),
  run: () => http.post<SyncInfo>('/admin/sync/run').then((r) => r.data),
}
