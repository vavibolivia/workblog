import { http, type AttachmentResponse } from './api'

/** 파일 → 본문 변환 결과(POST /api/import/convert). 저장은 하지 않는다. */
export interface ConvertResponse {
  title: string
  content: string
  contentFormat: 'HTML'
  sourceType: 'HTML' | 'WORD' | 'EXCEL' | 'CSV' | 'MARKDOWN' | 'TEXT'
  attachments: AttachmentResponse[]
  warnings: string[]
}

export const IMPORT_ACCEPT = '.html,.htm,.docx,.xlsx,.xlsm,.xls,.csv,.md,.markdown,.txt'

export const editorApi = {
  /** HTML·Word·Excel·CSV·Markdown·텍스트 파일을 본문 HTML 로 바꾼다. 문서 안의 사진은 첨부로 저장된다. */
  convert: (file: File, attachOriginal = false) => {
    const fd = new FormData(); fd.append('file', file)
    return http.post<ConvertResponse>('/import/convert', fd, { params: { attachOriginal }, headers: { 'Content-Type': 'multipart/form-data' }, timeout: 300_000 }).then((r) => r.data)
  },
  /** 공개 설정(다이어그램 편집기 주소 등). 토큰 없이도 열린다. */
  options: () => http.get<{ signupEnabled: boolean; signupRequiresApproval: boolean; drawioUrl: string }>('/auth/options').then((r) => r.data),
}

/** 이미지 주소 → data URI (draw.io 에 기존 다이어그램(PNG 안의 XML)을 다시 열 때) */
export async function toDataUri(url: string): Promise<string> {
  const res = await fetch(url, { credentials: 'same-origin' })
  if (!res.ok) throw new Error('다이어그램 파일을 불러오지 못했습니다.')
  const blob = await res.blob()
  return new Promise((resolve, reject) => {
    const r = new FileReader()
    r.onload = () => resolve(String(r.result))
    r.onerror = () => reject(new Error('파일을 읽지 못했습니다.'))
    r.readAsDataURL(blob)
  })
}

export function dataUriToFile(dataUri: string, name: string): File {
  const [head, b64] = dataUri.split(',', 2)
  const mime = /data:([^;]+)/.exec(head)?.[1] ?? 'image/png'
  const bin = atob(b64)
  const bytes = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i)
  return new File([bytes], name, { type: mime })
}
