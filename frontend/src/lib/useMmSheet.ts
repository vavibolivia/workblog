import { useCallback, useEffect, useRef, useState } from 'react'
import { errorMessage } from './api'
import { mmApi, type MmCellChange, type MmSheet } from './manMonthApi'
import { useToast } from './toast'

const POLL_MS = 20_000

/** 공수 표 불러오기 · 칸 저장(모아서 0.5초 뒤) · 다른 사람 변경 반영(20초마다, 저장할 것이 없을 때). */
export function useMmSheet(id: number) {
  const toast = useToast()
  const [sheet, setSheet] = useState<MmSheet | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const [overlay, setOverlay] = useState<Record<string, number | null>>({})
  const queue = useRef<Map<string, MmCellChange>>(new Map())
  const timer = useRef<number | undefined>(undefined)
  const [saving, setSaving] = useState(false)

  const reload = useCallback(() => mmApi.get(id).then((s) => { setSheet(s); setErr(null) }).catch((e) => setErr(errorMessage(e))), [id])

  useEffect(() => {
    let alive = true
    setSheet(null)
    mmApi.get(id).then((s) => { if (alive) setSheet(s) }).catch((e) => { if (alive) setErr(errorMessage(e)) })
    return () => { alive = false }
  }, [id])

  useEffect(() => {
    const t = window.setInterval(() => {
      if (queue.current.size === 0 && document.visibilityState === 'visible') mmApi.get(id).then(setSheet).catch(() => null)
    }, POLL_MS)
    return () => window.clearInterval(t)
  }, [id])

  const flush = useCallback(async () => {
    const batch = [...queue.current.values()]
    queue.current.clear()
    if (!batch.length) return
    setSaving(true)
    try {
      const s = await mmApi.setCells(id, batch)
      setSheet(s)
      setOverlay((o) => {
        const n = { ...o }
        for (const c of batch) { const k = `${c.rowId}:${c.week}`; if (!queue.current.has(k)) delete n[k] }
        return n
      })
    } catch (e) {
      toast(errorMessage(e, '저장하지 못했습니다.'), 'error')
      setOverlay({})
      reload()
    } finally { setSaving(false) }
  }, [id, toast, reload])

  const change = useCallback((changes: MmCellChange[]) => {
    setOverlay((o) => {
      const n = { ...o }
      for (const c of changes) { const k = `${c.rowId}:${c.week}`; n[k] = c.value; queue.current.set(k, c) }
      return n
    })
    window.clearTimeout(timer.current)
    timer.current = window.setTimeout(flush, 500)
  }, [flush])

  useEffect(() => () => { window.clearTimeout(timer.current); if (queue.current.size) void flush() }, [flush])

  return { sheet, setSheet, err, overlay, change, saving, reload }
}
