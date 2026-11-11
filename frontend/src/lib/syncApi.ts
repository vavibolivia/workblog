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
  github?: { programRepo: string; dataRepo: string; tokenSet: boolean }
  log: string[]
}

export interface SyncInfo {
  installed: boolean
  appVersion: string
  settings: SyncSettings
  requested: boolean
  status: SyncStatus | null
  /** 업데이트 장치가 알려 준 지금 값(토큰 값은 오지 않음). pending = 입력을 아직 장치가 가져가지 않음 */
  github: { programRepo: string; dataRepo: string; tokenSet: boolean; pending: boolean }
}

export interface GithubInput { programRepo: string; dataRepo: string; token: string }

export const syncApi = {
  get: () => http.get<SyncInfo>('/admin/sync').then((r) => r.data),
  save: (s: SyncSettings) => http.put<SyncSettings>('/admin/sync/settings', s).then((r) => r.data),
  run: () => http.post<SyncInfo>('/admin/sync/run').then((r) => r.data),
  github: (g: GithubInput) => http.put<SyncInfo>('/admin/sync/github', g).then((r) => r.data),
}
