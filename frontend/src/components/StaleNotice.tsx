import { useEffect, useState } from 'react'
import { errorMessage } from '../lib/api'
import { knowledgeApi, type ReviewStatus } from '../lib/knowledgeApi'
import { useToast } from '../lib/toast'

/** 글 상세 상단: 오래 수정되지 않은 발행 글 안내. 작성자·관리자에게는 [내용 확인 완료] 버튼. */
export default function StaleNotice({ postId }: { postId: number }) {
  const toast = useToast()
  const [st, setSt] = useState<ReviewStatus | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let alive = true
    setSt(null)
    knowledgeApi.review.status(postId).then((s) => { if (alive) setSt(s) }).catch(() => { /* 부가 정보: 실패하면 숨김 */ })
    return () => { alive = false }
  }, [postId])

  const confirm = async () => {
    setBusy(true)
    try {
      setSt(await knowledgeApi.review.confirm(postId))
      toast('내용 확인을 기록했습니다. 버전은 바뀌지 않습니다.', 'success')
    } catch (e) { toast(errorMessage(e), 'error') } finally { setBusy(false) }
  }

  if (!st || !st.stale) return null
  return (
    <div className="alert alert--warn stale-notice" role="status">
      <span><strong>{st.daysSince.toLocaleString()}일</strong> 동안 수정되지 않은 글입니다. 내용이 지금도 맞는지 확인해 주세요.</span>
      {st.canConfirm && <button type="button" className="btn btn--sm" disabled={busy} onClick={confirm}>{busy ? '기록 중…' : '내용 확인 완료'}</button>}
    </div>
  )
}
