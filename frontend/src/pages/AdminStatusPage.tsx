import { useCallback, useEffect, useState } from 'react'
import { errorMessage, formatDate, formatFileSize, opsApi, type CleanupResult, type OpsStatus } from '../lib/api'
import { useToast } from '../lib/toast'
import Modal from '../components/Modal'

const REFRESH_MS = 60_000

function uptime(sec: number): string {
  const d = Math.floor(sec / 86400), h = Math.floor((sec % 86400) / 3600), m = Math.floor((sec % 3600) / 60)
  return d > 0 ? `${d}일 ${h}시간 ${m}분` : h > 0 ? `${h}시간 ${m}분` : `${m}분`
}

function pct(part: number, whole: number): number {
  return whole > 0 ? Math.min(100, Math.round((part / whole) * 100)) : 0
}

/** 얇은 진행 막대 하나. 색면 패널 없이 선 하나로만 비율을 보인다. */
function Meter({ value, warn = false, label }: { value: number; warn?: boolean; label: string }) {
  return (
    <div className={`meter${warn ? ' meter--warn' : ''}`} role="progressbar" aria-valuenow={value} aria-valuemin={0} aria-valuemax={100} aria-label={label}>
      <span style={{ width: `${value}%` }} />
    </div>
  )
}

function cleanupSummary(r: CleanupResult): string {
  return `휴지통 글 ${r.purgedPosts}건, 고아 첨부 ${r.deletedOrphans}건, 파일 ${r.deletedFiles}개 삭제${r.failedFiles ? `, 파일 실패 ${r.failedFiles}개` : ''}`
}

export default function AdminStatusPage() {
  const toast = useToast()
  const [status, setStatus] = useState<OpsStatus | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const [loadedAt, setLoadedAt] = useState<Date | null>(null)
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)

  const load = useCallback(() => opsApi.status()
    .then((s) => { setStatus(s); setErr(null); setLoadedAt(new Date()) })
    .catch((e) => setErr(errorMessage(e))), [])

  useEffect(() => {
    load()
    const t = window.setInterval(load, REFRESH_MS)
    return () => window.clearInterval(t)
  }, [load])

  const runCleanup = async () => {
    setBusy(true)
    try {
      const r = await opsApi.cleanup()
      if (r.error) toast(`정리 실패: ${r.error}`, 'error')
      else toast(`정리 완료 — ${cleanupSummary(r)}`, 'success')
      load()
    } catch (e) { toast(errorMessage(e), 'error') } finally { setBusy(false); setConfirming(false) }
  }

  const memPct = status ? pct(status.memory.usedBytes, status.memory.maxBytes) : 0
  const diskUsedPct = status ? pct(status.disk.totalBytes - status.disk.freeBytes, status.disk.totalBytes) : 0
  const diskFreePct = status && status.disk.totalBytes > 0 ? (status.disk.freeBytes / status.disk.totalBytes) * 100 : 100
  const hasCode = (code: string) => status?.alerts.some((a) => a.code === code) ?? false

  return (
    <div className="shell shell--wide">
      <div className="page-head row row--between row--wrap">
        <div>
          <h2>시스템 상태</h2>
          <p>가동·자원·데이터 집계와 최근 오류를 봅니다. 60초마다 자동으로 새로 고칩니다.{loadedAt && <> 마지막 갱신 {formatDate(loadedAt.toISOString(), true)}</>}</p>
        </div>
        <div className="row">
          <button type="button" className="btn" onClick={load}>새로 고침</button>
          <button type="button" className="btn btn--primary" onClick={() => setConfirming(true)} disabled={busy}>지금 정리 실행</button>
        </div>
      </div>

      {err && <div className="alert alert--error" style={{ marginBottom: 16 }}>{err}</div>}
      {!status && !err && <div className="empty">불러오는 중…</div>}

      {status && (
        <div className="status-grid">
          {status.alerts.length > 0 && (
            <div className="alert alert--warn card--full">
              <strong>경고 {status.alerts.length}건</strong>
              <ul>{status.alerts.map((a) => <li key={a.code}>{a.message}</li>)}</ul>
            </div>
          )}

          <div className="card">
            <div className="card__head"><h3>가동 정보</h3></div>
            <div className="card__body">
              <table className="kv"><tbody>
                <tr><th>버전</th><td><code>{status.app.version}</code></td></tr>
                <tr><th>가동 시간</th><td>{uptime(status.app.uptimeSeconds)}</td></tr>
                <tr><th>시작 시각</th><td>{formatDate(status.app.startedAt, true)}</td></tr>
                <tr><th>Java</th><td>{status.app.javaVersion}</td></tr>
                <tr><th>DB</th><td>{status.db.up ? <><span className="chip chip--success">연결됨</span> <span className="small muted">{status.db.version}</span></> : <><span className="chip chip--danger">연결 안 됨</span> <span className="small muted">{status.db.error}</span></>}</td></tr>
              </tbody></table>
            </div>
          </div>

          <div className="card">
            <div className="card__head"><h3>메모리 · 디스크</h3></div>
            <div className="card__body stack">
              <div>
                <div className="row row--between small"><span>JVM 메모리</span><span className="muted">{formatFileSize(status.memory.usedBytes)} / {formatFileSize(status.memory.maxBytes)} ({memPct}%)</span></div>
                <Meter value={memPct} warn={hasCode('MEMORY_HIGH')} label="JVM 메모리 사용률" />
              </div>
              <div>
                <div className="row row--between small"><span>첨부 저장소 디스크</span><span className="muted">여유 {formatFileSize(status.disk.freeBytes)} / 전체 {formatFileSize(status.disk.totalBytes)} ({diskFreePct.toFixed(1)}% 여유)</span></div>
                <Meter value={diskUsedPct} warn={hasCode('DISK_LOW')} label="디스크 사용률" />
                <div className="small muted"><code>{status.disk.path}</code></div>
              </div>
            </div>
          </div>

          <div className="card">
            <div className="card__head"><h3>집계</h3></div>
            <div className="card__body">
              <table className="kv"><tbody>
                <tr><th>사용자</th><td>{status.counts.users.toLocaleString()} <span className="small muted">(활성 {status.counts.activeUsers.toLocaleString()})</span></td></tr>
                <tr><th>글</th><td>{status.counts.posts.toLocaleString()} <span className="small muted">(발행 {status.counts.published.toLocaleString()} · 임시 {status.counts.drafts.toLocaleString()})</span></td></tr>
                <tr><th>휴지통</th><td>{status.counts.trash.toLocaleString()}</td></tr>
                <tr><th>댓글</th><td>{status.counts.comments.toLocaleString()}</td></tr>
                <tr><th>첨부</th><td>{status.counts.attachments.toLocaleString()} <span className="small muted">({formatFileSize(status.counts.attachmentBytes)})</span></td></tr>
              </tbody></table>
            </div>
          </div>

          <div className="card">
            <div className="card__head"><h3>자동 정리</h3><span className="small muted">매일 03:30 · {status.cleanup.enabled ? '사용' : '중지'}</span></div>
            <div className="card__body">
              <table className="kv"><tbody>
                <tr><th>휴지통 보존</th><td>{status.cleanup.trashRetentionDays}일</td></tr>
                <tr><th>고아 첨부 보존</th><td>{status.cleanup.orphanAttachmentHours}시간</td></tr>
                <tr><th>마지막 실행</th><td>{status.cleanup.last ? <>{formatDate(status.cleanup.last.finishedAt, true)} <span className="small muted">({status.cleanup.last.manual ? '수동' : '자동'})</span></> : <span className="muted">기동 후 실행 없음</span>}</td></tr>
                {status.cleanup.last && <tr><th>결과</th><td className="small">{status.cleanup.last.error ? <span style={{ color: 'var(--danger)' }}>{status.cleanup.last.error}</span> : cleanupSummary(status.cleanup.last)}</td></tr>}
              </tbody></table>
            </div>
          </div>

          <div className="card card--full">
            <div className="card__head"><h3>최근 오류 로그</h3><span className="small muted">WARN 이상 최근 {status.recentErrors.length}건 (메모리 보관, 재기동 시 초기화)</span></div>
            <div className="table-wrap">
              <table className="table">
                <thead><tr><th>시각</th><th>레벨</th><th>위치</th><th>메시지</th></tr></thead>
                <tbody>
                  {status.recentErrors.map((e, i) => (
                    <tr key={`${e.time}-${i}`}>
                      <td className="small muted" style={{ whiteSpace: 'nowrap' }}>{formatDate(e.time, true)}</td>
                      <td>{e.level === 'ERROR' ? <span className="chip chip--danger">ERROR</span> : <span className="chip chip--draft">{e.level}</span>}</td>
                      <td className="small"><code>{e.logger}</code></td>
                      <td className="small"><div className="log-msg" title={e.exception ? `${e.message}\n${e.exception}` : e.message}>{e.message}{e.exception && <span className="muted"> — {e.exception}</span>}</div></td>
                    </tr>
                  ))}
                  {status.recentErrors.length === 0 && <tr><td colSpan={4} className="empty">기동 후 오류·경고 로그가 없습니다.</td></tr>}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      )}

      {confirming && (
        <Modal title="지금 정리 실행" onClose={() => setConfirming(false)}
               footer={<><button type="button" className="btn" onClick={() => setConfirming(false)}>취소</button><button type="button" className="btn btn--danger" onClick={runCleanup} disabled={busy}>{busy ? '실행 중…' : '실행'}</button></>}>
          <p>휴지통에 {status?.cleanup.trashRetentionDays ?? 30}일 넘게 있던 글을 <strong>영구 삭제</strong>하고, 글에 연결되지 않은 채 {status?.cleanup.orphanAttachmentHours ?? 24}시간 지난 첨부 파일을 지웁니다. 되돌릴 수 없습니다.</p>
        </Modal>
      )}
    </div>
  )
}
