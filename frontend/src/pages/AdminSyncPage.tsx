import { useCallback, useEffect, useState } from 'react'
import { errorMessage, formatDate } from '../lib/api'
import { syncApi, type GithubInput, type SyncInfo, type SyncSettings } from '../lib/syncApi'
import { useToast } from '../lib/toast'

const INTERVALS: { v: number; label: string }[] = [
  { v: 0, label: '끔 (버튼으로만)' },
  { v: 15, label: '15분마다' },
  { v: 60, label: '1시간마다' },
  { v: 360, label: '6시간마다' },
  { v: 1440, label: '하루마다' },
]

const TRIGGER: Record<string, string> = { timer: '주기', button: '버튼', manual: '서버에서 직접' }

function Result({ text }: { text: string }) {
  if (!text) return <span className="muted">—</span>
  if (text.startsWith('OK')) return <span><span className="chip chip--success">정상</span> {text.replace(/^OK\s*/, '')}</span>
  if (text.startsWith('FAILED')) return <span><span className="chip chip--danger">실패</span> {text.replace(/^FAILED\s*/, '')}</span>
  return <span className="muted">{text}</span>
}

export default function AdminSyncPage() {
  const toast = useToast()
  const [info, setInfo] = useState<SyncInfo | null>(null)
  const [form, setForm] = useState<SyncSettings | null>(null)
  const [err, setErr] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [gh, setGh] = useState<GithubInput | null>(null)

  const load = useCallback(() => syncApi.get()
    .then((i) => {
      setInfo(i); setForm((f) => f ?? i.settings); setErr(null)
      setGh((g) => g ?? { programRepo: i.github.programRepo || 'vavibolivia/workblog', dataRepo: i.github.dataRepo, token: '' })
    })
    .catch((e) => setErr(errorMessage(e))), [])

  const running = !!info && (info.requested || info.github.pending || info.status?.state === 'running')
  useEffect(() => {
    load()
    const t = window.setInterval(load, running ? 3_000 : 30_000)
    return () => window.clearInterval(t)
  }, [load, running])

  const dirty = !!info && !!form && JSON.stringify(form) !== JSON.stringify(info.settings)

  const save = async () => {
    if (!form) return
    setBusy(true)
    try {
      await syncApi.save(form)
      toast('업데이트 설정을 저장했습니다.', 'success')
      await load()
    } catch (e) { toast(errorMessage(e), 'error') } finally { setBusy(false) }
  }

  const ghDirty = !!info && !!gh && (gh.token !== '' || gh.programRepo !== (info.github.programRepo || 'vavibolivia/workblog') || gh.dataRepo !== info.github.dataRepo)

  const saveGithub = async () => {
    if (!gh) return
    setBusy(true)
    try {
      const i = await syncApi.github(gh)
      setInfo(i)
      setGh({ ...gh, token: '' })
      toast('GitHub 설정을 넘겼습니다. 업데이트 장치가 적용하고 바로 확인합니다(결과는 아래).', 'success')
    } catch (e) { toast(errorMessage(e), 'error') } finally { setBusy(false) }
  }

  const runNow = async () => {
    setBusy(true)
    try {
      setInfo(await syncApi.run())
      toast('업데이트를 요청했습니다. 잠시 뒤 결과가 아래에 나옵니다.', 'success')
    } catch (e) { toast(errorMessage(e), 'error') } finally { setBusy(false) }
  }

  const st = info?.status

  return (
    <div className="shell shell--wide">
      <div className="page-head row row--between row--wrap">
        <div>
          <h2>업데이트</h2>
          <p>프로그램은 공개 저장소 릴리스에서, 데이터는 비공개 데이터 저장소 릴리스에서 받습니다. 데이터는 지우지 않고 더하며, 원본에서 바뀐 글·사용자는 원본 것으로 갱신합니다(이 서버에서 고친 내용은 글 이력에 남음).</p>
        </div>
        <div className="row">
          <button type="button" className="btn" onClick={load}>새로 고침</button>
          <button type="button" className="btn btn--primary" onClick={runNow} disabled={busy || running || !info?.installed}>{running ? '업데이트 중…' : '지금 업데이트'}</button>
        </div>
      </div>

      {err && <div className="alert alert--error" style={{ marginBottom: 16 }}>{err}</div>}
      {!info && !err && <div className="empty">불러오는 중…</div>}
      {info && !info.installed && (
        <div className="alert alert--warn" style={{ marginBottom: 16 }}>
          이 서버에는 업데이트 장치가 없습니다. <code>install.sh</code> 로 설치한 서버에서 쓸 수 있습니다. 설치한 뒤 이 화면의 GitHub 칸에 데이터 저장소와 토큰을 넣으면 됩니다.
        </div>
      )}

      {info && form && (
        <div className="status-grid">
          <div className="card">
            <div className="card__head"><h3>설정</h3>{dirty && <span className="small muted">저장하지 않음</span>}</div>
            <div className="card__body stack">
              <label className="field">
                <span className="field__label">주기</span>
                <select className="input" value={form.intervalMinutes} disabled={!info.installed}
                        onChange={(e) => setForm({ ...form, intervalMinutes: Number(e.target.value) })}>
                  {INTERVALS.map((i) => <option key={i.v} value={i.v}>{i.label}</option>)}
                </select>
              </label>
              <label className="row"><input type="checkbox" checked={form.program} disabled={!info.installed} onChange={(e) => setForm({ ...form, program: e.target.checked })} /> 프로그램 업데이트</label>
              <label className="row"><input type="checkbox" checked={form.data} disabled={!info.installed} onChange={(e) => setForm({ ...form, data: e.target.checked })} /> 데이터 업데이트</label>
              <div className="row">
                <button type="button" className="btn btn--primary" onClick={save} disabled={busy || !dirty || !info.installed}>저장</button>
                {dirty && <button type="button" className="btn" onClick={() => setForm(info.settings)}>되돌리기</button>}
              </div>
            </div>
          </div>

          {gh && (
            <div className="card">
              <div className="card__head"><h3>GitHub</h3>{info.github.pending ? <span className="small muted">적용 중…</span> : ghDirty && <span className="small muted">저장하지 않음</span>}</div>
              <div className="card__body stack">
                <label className="field">
                  <span className="field__label">프로그램 저장소 (공개)</span>
                  <input className="input" value={gh.programRepo} disabled={!info.installed} placeholder="계정/저장소"
                         onChange={(e) => setGh({ ...gh, programRepo: e.target.value })} />
                </label>
                <label className="field">
                  <span className="field__label">데이터 저장소 (비공개) — 비우면 데이터 받기 안 함</span>
                  <input className="input" value={gh.dataRepo} disabled={!info.installed} placeholder="예: vavibolivia/workblog-data"
                         onChange={(e) => setGh({ ...gh, dataRepo: e.target.value })} />
                </label>
                <label className="field">
                  <span className="field__label">데이터 저장소 토큰 {info.github.tokenSet ? <span className="chip chip--success">설정됨</span> : <span className="chip">없음</span>}</span>
                  <input className="input" type="password" autoComplete="off" value={gh.token} disabled={!info.installed || !gh.dataRepo}
                         placeholder={info.github.tokenSet ? '바꿀 때만 입력' : 'github_pat_… (Contents 읽기 전용)'}
                         onChange={(e) => setGh({ ...gh, token: e.target.value })} />
                  <span className="small muted">GitHub › Settings › Developer settings › Fine-grained tokens 에서 데이터 저장소만, Contents: Read-only 로 만듭니다. 토큰은 서버의 root 전용 설정 파일에만 저장되고 화면에는 다시 나오지 않습니다.</span>
                </label>
                <div className="row">
                  <button type="button" className="btn btn--primary" onClick={saveGithub} disabled={busy || !ghDirty || !info.installed || info.github.pending}>저장하고 확인</button>
                  {ghDirty && <button type="button" className="btn" onClick={() => setGh({ programRepo: info.github.programRepo || 'vavibolivia/workblog', dataRepo: info.github.dataRepo, token: '' })}>되돌리기</button>}
                </div>
              </div>
            </div>
          )}

          <div className="card">
            <div className="card__head"><h3>마지막 업데이트</h3>{st && <span className="small muted">{st.state === 'running' ? '진행 중' : '끝남'} · {TRIGGER[st.trigger] ?? st.trigger}</span>}</div>
            <div className="card__body">
              <table className="kv"><tbody>
                <tr><th>지금 버전</th><td><code>{info.appVersion}</code></td></tr>
                <tr><th>시각</th><td>{st ? formatDate(st.at, true) : <span className="muted">아직 없음</span>}</td></tr>
                <tr><th>프로그램</th><td className="small">{st ? <><Result text={st.program.result} />{st.program.latest && <span className="muted"> · 최신 릴리스 {st.program.latest}</span>}</> : '—'}</td></tr>
                <tr><th>데이터</th><td className="small">{st ? <><Result text={st.data.result} />{st.data.tag && <span className="muted"> · {st.data.tag}</span>}</> : '—'}</td></tr>
              </tbody></table>
            </div>
          </div>

          {st && st.log.length > 0 && (
            <div className="card card--full">
              <div className="card__head"><h3>기록</h3></div>
              <div className="card__body"><pre className="small" style={{ margin: 0, whiteSpace: 'pre-wrap' }}>{st.log.join('\n')}</pre></div>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
