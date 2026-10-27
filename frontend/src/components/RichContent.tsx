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
    ADD_ATTR: ['data-align', 'data-indent', 'data-width', 'data-sheet', 'data-diagram', 'data-ink-dark', 'colwidth', 'target'],
    ADD_TAGS: ['figure', 'figcaption'],
    FORBID_TAGS: ['script', 'style', 'iframe', 'object', 'embed'],
  })
}

/** CSS 색 문자열 → 상대 휘도(0~1). 모르는 형식이면 null. */
function luminance(c: string): number | null {
  let r: number, g: number, b: number
  const hex = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(c.trim())
  const rgb = /^rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)/i.exec(c.trim())
  if (hex) {
    const h = hex[1].length === 3 ? hex[1].split('').map((x) => x + x).join('') : hex[1]
    r = parseInt(h.slice(0, 2), 16); g = parseInt(h.slice(2, 4), 16); b = parseInt(h.slice(4, 6), 16)
  } else if (rgb) { r = +rgb[1]; g = +rgb[2]; b = +rgb[3] } else if (/^black$/i.test(c.trim())) { r = g = b = 0 } else return null
  const f = (v: number) => { v /= 255; return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4 }
  return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b)
}

/**
 * 다크 모드 가독성: 글자색을 직접 지정한 요소 중 어두운 색(휘도 < 0.12)이고 자기 배경색이 없는 것에 표시를 단다.
 * CSS 가 다크 모드일 때만 이 표시를 밝은 글자색으로 바꾼다(라이트·인쇄에서는 원래 색 그대로).
 */
DOMPurify.addHook('afterSanitizeAttributes', (node) => {
  const el = node as Element
  if (!el.getAttribute) return
  const style = el.getAttribute('style')
  if (!style || /background/i.test(style)) return
  const m = /(?:^|;)\s*color\s*:\s*([^;]+)/i.exec(style)
  if (!m) return
  const l = luminance(m[1])
  if (l !== null && l < 0.12) el.setAttribute('data-ink-dark', '')
})

/** 외부 링크는 새 창(R11): `/`로 시작하는 내부 링크만 같은 창에 둔다. 정화 직후 한 번 거는 DOMPurify 훅. */
DOMPurify.addHook('afterSanitizeAttributes', (node) => {
  if (node.nodeName !== 'A') return
  const href = node.getAttribute('href') ?? ''
  if (!href || href.startsWith('/') || href.startsWith('#')) return
  node.setAttribute('target', '_blank')
  node.setAttribute('rel', 'noopener noreferrer')
})
