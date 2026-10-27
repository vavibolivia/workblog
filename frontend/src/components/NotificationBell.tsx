import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { errorMessage, relativeTime } from '../lib/api'
import { notificationHref, notificationsApi, notificationText, type NotificationItem } from '../lib/collabApi'
import { useToast } from '../lib/toast'
import { IcBell } from './Icons'

const POLL_MS = 60_000

/** 상단바 종: 읽지 않은 수 배지(60초마다 갱신) + 펼치면 최근 알림 20건. 누르면 읽음 처리 후 글/댓글로 이동. */
export default function NotificationBell() {
  const navigate = useNavigate()
  const toast = useToast()
  const [count, setCount] = useState(0)
  const [open, setOpen] = useState(false)
  const [items, setItems] = useState<NotificationItem[] | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const ref = useRef<HTMLDivElement>(null)

  const refreshCount = useCallback(() => {
    notificationsApi.unreadCount().then(setCount).catch(() => { /* 다음 주기에 다시 */ })
  }, [])

  useEffect(() => {
    refreshCount()
    const t = window.setInterval(() => { if (document.visibilityState === 'visible') refreshCount() }, POLL_MS)
    const onFocus = () => refreshCount()
    window.addEventListener('focus', onFocus)
    return () => { window.clearInterval(t); window.removeEventListener('focus', onFocus) }
  }, [refreshCount])

  const load = useCallback(async () => {
    setErr(null)
    try { const r = await notificationsApi.list({ size: 20 }); setItems(r.content) }
    catch (e) { setErr(errorMessage(e, '알림을 불러오지 못했습니다.')) }
  }, [])

  useEffect(() => {
    if (!open) return
    load(); refreshCount()
    const onDoc = (e: MouseEvent) => { if (!ref.current?.contains(e.target as Node)) setOpen(false) }
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false) }
    document.addEventListener('mousedown', onDoc)
    document.addEventListener('keydown', onKey)
    return () => { document.removeEventListener('mousedown', onDoc); document.removeEventListener('keydown', onKey) }
  }, [open, load, refreshCount])

  const go = async (n: NotificationItem) => {
    setOpen(false)
    if (!n.read) {
      try { await notificationsApi.read(n.id); setCount((c) => Math.max(0, c - 1)) } catch { /* 이동은 계속 */ }
    }
    if (!n.available) { toast('지금은 볼 수 없는 글입니다(비공개·임시 저장·열람 제한·삭제).', 'info'); return }
    navigate(notificationHref(n))
  }

  const readAll = async () => {
    try { await notificationsApi.readAll(); setCount(0); setItems((l) => l?.map((n) => ({ ...n, read: true })) ?? l) }
    catch (e) { toast(errorMessage(e), 'error') }
  }

  const label = count > 0 ? `알림 ${count}건 읽지 않음` : '알림'
  return (
    <div className="notif" ref={ref}>
      <button type="button" className="notif__btn" aria-haspopup="true" aria-expanded={open} aria-label={label} title={label} onClick={() => setOpen((v) => !v)}>
        <IcBell size={20} />
        {count > 0 && <span className="notif__badge" aria-hidden>{count > 99 ? '99+' : count}</span>}
      </button>
      {open && (
        <div className="notif__panel" role="dialog" aria-label="알림">
          <div className="notif__head">
            <strong>알림</strong>
            <button type="button" className="notif__link" onClick={readAll} disabled={count === 0 && !(items ?? []).some((n) => !n.read)}>모두 읽음</button>
          </div>
          {err && <div className="alert alert--error" style={{ margin: 8 }}>{err}</div>}
          {!items && !err && <div className="notif__empty"><div className="spinner" style={{ margin: '0 auto' }} /></div>}
          {items && items.length === 0 && <p className="notif__empty">새 알림이 없습니다. 내 글에 댓글이 달리거나 누가 @{'아이디'}로 부르면 여기에 표시됩니다.</p>}
          {items && items.length > 0 && (
            <ul className="notif__list">
              {items.map((n) => (
                <li key={n.id}>
                  <button type="button" className={`notif__item${n.read ? '' : ' notif__item--unread'}`} onClick={() => go(n)}>
                    <span className="notif__text">{notificationText(n)}</span>
                    <span className="notif__title ellipsis">{n.available ? n.postTitle : '볼 수 없는 글'}</span>
                    {n.excerpt && <span className="notif__excerpt">{n.excerpt}</span>}
                    <span className="notif__time">{relativeTime(n.createdAt)}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  )
}
