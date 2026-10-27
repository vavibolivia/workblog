import { useEffect, useState, type RefObject } from 'react'

/**
 * 글 목차(X1). 본문(.rich 또는 .md)의 h1~h3 을 읽어 오른쪽 sticky 패널에 보여 준다(네이버 블로그 '목차').
 * - 제목이 3개 미만이면 아무것도 그리지 않는다.
 * - 제목에 id 가 없으면 `h-{index}` 를 붙이고, 클릭하면 부드럽게 스크롤한다.
 * - IntersectionObserver 로 현재 읽는 제목을 글자색·굵기만으로 강조한다(왼쪽 색 띠 금지).
 * - 1100px 이하에서는 본문 위에 접힌 "목차" 토글로 보인다(CSS 로 배치만 바뀌고 컴포넌트는 같다).
 */
interface Heading { id: string; text: string; level: number }

export default function Toc({ bodyRef, deps }: { bodyRef: RefObject<HTMLElement | null>; deps: unknown }) {
  const [items, setItems] = useState<Heading[]>([])
  const [active, setActive] = useState<string | null>(null)
  const [open, setOpen] = useState(false)

  // 본문이 바뀌면(글 로드·RichContent 렌더 후) 제목을 다시 읽는다.
  useEffect(() => {
    const root = bodyRef.current
    if (!root) { setItems([]); return }
    const read = () => {
      const hs = Array.from(root.querySelectorAll<HTMLElement>('h1, h2, h3'))
      const list: Heading[] = []
      hs.forEach((h, i) => {
        const text = (h.textContent ?? '').trim()
        if (!text) return
        if (!h.id) h.id = `h-${i + 1}`
        list.push({ id: h.id, text, level: Number(h.tagName.slice(1)) })
      })
      setItems(list.length >= 3 ? list : [])
    }
    read()
    // 렌더 직후 한 번 더(dangerouslySetInnerHTML 과 마크다운 렌더 타이밍 차이 대비)
    const t = window.setTimeout(read, 50)
    return () => window.clearTimeout(t)
  }, [bodyRef, deps])

  useEffect(() => {
    if (items.length === 0) return
    const root = bodyRef.current
    if (!root) return
    const gnb = parseInt(getComputedStyle(document.documentElement).getPropertyValue('--gnb-h')) || 60
    const els = items.map((it) => root.querySelector<HTMLElement>(`#${cssEscape(it.id)}`)).filter((x): x is HTMLElement => !!x)
    const visible = new Map<string, number>()
    const io = new IntersectionObserver((entries) => {
      for (const en of entries) {
        if (en.isIntersecting) visible.set(en.target.id, en.boundingClientRect.top)
        else visible.delete(en.target.id)
      }
      if (visible.size > 0) {
        // 보이는 제목 중 가장 위의 것
        const top = Array.from(visible.entries()).sort((a, b) => a[1] - b[1])[0][0]
        setActive(top)
      } else {
        // 아무 제목도 안 보이면 화면 위를 지나간 마지막 제목
        let last: string | null = null
        for (const el of els) { if (el.getBoundingClientRect().top < gnb + 40) last = el.id; else break }
        if (last) setActive(last)
      }
    }, { rootMargin: `-${gnb + 10}px 0px -55% 0px`, threshold: [0, 1] })
    els.forEach((el) => io.observe(el))
    return () => io.disconnect()
  }, [items, bodyRef])

  if (items.length === 0) return null

  const go = (id: string) => (e: React.MouseEvent) => {
    e.preventDefault()
    const el = bodyRef.current?.querySelector<HTMLElement>(`#${cssEscape(id)}`)
    if (!el) return
    const gnb = parseInt(getComputedStyle(document.documentElement).getPropertyValue('--gnb-h')) || 60
    const y = el.getBoundingClientRect().top + window.scrollY - gnb - 16
    window.scrollTo({ top: y, behavior: 'smooth' })
    history.replaceState(null, '', `#${id}`)
    setActive(id)
    setOpen(false)
  }

  const minLevel = Math.min(...items.map((i) => i.level))

  return (
    <nav className={`toc${open ? ' toc--open' : ''}`} aria-label="목차">
      <button type="button" className="toc__toggle" aria-expanded={open} onClick={() => setOpen((v) => !v)}>
        목차 <span className="toc__count">{items.length}</span>
        <span className="toc__chev" aria-hidden>{open ? '▴' : '▾'}</span>
      </button>
      <div className="toc__head" aria-hidden>목차</div>
      <ol className="toc__list">
        {items.map((it) => (
          <li key={it.id} className={`toc__item toc__item--l${it.level - minLevel + 1}${active === it.id ? ' is-active' : ''}`}>
            <a href={`#${it.id}`} onClick={go(it.id)} aria-current={active === it.id ? 'location' : undefined}>{it.text}</a>
          </li>
        ))}
      </ol>
    </nav>
  )
}

function cssEscape(s: string): string {
  return typeof CSS !== 'undefined' && CSS.escape ? CSS.escape(s) : s.replace(/[^a-zA-Z0-9_-]/g, (c) => `\\${c}`)
}
