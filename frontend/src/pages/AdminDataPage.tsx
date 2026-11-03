import { useCallback, useEffect, useRef, useState } from 'react'
import { errorMessage, formatDate } from '../lib/api'
import {
  actionLabel, dataMoveApi, entityLabel,
  type DataMoveInfo, type ImportItem, type ImportReport, type TransferRow, type UploadResult,
} from '../lib/dataMoveApi'
import Modal from '../components/Modal'

/** ISO → datetime-local 값(이 브라우저 시간대) */
function toLocalInput(iso: string | null): string {
  if (!iso) return ''
  const d = new Date(iso)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`
}

function bytes(n: number): string {
  if (n >= 1024 * 1024 * 1024) return `${(n / 1024 / 1024 / 1024).toFixed(2)} GB`
  if (n >= 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MB`
  return `${Math.max(1, Math.round(n / 1024))} KB`
}

const transferLabel = (t: TransferRow) =>
  t.direction === 'EXPORT' ? '내보내기' : t.action === 'APPLY' ? '가져오기 적용' : '가져오기 미리 보기'

/** 기록 표에 처음 보이는 줄 수 */
const HISTORY_FIRST = 10

/** 관리자: 다른 망으로 옮기기 — 덤프 내보내기(전체·업데이트)와 가져오기(미리 보기 → 적용). */
export default function AdminDataPage() {
  const [info, setInfo] = useState<DataMoveInfo | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const [full, setFull] = useState(false)
  const [allHistory, setAllHistory] = useState(false)
  const [since, setSince] = useState('')
  const [markBaseline, setMarkBaseline] = useState(true)
  const [exporting, setExporting] = useState(false)
  const [file, setFile] = useState<File | null>(null)
  const [progress, setProgress] = useState<number | null>(null)
  const [upload, setUpload] = useState<UploadResult | null>(null)
  const [result, setResult] = useState<ImportReport | null>(null)
  const [applying, setApplying] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const [importErr, setImportErr] = useState<string | null>(null)
  const [detail, setDetail] = useState<{ row: TransferRow; report: ImportReport | null } | null>(null)
  const [fileKey, setFileKey] = useState(0)
  const uploadRef = useRef<UploadResult | null>(null)
  const timer = useRef<number | undefined>(undefined)
  useEffect(() => { uploadRef.current = upload }, [upload])
  useEffect(() => () => window.clearTimeout(timer.current), [])

  const load = useCallback(async () => {
    try {
      const i = await dataMoveApi.info()
      setInfo(i)
      if (!i.lastExportAt) setFull(true)
      setSince((s) => s || toLocalInput(i.lastExportAt))
    } catch (e) { setErr(errorMessage(e, '정보를 불러오지 못했습니다.')) }
  }, [])
  useEffect(() => { load() }, [load])

  const doExport = async () => {
    setErr(null)
    if (!full && !since) { setErr('업데이트 덤프는 기준 시각이 필요합니다.'); return }
    setExporting(true)
    try {
      const t = await dataMoveApi.ticket({ full, since: full ? undefined : new Date(since).toISOString(), markBaseline })
      // 큰 파일도 메모리에 담지 않도록 브라우저가 주소로 바로 내려받는다(1회용 표).
      // 숨은 iframe 으로 받아 실패(JSON 오류)해도 화면을 떠나지 않는다. 결과는 아래 기록에 남는다.
      const f = document.createElement('iframe')
      f.style.display = 'none'
      f.src = dataMoveApi.downloadUrl(t.ticket)
      document.body.appendChild(f)
      window.clearTimeout(timer.current)
      timer.current = window.setTimeout(() => { load(); setExporting(false); f.remove() }, 3000)
    } catch (e) { setErr(errorMessage(e, '내보내기를 시작하지 못했습니다.')); setExporting(false) }
  }

  const doUpload = async () => {
    if (!file) return
    setImportErr(null); setUpload(null); setResult(null); setProgress(0)
    try {
      setUpload(await dataMoveApi.upload(file, setProgress))
      load()
    } catch (e) { setImportErr(errorMessage(e, '덤프를 올리지 못했습니다.')) } finally { setProgress(null) }
  }

  const doApply = async () => {
    if (!upload) return
    setConfirming(false); setApplying(true); setImportErr(null)
    try {
      setResult(await dataMoveApi.apply(upload.uploadId))
      setUpload(null); setFile(null); setFileKey((k) => k + 1)
      load()
    } catch (e) { setImportErr(errorMessage(e, '적용하지 못했습니다.')) } finally { setApplying(false) }
  }

  const cancel = async () => {
    if (upload) await dataMoveApi.discard(upload.uploadId).catch(() => null)
    setUpload(null); setFile(null); setFileKey((k) => k + 1)
  }

  const pickFile = (f: File | null) => {
    // 미리 보기만 하고 버린 덤프는 서버 임시 파일도 지운다
    const prev = uploadRef.current
    if (prev) dataMoveApi.discard(prev.uploadId).catch(() => null)
    setFile(f); setUpload(null); setResult(null)
  }

  const openDetail = async (row: TransferRow) => {
    try {
      const d = await dataMoveApi.transfer(row.id)
      setDetail({ row, report: d.report && 'items' in d.report ? d.report as ImportReport : null })
    } catch (e) { setErr(errorMessage(e)) }
  }

  return (
    <div className="shell shell--wide">
      <div className="page-head">
        <h2>데이터 이전</h2>
        <p>이 서버의 데이터를 덤프(.zip)로 받아 다른 망의 WorkBlog 에 넣습니다. 처음 한 번은 전체 덤프, 그 뒤로는 바뀐 것만 담은 업데이트 덤프를 받아 넣으면 됩니다. 가져오기는 받는 서버의 데이터를 지우지 않고 더하거나 갱신합니다.</p>
      </div>
      <div className="alert alert--warn" style={{ marginBottom: 16 }}>덤프에는 모든 글·첨부 원본·사용자 비밀번호 해시·API 토큰 해시가 그대로 들어 있습니다. 사내 반출 절차에 따라 옮기고 보관하세요.</div>
      {err && <div className="alert alert--error" style={{ marginBottom: 16 }}>{err}</div>}

      <div className="status-grid dm-grid">
        <div className="card">
          <div className="card__head"><h3>1. 내보내기 (덤프 받기)</h3></div>
          <div className="card__body dm-form">
            <div className="segmented" role="radiogroup" aria-label="덤프 종류">
              <button type="button" role="radio" aria-checked={full} className={full ? 'is-on' : undefined} onClick={() => setFull(true)}>전체 덤프</button>
              <button type="button" role="radio" aria-checked={!full} className={!full ? 'is-on' : undefined} onClick={() => setFull(false)}>업데이트 덤프</button>
            </div>
            {!full && (
              <div className="field">
                <label htmlFor="dm-since">기준 시각 (이 시각 이후 바뀐 것만)</label>
                <input id="dm-since" type="datetime-local" className="input" value={since} onChange={(e) => setSince(e.target.value)} />
                <span className="small muted">기본값은 직전 내보내기 시각{info?.lastExportAt ? ` (${formatDate(info.lastExportAt, true)})` : ' (기록 없음)'}입니다. 안전을 위해 10분 앞부터 겹쳐 담습니다.</span>
              </div>
            )}
            <label className="check">
              <input type="checkbox" checked={markBaseline} onChange={(e) => setMarkBaseline(e.target.checked)} />
              다음 업데이트 덤프의 기준 시각으로 기록
            </label>
            <div><button type="button" className="btn btn--primary" onClick={doExport} disabled={exporting}>{exporting ? '내려받는 중…' : '덤프 받기'}</button></div>
          </div>
        </div>

        <div className="card">
          <div className="card__head"><h3>이 서버</h3></div>
          <div className="card__body">
            <table className="kv"><tbody>
              <tr><th>인스턴스 ID</th><td><code>{info?.instanceId ?? '…'}</code></td></tr>
              <tr><th>직전 내보내기(기준)</th><td>{info?.lastExportAt ? formatDate(info.lastExportAt, true) : <span className="muted">없음</span>}</td></tr>
              <tr><th>올리기 한도</th><td>{info ? `${info.importMaxMb.toLocaleString()} MB` : '…'}</td></tr>
            </tbody></table>
          </div>
        </div>

        <div className="card card--full">
          <div className="card__head"><h3>2. 가져오기 (덤프 넣기)</h3></div>
          <div className="card__body dm-form">
            <p className="small muted" style={{ margin: 0 }}>다른 서버에서 받은 덤프를 올리면 먼저 미리 보기(무엇이 추가·갱신·충돌하는지)를 보여 줍니다. 미리 보기는 아무것도 바꾸지 않습니다. [적용]을 눌러야 반영됩니다.</p>
            <div className="row row--wrap">
              <input key={fileKey} id="dm-file" type="file" accept=".zip,application/zip" hidden disabled={progress !== null || applying} onChange={(e) => pickFile(e.target.files?.[0] ?? null)} />
              <label htmlFor="dm-file" className="btn" aria-disabled={progress !== null || applying}>파일 고르기</label>
              <span className="small muted ellipsis" style={{ maxWidth: 320 }}>{file?.name ?? '고른 파일 없음 (.zip)'}</span>
              <button type="button" className="btn" onClick={doUpload} disabled={!file || progress !== null || applying}>
                {progress !== null ? (progress < 100 ? `올리는 중 ${progress}%` : '검사하는 중…') : '올리고 미리 보기'}
              </button>
            </div>
            {importErr && <div className="alert alert--error">{importErr}</div>}

            {upload && (
              <>
                <table className="kv dm-manifest"><tbody>
                  <tr><th>덤프</th><td><code>{upload.manifest.dumpName}</code></td></tr>
                  <tr><th>원본 인스턴스</th><td><code>{upload.manifest.sourceInstanceId}</code></td></tr>
                  <tr><th>종류</th><td>{upload.manifest.kind === 'FULL' ? '전체' : `업데이트 (${formatDate(upload.manifest.since, true)} 이후)`}</td></tr>
                  <tr><th>내보낸 시각</th><td>{formatDate(upload.manifest.exportedAt, true)}</td></tr>
                  <tr><th>크기(풀었을 때)</th><td>{bytes(upload.manifest.totalBytes)}</td></tr>
                </tbody></table>
                <Report report={upload.preview} title="미리 보기" />
                <div className="row">
                  <button type="button" className="btn" onClick={cancel} disabled={applying}>취소</button>
                  <button type="button" className="btn btn--primary" onClick={() => setConfirming(true)} disabled={applying || upload.preview.status !== 'OK'}>
                    {applying ? '적용하는 중…' : '적용'}
                  </button>
                </div>
              </>
            )}
            {result && <Report report={result} title="적용 결과" />}
          </div>
        </div>

        <div className="card card--full">
          <div className="card__head"><h3>기록</h3>{info && <span className="small muted">전체 {info.history.length.toLocaleString()}건</span>}</div>
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>시각</th><th>작업</th><th>덤프</th><th>한 사람</th><th>상태</th><th>요약</th></tr></thead>
              <tbody>
                {info?.history.slice(0, allHistory ? undefined : HISTORY_FIRST).map((t) => (
                  <tr key={t.id}>
                    <td className="small nowrap">{formatDate(t.startedAt, true)}</td>
                    <td className="nowrap">{transferLabel(t)}{t.direction === 'EXPORT' && t.baseline && <span className="small muted"> · 기준</span>}</td>
                    <td><button type="button" className="link-btn" onClick={() => openDetail(t)}>{t.dumpName}</button></td>
                    <td className="nowrap">{t.actorName}</td>
                    <td><span className={`chip chip--xs ${t.status === 'OK' ? 'chip--neutral' : t.status === 'FAILED' ? 'chip--danger' : 'chip--neutral'}`}>{t.status === 'OK' ? '완료' : t.status === 'FAILED' ? '실패' : '진행 중'}</span></td>
                    <td className="small">{t.summary}</td>
                  </tr>
                ))}
                {info && info.history.length === 0 && <tr><td colSpan={6} className="empty">아직 기록이 없습니다.</td></tr>}
              </tbody>
            </table>
          </div>
          {info && info.history.length > HISTORY_FIRST && (
            <div className="dm-history__more">
              <button type="button" className="btn btn--sm" onClick={() => setAllHistory((v) => !v)}>
                {allHistory ? '접기' : `더 보기 (${(info.history.length - HISTORY_FIRST).toLocaleString()}건)`}
              </button>
            </div>
          )}
        </div>
      </div>

      {confirming && upload && (
        <Modal title="가져오기 적용" onClose={() => setConfirming(false)}
          footer={<><button type="button" className="btn" onClick={() => setConfirming(false)}>취소</button><button type="button" className="btn btn--primary" onClick={doApply}>적용</button></>}>
          <p><code>{upload.manifest.dumpName}</code> 을(를) 이 서버에 반영합니다. 기존 데이터는 지우지 않습니다. 이 서버에서 고친 글은 덮어쓰지 않고 충돌로 남깁니다.</p>
          <p className="small muted">적용 전에 서버 백업(backup.sh)을 해 두기를 권합니다.</p>
        </Modal>
      )}

      {detail && (
        <Modal title={transferLabel(detail.row)} wide onClose={() => setDetail(null)}>
          <p className="small muted" style={{ marginTop: 0 }}><code>{detail.row.dumpName}</code> · {detail.row.actorName} · {formatDate(detail.row.startedAt, true)}</p>
          {detail.report ? <Report report={detail.report} title="보고서" /> : <p>{detail.row.summary}</p>}
        </Modal>
      )}
    </div>
  )
}

function Report({ report, title }: { report: ImportReport; title: string }) {
  const [onlyIssues, setOnlyIssues] = useState(true)
  const items: ImportItem[] = onlyIssues ? report.items.filter((i) => i.action === 'CONFLICT' || i.action === 'FAIL') : report.items
  const entities = Object.keys(report.counts)
  return (
    <div className="dm-report">
      <h4>{title} {report.status === 'FAILED' ? <span className="chip chip--xs chip--danger">실패</span> : <span className="chip chip--xs chip--success">{report.applied ? '반영됨' : '이상 없음'}</span>}</h4>
      {report.status === 'FAILED' && (
        <div className="alert alert--error">
          <strong>{entityLabel[report.failedStage ?? ''] ?? report.failedStage} 단계에서 멈췄습니다.</strong> {report.error}
          {report.applied && <div className="small">반영 완료 단계: {report.completedStages.map((s) => entityLabel[s] ?? s).join(' · ') || '없음'}{report.lastCommitted && ` · 마지막 커밋 ${report.lastCommitted}`}. 원인을 고친 뒤 같은 덤프를 다시 넣으면 이어서 반영됩니다(이미 넣은 것은 건너뜀).</div>}
        </div>
      )}
      <div className="table-wrap">
        <table className="table dm-counts">
          <thead><tr><th>항목</th><th>추가</th><th>갱신</th><th>건너뜀</th><th>충돌</th><th>실패</th></tr></thead>
          <tbody>
            {entities.map((e) => {
              const c = report.counts[e]
              return (
                <tr key={e}>
                  <td>{entityLabel[e] ?? e}</td>
                  <td>{c.created.toLocaleString()}</td>
                  <td>{c.updated.toLocaleString()}</td>
                  <td className="muted">{c.skipped.toLocaleString()}</td>
                  <td className={c.conflict ? 'dm-bad' : undefined}>{c.conflict.toLocaleString()}</td>
                  <td className={c.failed ? 'dm-bad' : undefined}>{c.failed.toLocaleString()}</td>
                </tr>
              )
            })}
            {entities.length === 0 && <tr><td colSpan={6} className="empty">담긴 것이 없습니다.</td></tr>}
          </tbody>
        </table>
      </div>
      <div className="row row--between">
        <strong className="small">목록 {items.length.toLocaleString()}건{report.itemsOmitted > 0 && ` (그 밖 ${report.itemsOmitted.toLocaleString()}건 생략)`}</strong>
        <label className="check"><input type="checkbox" checked={onlyIssues} onChange={(e) => setOnlyIssues(e.target.checked)} /> 충돌·실패만</label>
      </div>
      {items.length > 0 && (
        <div className="table-wrap dm-items">
          <table className="table">
            <thead><tr><th>항목</th><th>결과</th><th>이름</th><th>원본 → 대상</th><th>사유</th></tr></thead>
            <tbody>
              {items.map((i, k) => (
                <tr key={k}>
                  <td className="nowrap">{entityLabel[i.entity] ?? i.entity}</td>
                  <td><span className={`chip chip--xs ${i.action === 'CONFLICT' || i.action === 'FAIL' ? 'chip--danger' : i.action === 'SKIP' ? 'chip--neutral' : 'chip--success'}`}>{actionLabel[i.action]}</span></td>
                  <td>{i.entity === 'posts' && i.targetId && i.targetId > 0 ? <a href={`/posts/${i.targetId}`} target="_blank" rel="noreferrer">{i.label}</a> : i.label}</td>
                  <td className="small nowrap">#{i.sourceId ?? '-'} → {i.targetId && i.targetId > 0 ? `#${i.targetId}` : i.action === 'CREATE' ? '새로' : '-'}</td>
                  <td className="small">{i.reason}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
