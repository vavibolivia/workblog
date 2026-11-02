import { useEffect, useState } from 'react'
import { errorMessage } from '../lib/api'
import { useAuth } from '../lib/auth'
import { frontApi, notifyFrontChanged, type FrontKind } from '../lib/frontApi'
import { useToast } from '../lib/toast'

/** 관리자에게만 보이는 [대문에 올리기 / 대문에서 내리기] 버튼. 글·공수 표 화면 어디서든 바로. */
export default function FrontToggle({ kind, refId, large }: { kind: FrontKind; refId: number; large?: boolean }) {
  const { user } = useAuth()
  const toast = useToast()
  const [on, setOn] = useState<boolean | null>(null)
  const [busy, setBusy] = useState(false)
  const admin = user?.role === 'ADMIN'

  useEffect(() => {
    if (!admin) return
    let alive = true
    frontApi.list().then((l) => { if (alive) setOn(l.some((i) => i.kind === kind && i.refId === refId)) }).catch(() => null)
    return () => { alive = false }
  }, [admin, kind, refId])

  if (!admin || on === null) return null
  const flip = async () => {
    setBusy(true)
    try {
      if (on) await frontApi.remove(kind, refId); else await frontApi.add(kind, refId)
      setOn(!on)
      notifyFrontChanged()
      toast(on ? '대문에서 내렸습니다.' : '대문에 올렸습니다. 홈 맨 위 탭에 보입니다.', 'success')
    } catch (e) { toast(errorMessage(e), 'error') } finally { setBusy(false) }
  }
  return (
    <button type="button" className={`btn${large ? '' : ' btn--sm'}${on ? ' btn--on front-toggle' : ''}`} onClick={flip} disabled={busy} aria-pressed={on}
            title={on ? '대문(홈 맨 위)에 올라가 있습니다. 누르면 대문에서 내립니다.' : '홈 맨 위(대문)에 탭으로 띄웁니다. 순서는 홈의 [대문 고르기] 에서'}>
      {on ? <><span className="front-toggle__on">★ 대문에 있음</span><span className="front-toggle__off">대문에서 내리기</span></> : '☆ 대문에 올리기'}
    </button>
  )
}
