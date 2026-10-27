import { marked } from 'marked'

/** 예전 마크다운 글을 리치 에디터로 열 때 HTML 로 변환 */
export function markdownToHtml(md: string): string {
  return marked.parse(md, { async: false, gfm: true, breaks: true }) as string
}

/** HTML → 평문(줄 단위). 버전 비교·글자 수에 쓴다. */
export function htmlToText(html: string): string {
  const doc = new DOMParser().parseFromString(html, 'text/html')
  doc.querySelectorAll('br').forEach((b) => b.replaceWith('\n'))
  doc.querySelectorAll('p, div, h1, h2, h3, h4, li, tr, blockquote, pre, figcaption, hr').forEach((el) => el.append('\n'))
  doc.querySelectorAll('td, th').forEach((el) => el.append('\t'))
  return (doc.body.textContent ?? '').replace(/[ \t]+\n/g, '\n').replace(/\n{3,}/g, '\n\n').trim()
}

export function contentToText(content: string, format: 'HTML' | 'MARKDOWN'): string {
  return format === 'HTML' ? htmlToText(content) : content
}
