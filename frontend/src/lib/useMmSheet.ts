import { useCallback, useEffect, useRef, useState } from 'react'
import { errorMessage } from './api'
import { mmApi, type MmCellChange, type MmSheet } from './manMonthApi'
import { useToast } from './toast'

const POLL_MS = 20_000
const UNDO_MAX = 100
const keyOf = (c: { rowId: number; week: number }) => `${c.rowId}:${c.week}`

/**
 * 공수 표 불러오기 · 칸 저장(모아서 0.5초 뒤) · 다른 사람 변경 반영(20초마다, 저장할 것이 없을 때).
 * 되돌리기(Ctrl+Z)·다시(Ctrl+Y) 는 이 화면에서 한 변경 묶음 단위. 저장에 실패한 칸은 값을 그대로 두고 [다시 시도] 를 기다린다.
 */
export function useMmSheet(id: number) {
  const toast = useToast()
  const [sheet, setSheetState] = useState<MmSheet | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const [overlay, setOverlayState] = useState<Record<string, number | null>>({})
  const [failed, setFailed] = useState<Set<string>>(new Set())
  const [saving, setSaving] = useState(false)
  const [savedAt, setSavedAt] = useState<Date | null>(null)
  const [history, setHistory] = useState({ undo: 0, redo: 0 })
  const sheetRef = useRef<MmSheet | null>(null)
  const overlayRef = useRef<Record<string, number | null>>({})
  const failedRef = useRef<Set<string>>(new Set())
  const queue = useRef<Map<string, MmCellChange>>(new Map())
  const timer = useRef<number | undefined>(undefined)
  const undoStack = useRef<{ before: MmCellChange[]; after: MmCellChange[] }[]>([])
  const redoStack = useRef<{ before: MmCellChange[]; after: MmCellChange[] }[]>([])

  const setSheet = useCallback((s: MmSheet) => { sheetRef.current = s; setSheetState(s) }, [])
  const setOverlay = (n: Record<string, number | null>) => { overlayRef.current = n; setOverlayState(n) }
  const markFailed = (s: Set<string>) => { failedRef.current = s; setFailed(s) }
  const syncHistory = () => setHistory({ undo: undoStack.current.length, redo: redoStack.current.length })

  const reload = useCallback(() => mmApi.get(id).then((s) => { setSheet(s); setErr(null) }).catch((e) => setErr(errorMessage(e))), [id, setSheet])

  useEffect(() => {
    let alive = true
    setSheetState(null)
    undoStack.current = []; redoStack.current = []; syncHistory()
    mmApi.get(id).then((s) => { if (alive) setSheet(s) }).catch((e) => { if (alive) setErr(errorMessage(e)) })
    return () => { alive = false }
  }, [id, setSheet])

  useEffect(() => {
    const t = window.setInterval(() => {
      if (queue.current.size === 0 && failedRef.current.size === 0 && document.visibilityState === 'visible') mmApi.get(id).then(setSheet).catch(() => null)
    }, POLL_MS)
    return () => window.clearInterval(t)
  }, [id, setSheet])

  const flush = useCallback(async () => {
    const batch = [...queue.current.values()]
    queue.current.clear()
    if (!batch.length) return
    setSaving(true)
    try {
      const s = await mmApi.setCells(id, batch)
      setSheet(s)
      const n = { ...overlayRef.current }
      const f = new Set(failedRef.current)
      for (const c of batch) { const k = keyOf(c); f.delete(k); if (!queue.current.has(k)) delete n[k] }
      setOverlay(n); markFailed(f)
      setSavedAt(new Date())
    } catch (e) {
      // 입력한 값은 화면에 남기고(빨간 점선) [다시 시도] 를 기다린다
      const f = new Set(failedRef.current)
      for (const c of batch) f.add(keyOf(c))
      markFailed(f)
      toast(errorMessage(e, '저장하지 못했습니다.') + ' — 표 위 [다시 시도] 를 누르세요.', 'error')
    } finally { setSaving(false) }
  }, [id, toast, setSheet])

  const valueNow = useCallback((rowId: number, week: number): number | null => {
    const k = `${rowId}:${week}`
    if (k in overlayRef.current) return overlayRef.current[k]
    return sheetRef.current?.rows.find((r) => r.id === rowId)?.cells[String(week)] ?? null
  }, [])

  const apply = useCallback((changes: MmCellChange[]) => {
    const n = { ...overlayRef.current }
    for (const c of changes) { const k = keyOf(c); n[k] = c.value; queue.current.set(k, c) }
    setOverlay(n)
    window.clearTimeout(timer.current)
    timer.current = window.setTimeout(flush, 500)
  }, [flush])

  /** 칸 바꾸기. 되돌리기 기록에 남는다. */
  const change = useCallback((changes: MmCellChange[]) => {
    const real = changes.filter((c) => valueNow(c.rowId, c.week) !== c.value)
    if (!real.length) return
    undoStack.current.push({ before: real.map((c) => ({ ...c, value: valueNow(c.rowId, c.week) })), after: real })
    if (undoStack.current.length > UNDO_MAX) undoStack.current.shift()
    redoStack.current = []
    syncHistory()
    apply(real)
  }, [apply, valueNow])

  const undo = useCallback(() => {
    const step = undoStack.current.pop()
    if (!step) return 0
    redoStack.current.push(step); syncHistory()
    apply(step.before)
    return step.before.length
  }, [apply])
  const redo = useCallback(() => {
    const step = redoStack.current.pop()
    if (!step) return 0
    undoStack.current.push(step); syncHistory()
    apply(step.after)
    return step.after.length
  }, [apply])

  const retry = useCallback(() => {
    for (const k of failedRef.current) {
      const [rowId, week] = k.split(':').map(Number)
      if (k in overlayRef.current) queue.current.set(k, { rowId, week, value: overlayRef.current[k] })
    }
    void flush()
  }, [flush])

  // 저장 전·실패한 칸이 있으면 창 닫기·새로 고침 전에 묻는다
  useEffect(() => {
    const onBefore = (e: BeforeUnloadEvent) => {
      if (queue.current.size || failedRef.current.size) { e.preventDefault(); e.returnValue = '' }
    }
    window.addEventListener('beforeunload', onBefore)
    return () => window.removeEventListener('beforeunload', onBefore)
  }, [])

  useEffect(() => () => { window.clearTimeout(timer.current); if (queue.current.size) void flush() }, [flush])

  return {
    sheet, setSheet, err, overlay, change, saving, reload, savedAt, failed, retry,
    undo, redo, canUndo: history.undo > 0, canRedo: history.redo > 0,
  }
}

export type MmSheetState = ReturnType<typeof useMmSheet>
