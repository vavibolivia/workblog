import { useEffect, useState } from 'react'

/** 주 수 입력(1~104). 치는 동안은 그대로 두고(지우고 다시 치기 가능), 칸을 떠날 때 범위를 맞춘다. */
export default function WeeksInput({ id, value, onChange }: { id: string; value: number; onChange: (n: number) => void }) {
  const [text, setText] = useState(String(value))
  useEffect(() => { setText(String(value)) }, [value])
  const n = Number(text)
  const bad = text !== '' && (!Number.isInteger(n) || n < 1 || n > 104)
  return (
    <>
      <input id={id} type="number" min={1} max={104} className="input" value={text} aria-invalid={bad}
             onChange={(e) => { setText(e.target.value); const v = Number(e.target.value); if (Number.isInteger(v) && v >= 1 && v <= 104) onChange(v) }}
             onBlur={() => { const v = Math.max(1, Math.min(104, Math.round(Number(text)) || value)); setText(String(v)); onChange(v) }} />
      {bad && <span className="field__hint field__hint--bad">1~104 주</span>}
    </>
  )
}
