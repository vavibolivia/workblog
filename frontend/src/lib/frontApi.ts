import { http } from './api'

// ---------- 대문 (com.workblog.front) — 홈 맨 위 탭: 글 · 공수 표 ----------
export type FrontKind = 'POST' | 'MM_SHEET'
export interface FrontItem { kind: FrontKind; refId: number; title: string }

export const frontApi = {
  list: () => http.get<FrontItem[]>('/front').then((r) => r.data),
  replace: (items: { kind: FrontKind; refId: number }[]) => http.put<FrontItem[]>('/front', { items }).then((r) => r.data),
  /** 맨 뒤에 올리기 (이미 있으면 그대로) */
  add: async (kind: FrontKind, refId: number) => {
    const cur = await frontApi.list()
    if (cur.some((i) => i.kind === kind && i.refId === refId)) return cur
    return frontApi.replace([...cur, { kind, refId }])
  },
  remove: async (kind: FrontKind, refId: number) => {
    const cur = await frontApi.list()
    return frontApi.replace(cur.filter((i) => !(i.kind === kind && i.refId === refId)))
  },
}

export const FRONT_CHANGED = 'workblog:front-changed'
/** 대문이 바뀌었음을 같은 화면의 다른 부분(홈 대문 등)에 알림 */
export function notifyFrontChanged() {
  window.dispatchEvent(new Event(FRONT_CHANGED))
}
