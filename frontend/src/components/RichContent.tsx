import { useMemo } from 'react'
import DOMPurify from 'dompurify'
import Markdown from './Markdown'
import '../editor/editor.css'

/** 글 본문 렌더러: HTML(리치 에디터)은 한 번 더 정화해서, MARKDOWN(예전 글)은 마크다운으로. */
export default function RichContent({ content, format }: { content: string; format: 'HTML' | 'MARKDOWN' }) {
  const html = useMemo(() => format === 'HTML' ? sanitize(content) : '', [content, format])
  if (format === 'MARKDOWN') return <Markdown>{content}</Markdown>
  return <div className="rich" dangerouslySetInnerHTML={{ __html: html }} />
}

export function sanitize(html: string): string {
  return DOMPurify.sanitize(html, {
    ADD_ATTR: ['data-align', 'data-indent', 'data-width', 'data-sheet', 'data-diagram', 'colwidth', 'target'],
    ADD_TAGS: ['figure', 'figcaption'],
    FORBID_TAGS: ['script', 'style', 'iframe', 'object', 'embed'],
  })
}

/** 외부 링크는 새 창(R11): `/`로 시작하는 내부 링크만 같은 창에 둔다. 정화 직후 한 번 거는 DOMPurify 훅. */
DOMPurify.addHook('afterSanitizeAttributes', (node) => {
  if (node.nodeName !== 'A') return
  const href = node.getAttribute('href') ?? ''
  if (!href || href.startsWith('/') || href.startsWith('#')) return
  node.setAttribute('target', '_blank')
  node.setAttribute('rel', 'noopener noreferrer')
})
