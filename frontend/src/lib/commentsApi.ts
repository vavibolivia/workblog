import { http } from './api'

export interface CommentAuthor {
  id: number
  displayName: string
  department: string | null
}

/** 삭제된 댓글은 content 가 null, deleted 가 true. 답글이 남아 있을 때만 목록에 온다. */
export interface CommentResponse {
  id: number
  postId: number
  parentId: number | null
  author: CommentAuthor
  content: string | null
  deleted: boolean
  createdAt: string
  updatedAt: string
  editable: boolean
}

export const COMMENT_MAX_LENGTH = 2000

export const commentsApi = {
  list: (postId: number) => http.get<CommentResponse[]>(`/posts/${postId}/comments`).then((r) => r.data),
  create: (postId: number, content: string, parentId?: number | null) =>
    http.post<CommentResponse>(`/posts/${postId}/comments`, { content, parentId: parentId ?? null }).then((r) => r.data),
  update: (postId: number, id: number, content: string) =>
    http.put<CommentResponse>(`/posts/${postId}/comments/${id}`, { content }).then((r) => r.data),
  remove: (postId: number, id: number) => http.delete(`/posts/${postId}/comments/${id}`),
}
