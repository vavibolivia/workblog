import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react'

type Kind = 'info' | 'success' | 'error'
interface Toast { id: number; kind: Kind; text: string }

const ToastContext = createContext<{ push: (text: string, kind?: Kind) => void } | null>(null)

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<Toast[]>([])
  const seq = useRef(0)
  const push = useCallback((text: string, kind: Kind = 'info') => {
    const id = ++seq.current
    setItems((s) => [...s, { id, kind, text }])
    window.setTimeout(() => setItems((s) => s.filter((t) => t.id !== id)), 3600)
  }, [])
  const value = useMemo(() => ({ push }), [push])
  return (
    <ToastContext.Provider value={value}>
      {children}
      <div className="toast-stack" role="status" aria-live="polite">
        {items.map((t) => (
          <div key={t.id} className={`toast toast--${t.kind}`}>{t.text}</div>
        ))}
      </div>
    </ToastContext.Provider>
  )
}

export function useToast() {
  const ctx = useContext(ToastContext)
  if (!ctx) throw new Error('useToast must be used within ToastProvider')
  return ctx.push
}
