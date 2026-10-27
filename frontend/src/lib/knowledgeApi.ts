import { http, type CategoryRef, type PageResponse } from './api'

/** 글 템플릿(GET/POST/PUT/DELETE /api/templates). 관리는 관리자만. */
export interface TemplateResponse {
  id: number
  name: string
  description: string | null
  content: string
  /** 기본 카테고리(없거나 지워졌으면 null) */
  category: CategoryRef | null
  tags: string[]
  sortOrder: number
  createdAt: string
  updatedAt: string
}

export interface TemplateRequest {
  name: string
  description?: string
  content: string
  categoryId?: number | null
  tags: string[]
  sortOrder?: number
}

/** 관련 글(점수 = 공통 태그×2 + 같은 카테고리 1) · 이 글을 언급한 글 */
export interface RelatedPost {
  id: number
  title: string
  summary: string | null
  category: CategoryRef
  tags: string[]
  authorName: string
  publishedAt: string | null
  updatedAt: string
  score: number
  commonTags: string[]
}
export interface RelatedResponse { related: RelatedPost[]; backlinks: RelatedPost[] }

/** 재검토 상태. 기준일 lastCheckedAt = max(마지막 수정, 내용 확인) */
export interface ReviewStatus {
  postId: number
  staleDays: number
  lastModifiedAt: string | null
  reviewedAt: string | null
  lastCheckedAt: string | null
  daysSince: number
  stale: boolean
  canConfirm: boolean
}

export interface StaleRow {
  id: number
  title: string
  authorName: string
  categoryName: string
  viewCount: number
  lastModifiedAt: string | null
  reviewedAt: string | null
  lastCheckedAt: string | null
  daysSince: number
}

export const knowledgeApi = {
  templates: {
    list: () => http.get<TemplateResponse[]>('/templates').then((r) => r.data),
    create: (body: TemplateRequest) => http.post<TemplateResponse>('/templates', body).then((r) => r.data),
    update: (id: number, body: TemplateRequest) => http.put<TemplateResponse>(`/templates/${id}`, body).then((r) => r.data),
    remove: (id: number) => http.delete(`/templates/${id}`),
  },
  related: (postId: number) => http.get<RelatedResponse>(`/posts/${postId}/related`).then((r) => r.data),
  review: {
    status: (postId: number) => http.get<ReviewStatus>(`/posts/${postId}/review`).then((r) => r.data),
    confirm: (postId: number) => http.post<ReviewStatus>(`/posts/${postId}/review-ok`).then((r) => r.data),
    stale: (page = 0, size = 20) => http.get<PageResponse<StaleRow>>('/admin/review/stale', { params: { page, size } }).then((r) => r.data),
  },
}
