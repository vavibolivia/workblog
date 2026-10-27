import { useEffect, useState } from 'react'
import { NavLink, useParams } from 'react-router-dom'
import { api, errorMessage, type GuideDoc } from '../lib/api'
import Markdown from '../components/Markdown'

const DEFAULT_DOC = 'cli'
const FALLBACK_DOCS: GuideDoc[] = [
  { id: 'api', title: 'API 사용 안내' },
  { id: 'cli', title: 'CLI 가이드' },
]

/** 도움말: 왼쪽 문서 목록, 오른쪽에 서버가 주는 마크다운 문서. 기본 문서는 CLI 가이드. */
export default function HelpPage() {
  const { doc } = useParams()
  // 예전 이름(claude-code) 링크는 CLI 가이드로
  const docId = doc === 'claude-code' ? 'cli' : (doc ?? DEFAULT_DOC)
  const [docs, setDocs] = useState<GuideDoc[]>(FALLBACK_DOCS)
  const [body, setBody] = useState<string | null>(null)
  const [err, setErr] = useState<string | null>(null)

  useEffect(() => {
    api.guide.list().then((d) => { if (d.length > 0) setDocs(d) }).catch(() => null)
  }, [])

  useEffect(() => {
    setBody(null); setErr(null)
    api.guide.get(docId).then(setBody).catch((e) => setErr(errorMessage(e, '문서를 불러오지 못했습니다.')))
  }, [docId])

  const current = docs.find((d) => d.id === docId)

  return (
    <div className="help">
      <nav className="help__nav" aria-label="도움말 문서">
        <h4>도움말</h4>
        {docs.map((d) => (
          <NavLink key={d.id} to={`/help/${d.id}`} className={({ isActive }) => (isActive || (d.id === DEFAULT_DOC && !doc)) ? 'active' : ''} end>
            {d.title}
          </NavLink>
        ))}
        <a href="/swagger-ui/index.html" target="_blank" rel="noopener noreferrer">
          <span>Swagger UI</span><span className="ext">대화형 API 문서 · 새 창</span>
        </a>
      </nav>
      <main className="help__doc">
        {err && <div className="alert alert--error">{err}</div>}
        {!body && !err && <div className="empty"><div className="spinner" style={{ margin: '0 auto' }} /></div>}
        {body !== null && !err && (
          <article aria-label={current?.title ?? docId}>
            <Markdown>{body}</Markdown>
          </article>
        )}
      </main>
    </div>
  )
}
