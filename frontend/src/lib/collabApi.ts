import { http, type PageResponse } from './api'

// ---------- 알림 (com.workblog.notification) ----------
export type NotificationType = 'COMMENT' | 'REPLY' | 'MENTION'

export interface NotificationItem {
  id: number
  type: NotificationType
  actor: { id: number; username: string; displayName: string } | null
  postId: number
  /** available=false 면 null(지금은 볼 수 없는 글) */
  postTitle: string | null
  /** 댓글·답글·댓글 멘션이면 댓글 id, 글 본문 멘션이면 null */
  commentId: number | null
  excerpt: string | null
  read: boolean
  available: boolean
  createdAt: string
}

export const notificationsApi = {
  list: (params: { unreadOnly?: boolean; page?: number; size?: number } = {}) =>
    http.get<PageResponse<NotificationItem>>('/notifications', { params }).then((r) => r.data),
  unreadCount: () => http.get<{ count: number }>('/notifications/unread-count').then((r) => r.data.count),
  read: (id: number) => http.post<NotificationItem>(`/notifications/${id}/read`).then((r) => r.data),
  readAll: () => http.post<{ count: number }>('/notifications/read-all').then((r) => r.data),
}

export function notificationText(n: NotificationItem): string {
  const who = n.actor?.displayName ?? '누군가'
  if (n.type === 'REPLY') return `${who}님이 내 댓글에 답글을 남겼습니다.`
  if (n.type === 'MENTION') return n.commentId ? `${who}님이 댓글에서 나를 언급했습니다.` : `${who}님이 글에서 나를 언급했습니다.`
  return `${who}님이 내 글에 댓글을 남겼습니다.`
}

/** 알림을 눌렀을 때 갈 주소: 댓글이면 그 댓글 위치(#comment-id) */
export function notificationHref(n: NotificationItem): string {
  return `/posts/${n.postId}${n.commentId ? `#comment-${n.commentId}` : ''}`
}

// ---------- 열람 감사 로그 (com.workblog.audit) ----------
export type ViewVia = 'WEB' | 'API_TOKEN'
export interface AuditUserRef { id: number; username: string; displayName: string; department: string | null }
export interface ViewerRow { user: AuditUserRef; count: number; lastViewedAt: string }
export interface ViewersResponse { postId: number; totalViewers: number; viewers: ViewerRow[] }
export interface ViewLogEntry { id: number; postId: number; postTitle: string; postDeleted: boolean; user: AuditUserRef; viewedAt: string; via: ViewVia }

export const auditApi = {
  /** 작성자·관리자: 이 글의 최근 열람자 50명(작성자 제외) */
  viewers: (postId: number) => http.get<ViewersResponse>(`/posts/${postId}/viewers`).then((r) => r.data),
  /** 관리자: 열람 기록 검색. from/to 는 yyyy-MM-dd(둘 다 포함) */
  views: (params: { postId?: number; userId?: number; from?: string; to?: string; page?: number; size?: number }) =>
    http.get<PageResponse<ViewLogEntry>>('/admin/audit/views', { params }).then((r) => r.data),
}

export const viaLabel: Record<ViewVia, string> = { WEB: '웹', API_TOKEN: 'API 토큰' }

// ---------- 멘션 입력 도우미 ----------
/** 커서 바로 앞의 '@부분' 을 찾는다. 이메일처럼 앞에 글자가 붙은 @ 는 무시. */
export function mentionQueryAt(text: string, caret: number): { start: number; query: string } | null {
  const before = text.slice(0, caret)
  const m = /(^|[^A-Za-z0-9._@-])@([A-Za-z0-9._-]{0,50})$/.exec(before)
  if (!m) return null
  return { start: caret - m[2].length - 1, query: m[2] }
}
