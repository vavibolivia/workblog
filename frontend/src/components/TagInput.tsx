import { useState, type KeyboardEvent } from 'react'

export default function TagInput({ value, onChange, placeholder = '태그 입력 후 Enter' }:
  { value: string[]; onChange: (tags: string[]) => void; placeholder?: string }) {
  const [draft, setDraft] = useState('')

  const commit = () => {
    const parts = draft.split(/[,\n]/).map((s) => s.trim().replace(/^#/, '')).filter(Boolean)
    if (parts.length) onChange([...new Set([...value, ...parts])].slice(0, 10))
    setDraft('')
  }
  const onKey = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter' || e.key === ',') { e.preventDefault(); commit() }
    else if (e.key === 'Backspace' && !draft && value.length) onChange(value.slice(0, -1))
  }
  return (
    <div className="tag-input" onClick={(e) => (e.currentTarget.querySelector('input') as HTMLInputElement | null)?.focus()}>
      {value.map((t) => (
        <span key={t} className="chip">#{t}
          <button type="button" aria-label={`${t} 태그 제거`} onClick={() => onChange(value.filter((x) => x !== t))}>×</button>
        </span>
      ))}
      <input value={draft} onChange={(e) => setDraft(e.target.value)} onKeyDown={onKey} onBlur={commit}
             placeholder={value.length ? '' : placeholder} aria-label="태그" />
    </div>
  )
}
