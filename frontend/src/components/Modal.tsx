import { useEffect, useRef, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'

/**
 * 대화상자. dirty(고친 내용 있음)면 바깥 클릭으로는 닫히지 않고 Esc 는 한 번 더 묻는다. busy(저장 중)면 닫히지 않는다.
 * 한글 조합 중 Esc 는 조합 취소로 두고 닫지 않는다. 열 때 포커스가 있던 곳으로 닫은 뒤 돌려준다. Tab 은 창 안에서만 돈다.
 */
export default function Modal({ title, onClose, children, footer, wide = false, dirty = false, busy = false }:
  { title: string; onClose: () => void; children: ReactNode; footer?: ReactNode; wide?: boolean; dirty?: boolean; busy?: boolean }) {
  const box = useRef<HTMLDivElement>(null)
  const [ask, setAsk] = useState(false)
  const state = useRef({ dirty, busy, ask, onClose })
  useEffect(() => { state.current = { dirty, busy, ask, onClose } })
  useEffect(() => {
    const before = document.activeElement as HTMLElement | null
    const onKey = (e: KeyboardEvent) => {
      const s = state.current
      if (e.key === 'Escape') {
        if (e.isComposing || s.busy) return
        if (s.dirty && !s.ask) { setAsk(true); return }
        s.onClose()
      } else if (e.key === 'Tab' && box.current) {
        const f = [...box.current.querySelectorAll<HTMLElement>('button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])')]
          .filter((el) => el.offsetParent !== null)
        if (!f.length) return
        const first = f[0], last = f[f.length - 1]
        if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus() }
        else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus() }
      }
    }
    document.addEventListener('keydown', onKey)
    const prev = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => { document.removeEventListener('keydown', onKey); document.body.style.overflow = prev; before?.focus?.({ preventScroll: true }) }
  }, [])
  const close = () => { if (busy) return; if (dirty && !ask) { setAsk(true); return } onClose() }
  // body 에 바로 그린다 — sticky 툴바 같은 쌓임 맥락 안에 갇혀 어두운 막이 상단 메뉴를 못 덮는 일이 없게
  return createPortal(
    <div className="modal-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget && !dirty && !busy) onClose() }}>
      <div className={`modal${wide ? ' modal--wide' : ''}`} role="dialog" aria-modal="true" aria-label={title} ref={box}>
        <div className="modal__head">
          <h3>{title}</h3>
          <button type="button" className="btn btn--ghost btn--sm" onClick={close} aria-label="닫기">✕</button>
        </div>
        <div className="modal__body">{children}</div>
        {ask && dirty && (
          <div className="modal__ask" role="alert">
            <span>입력한 내용을 버리고 닫을까요?</span>
            <button type="button" className="btn btn--sm" onClick={() => setAsk(false)}>계속 쓰기</button>
            <button type="button" className="btn btn--sm btn--danger" onClick={onClose}>버리고 닫기</button>
          </div>
        )}
        {footer && <div className="modal__foot">{footer}</div>}
      </div>
    </div>,
    document.body,
  )
}
