import axios, { AxiosError } from 'axios'

// ---------- types (mirror backend DTOs) ----------
export type Role = 'ADMIN' | 'USER'
export type PostStatus = 'DRAFT' | 'PUBLISHED'
/** 목록 정렬: 최신순 · 인기순(공감→조회) · 조회순 · 오래된순 */
export type PostSort = 'LATEST' | 'POPULAR' | 'VIEWS' | 'COMMENTS' | 'OLDEST'
export type ChangeType = 'CREATE' | 'UPDATE' | 'RESTORE' | 'DELETE' | 'UNDELETE'
export type ContentFormat = 'HTML' | 'MARKDOWN'
export type AttachmentKind = 'IMAGE' | 'EXCEL' | 'FILE'

export interface AttachmentResponse {
  id: string
  name: string
  url: string
  contentType: string
  size: number
  kind: AttachmentKind
  createdAt: string
}

export interface UserResponse {
  id: number
  username: string
  displayName: string
  email: string | null
  department: string | null
  role: Role
  active: boolean
  createdAt: string
}

export interface AuthorSummary {
  id: number
  username: string
  displayName: string
  department: string | null
}

export interface CategoryRef {
  id: number
  name: string
  path: string[]
  pathIds: number[]
}

export interface CategoryNode {
  id: number
  name: string
  parentId: number | null
  depth: number
  sortOrder: number
  postCount: number
  totalCount: number
  children: CategoryNode[]
}

export const CATEGORY_MAX_DEPTH = 5

/** 트리를 깊이 순서대로 펼친 목록(선택 상자용). */
export function flattenCategories(nodes: CategoryNode[], out: CategoryNode[] = []): CategoryNode[] {
  for (const n of nodes) { out.push(n); flattenCategories(n.children, out) }
  return out
}

export function findCategory(nodes: CategoryNode[], id: number): CategoryNode | undefined {
  for (const n of nodes) { if (n.id === id) return n; const f = findCategory(n.children, id); if (f) return f }
  return undefined
}

/** 공개 범위: PRIVATE(비공개)는 작성자와 관리자만 본다. */
export type Visibility = 'PUBLIC' | 'PRIVATE'

export interface PostSummary {
  id: number
  title: string
  summary: string | null
  snippet: string
  /** 본문 첫 이미지(앨범형 썸네일). 없으면 null */
  thumbnailUrl: string | null
  category: CategoryRef
  tags: string[]
  status: PostStatus
  visibility: Visibility
  /** 공지(상단 고정) */
  pinned: boolean
  author: AuthorSummary
  version: number
  viewCount: number
  likeCount: number
  likedByMe: boolean
  /** 삭제되지 않은 댓글 수 */
  commentCount: number
  createdAt: string
  updatedAt: string
  publishedAt: string | null
}

export interface LikeResponse { liked: boolean; count: number }

export interface PostDetail extends Omit<PostSummary, 'snippet' | 'thumbnailUrl'> {
  content: string
  contentFormat: ContentFormat
  deletedAt: string | null
  editable: boolean
  attachments: AttachmentResponse[]
}

export interface PostRequest {
  title: string
  content: string
  contentFormat: ContentFormat
  attachmentIds?: string[]
  summary?: string
  categoryId?: number | null
  tags: string[]
  status: PostStatus
  /** 기본 PUBLIC */
  visibility?: Visibility
  /** 관리자만 반영된다 */
  pinned?: boolean
  changeNote?: string
}

export interface RevisionSummary {
  id: number
  postId: number
  version: number
  title: string
  status: PostStatus
  changeType: ChangeType
  changeNote: string | null
  editedBy: AuthorSummary
  createdAt: string
}

export interface RevisionDetail extends RevisionSummary {
  content: string
  contentFormat: ContentFormat
  summary: string | null
  category: string
  tags: string[]
}

export interface ActivityEntry {
  revisionId: number
  postId: number
  postTitle: string
  version: number
  changeType: ChangeType
  changeNote: string | null
  editedBy: AuthorSummary
  createdAt: string
}

export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface TagCount { name: string; count: number }

export interface ApiErrorBody { status: number; error: string; message: string }

/** 로그인 전에도 볼 수 있는 가입 설정 */
export interface AuthOptions { signupEnabled: boolean; signupRequiresApproval: boolean }

export interface SignupRequest { username: string; password: string; displayName: string; email?: string; department?: string }

/** 가입 결과: 즉시 승인(201)이면 token 이 채워지고, 승인 대기(202)면 token 은 null */
export interface SignupResponse { approved: boolean; message: string; token: string | null; expiresIn: number | null; user: UserResponse }

export type ExportFormat = 'html' | 'md' | 'docx'

/** 외부 프로그램(Claude Code 등)용 개인 API 토큰. 원문은 발급 때 한 번만 온다. */
export interface TokenInfo {
  id: number
  name: string
  prefix: string
  createdAt: string
  expiresAt: string | null
  lastUsedAt: string | null
  revokedAt: string | null
  active: boolean
}

export interface GuideDoc { id: string; title: string }

// ---------- client ----------
const TOKEN_KEY = 'workblog.token'

export const tokenStore = {
  get: () => { try { return localStorage.getItem(TOKEN_KEY) } catch { return null } },
  set: (t: string) => { try { localStorage.setItem(TOKEN_KEY, t) } catch { /* ignore */ } },
  clear: () => { try { localStorage.removeItem(TOKEN_KEY) } catch { /* ignore */ } },
}

export const http = axios.create({ baseURL: '/api', timeout: 120_000 })

http.interceptors.request.use((cfg) => {
  const t = tokenStore.get()
  if (t) cfg.headers.Authorization = `Bearer ${t}`
  return cfg
})

let onUnauthorized: (() => void) | null = null
export function setUnauthorizedHandler(fn: () => void) { onUnauthorized = fn }

http.interceptors.response.use(
  (r) => r,
  (err: AxiosError<ApiErrorBody>) => {
    if (err.response?.status === 401 && !err.config?.url?.endsWith('/auth/login')) {
      tokenStore.clear()
      onUnauthorized?.()
    }
    return Promise.reject(err)
  },
)

export function errorMessage(e: unknown, fallback = '요청을 처리하지 못했습니다.'): string {
  if (axios.isAxiosError<ApiErrorBody>(e)) {
    if (e.response?.data?.message) return e.response.data.message
    if (e.code === 'ECONNABORTED') return '응답 시간이 초과되었습니다.'
    if (!e.response) return '서버에 연결할 수 없습니다.'
  }
  return fallback
}

// ---------- endpoints ----------
export const api = {
  auth: {
    login: (username: string, password: string) =>
      http.post<{ token: string; expiresIn: number; user: UserResponse }>('/auth/login', { username, password }).then((r) => r.data),
    me: () => http.get<UserResponse>('/auth/me').then((r) => r.data),
    options: () => http.get<AuthOptions>('/auth/options').then((r) => r.data),
    signup: (body: SignupRequest) => http.post<SignupResponse>('/auth/signup', body).then((r) => r.data),
  },
  users: {
    list: () => http.get<UserResponse[]>('/users').then((r) => r.data),
    create: (body: { username: string; password: string; displayName: string; email?: string; department?: string; role: Role }) =>
      http.post<UserResponse>('/users', body).then((r) => r.data),
    update: (id: number, body: { displayName: string; email?: string; department?: string; role?: Role; active?: boolean; newPassword?: string }) =>
      http.put<UserResponse>(`/users/${id}`, body).then((r) => r.data),
    deactivate: (id: number) => http.delete(`/users/${id}`),
    /** 영구 삭제(관리자). 글이 있는 계정은 400 — 메시지를 그대로 보여 준다. */
    remove: (id: number) => http.delete(`/users/${id}`, { params: { purge: true } }),
    updateProfile: (body: { displayName: string; email?: string; department?: string }) =>
      http.put<UserResponse>('/users/me', body).then((r) => r.data),
    changePassword: (currentPassword: string, newPassword: string) =>
      http.put('/users/me/password', { currentPassword, newPassword }),
  },
  posts: {
    list: (params: { q?: string; categoryId?: number; tag?: string; authorId?: number; status?: PostStatus; sort?: PostSort; page?: number; size?: number }) =>
      http.get<PageResponse<PostSummary>>('/posts', { params }).then((r) => r.data),
    get: (id: number, view = true) => http.get<PostDetail>(`/posts/${id}`, { params: { view } }).then((r) => r.data),
    create: (body: PostRequest) => http.post<PostDetail>('/posts', body).then((r) => r.data),
    update: (id: number, body: PostRequest) => http.put<PostDetail>(`/posts/${id}`, body).then((r) => r.data),
    /** 기본은 휴지통으로(복구 가능). purge=true 면 관리자가 이력·댓글·첨부까지 영구 삭제. */
    remove: (id: number, purge = false) => http.delete(`/posts/${id}`, { params: purge ? { purge: true } : undefined }),
    /** 글을 파일로 내려받기. 파일 이름은 Content-Disposition 에서, 없으면 호출 쪽이 정한다. */
    export: (id: number, format: ExportFormat, attachments: boolean): Promise<{ blob: Blob; filename: string | null }> =>
      http.get<Blob>(`/posts/${id}/export`, { params: { format, attachments }, responseType: 'blob', timeout: 300_000 })
        .then((r) => ({ blob: r.data, filename: parseContentDispositionFilename(r.headers['content-disposition']) })),
    undelete: (id: number) => http.post<PostDetail>(`/posts/${id}/undelete`).then((r) => r.data),
    deleted: () => http.get<PostSummary[]>('/posts/deleted').then((r) => r.data),
    revisions: (id: number) => http.get<RevisionSummary[]>(`/posts/${id}/revisions`).then((r) => r.data),
    revision: (id: number, version: number) => http.get<RevisionDetail>(`/posts/${id}/revisions/${version}`).then((r) => r.data),
    restore: (id: number, version: number) => http.post<PostDetail>(`/posts/${id}/revisions/${version}/restore`).then((r) => r.data),
    /** 공감 토글(한 번 더 누르면 취소) */
    like: (id: number) => http.post<LikeResponse>(`/posts/${id}/like`).then((r) => r.data),
  },
  categories: {
    tree: () => http.get<CategoryNode[]>('/categories').then((r) => r.data),
    create: (name: string, parentId: number | null) => http.post<CategoryNode>('/categories', { name, parentId }).then((r) => r.data),
    update: (id: number, body: { name: string; parentId?: number | null; moveToRoot?: boolean; sortOrder?: number }) =>
      http.put<CategoryNode>(`/categories/${id}`, body).then((r) => r.data),
    move: (id: number, direction: -1 | 1) => http.post(`/categories/${id}/move`, null, { params: { direction } }),
    remove: (id: number) => http.delete(`/categories/${id}`),
  },
  files: {
    upload: (file: File) => {
      const fd = new FormData(); fd.append('file', file)
      return http.post<AttachmentResponse>('/files', fd, { headers: { 'Content-Type': 'multipart/form-data' }, timeout: 300_000 }).then((r) => r.data)
    },
  },
  taxonomy: {
    tags: (limit = 30) => http.get<TagCount[]>('/tags', { params: { limit } }).then((r) => r.data),
  },
  history: {
    activity: (page = 0, size = 20) => http.get<PageResponse<ActivityEntry>>('/history', { params: { page, size } }).then((r) => r.data),
  },
  tokens: {
    list: () => http.get<TokenInfo[]>('/tokens').then((r) => r.data),
    create: (name: string, expiresInDays?: number) =>
      http.post<{ token: string; info: TokenInfo }>('/tokens', { name, expiresInDays }).then((r) => r.data),
    revoke: (id: number) => http.delete(`/tokens/${id}`),
  },
  guide: {
    list: () => http.get<GuideDoc[]>('/guide').then((r) => r.data),
    get: (id: string) => http.get<string>(`/guide/${encodeURIComponent(id)}`, { responseType: 'text', transformResponse: (d) => d }).then((r) => r.data),
  },
}

/** Content-Disposition 의 filename*=UTF-8''… 을 우선 디코드하고, 없으면 filename="…" 을 쓴다. */
export function parseContentDispositionFilename(header: unknown): string | null {
  if (typeof header !== 'string' || !header) return null
  const star = /filename\*\s*=\s*(?:UTF-8|utf-8)''([^;]+)/.exec(header)
  if (star) { try { return decodeURIComponent(star[1].trim()) } catch { /* fall through */ } }
  const plain = /filename\s*=\s*"?([^";]+)"?/.exec(header)
  return plain ? plain[1].trim() : null
}

/** responseType: 'blob' 요청이 실패하면 오류 본문도 Blob 으로 오므로 JSON 을 꺼내 메시지를 만든다. */
export async function blobErrorMessage(e: unknown, fallback = '요청을 처리하지 못했습니다.'): Promise<string> {
  if (axios.isAxiosError(e) && e.response?.data instanceof Blob) {
    try { const body = JSON.parse(await e.response.data.text()) as Partial<ApiErrorBody>; if (body.message) return body.message } catch { /* ignore */ }
  }
  return errorMessage(e, fallback)
}

/** Blob 을 a[download] 로 저장 */
export function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url; a.download = filename; a.rel = 'noopener'
  document.body.appendChild(a); a.click(); a.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 10_000)
}

// ---------- formatting helpers ----------
export function formatDate(iso: string | null | undefined, withTime = false): string {
  if (!iso) return ''
  const d = new Date(iso)
  const y = d.getFullYear(), m = String(d.getMonth() + 1).padStart(2, '0'), day = String(d.getDate()).padStart(2, '0')
  if (!withTime) return `${y}.${m}.${day}`
  const hh = String(d.getHours()).padStart(2, '0'), mm = String(d.getMinutes()).padStart(2, '0')
  return `${y}.${m}.${day} ${hh}:${mm}`
}

export function relativeTime(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime()
  const min = Math.floor(diff / 60000)
  if (min < 1) return '방금'
  if (min < 60) return `${min}분 전`
  const h = Math.floor(min / 60)
  if (h < 24) return `${h}시간 전`
  const d = Math.floor(h / 24)
  if (d < 7) return `${d}일 전`
  return formatDate(iso)
}

export function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes}B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)}KB`
  return `${(bytes / 1024 / 1024).toFixed(1)}MB`
}

export const changeTypeLabel: Record<ChangeType, string> = {
  CREATE: '작성',
  UPDATE: '수정',
  RESTORE: '복원',
  DELETE: '삭제',
  UNDELETE: '삭제 취소',
}
