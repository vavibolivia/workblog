import { useEffect, useRef, useState } from 'react'
import { editorApi } from '../lib/editorApi'

/**
 * draw.io(diagrams.net) 편집기를 embed 모드(iframe + postMessage, proto=json)로 띄운다.
 * 저장하면 XML 이 들어 있는 PNG(xmlpng)를 돌려주므로, 그 PNG 를 사진처럼 올리고 다시 열면 편집을 이어 갈 수 있다.
 * 편집기 주소는 서버 설정(DRAWIO_URL, 기본 https://embed.diagrams.net). 브라우저가 그 주소를 열 수 있어야 한다.
 */
export default function DrawioDialog({ initial, title, onSave, onClose }:
  { initial?: { xmlpng?: string; xml?: string }; title?: string; onSave: (pngDataUri: string) => Promise<void> | void; onClose: () => void }) {
  const frame = useRef<HTMLIFrameElement>(null)
  const [url, setUrl] = useState<string | null>(null)
  const [status, setStatus] = useState<string>('편집기를 불러오는 중…')
  const [ready, setReady] = useState(false)
  const readyRef = useRef(false) // 타임아웃 콜백이 최신 로드 상태를 보도록(클로저의 ready 는 오래된 값)
  const [failed, setFailed] = useState(false)
  const saving = useRef(false)

  useEffect(() => {
    editorApi.options().then((o) => setUrl(o.drawioUrl)).catch(() => setUrl('https://embed.diagrams.net'))
  }, [])

  useEffect(() => {
    if (!url) return
    const timer = window.setTimeout(() => { if (!readyRef.current) { setFailed(true); setStatus('편집기를 열지 못했습니다. 네트워크(인터넷 또는 사내 draw.io 서버) 연결을 확인하세요.') } }, 20_000)
    const post = (msg: Record<string, unknown>) => frame.current?.contentWindow?.postMessage(JSON.stringify(msg), '*')
    const onMessage = (e: MessageEvent) => {
      if (frame.current == null || e.source !== frame.current.contentWindow || typeof e.data !== 'string' || !e.data.startsWith('{')) return
      let msg: { event?: string; data?: string; xml?: string; message?: string }
      try { msg = JSON.parse(e.data) } catch { return }
      switch (msg.event) {
        case 'init':
          readyRef.current = true; setReady(true); setStatus('')
          if (initial?.xmlpng) post({ action: 'load', xmlpng: initial.xmlpng, autosave: 0, title: title ?? '다이어그램' })
          else post({ action: 'load', xml: initial?.xml ?? '', autosave: 0, title: title ?? '다이어그램' })
          break
        case 'save':
          if (saving.current) return
          saving.current = true
          setStatus('저장 중…')
          post({ action: 'export', format: 'xmlpng', spin: '저장 중', scale: 2, border: 10 })
          break
        case 'export':
          if (msg.data) {
            Promise.resolve(onSave(msg.data)).then(() => onClose()).catch((err: unknown) => {
              saving.current = false
              setStatus(err instanceof Error ? err.message : '저장하지 못했습니다.')
            })
          } else { saving.current = false; setStatus('') }
          break
        case 'exit':
          onClose()
          break
        default:
          break
      }
    }
    window.addEventListener('message', onMessage)
    return () => { window.removeEventListener('message', onMessage); window.clearTimeout(timer) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [url])

  void ready
  const src = url ? `${url.replace(/\/+$/, '')}/?embed=1&proto=json&spin=1&libraries=1&saveAndExit=1&noSaveBtn=0&noExitBtn=0&lang=ko&ui=kennedy` : undefined

  return (
    <div className="modal-backdrop drawio-backdrop" role="dialog" aria-modal="true" aria-label="다이어그램 편집">
      <div className="drawio">
        <div className="drawio__bar">
          <strong>다이어그램 {initial ? '편집' : '만들기'}</strong>
          <span className="small muted">{status || '편집기 안의 저장(Save) 버튼을 누르면 글에 들어갑니다.'}</span>
          <button type="button" className="btn btn--sm" onClick={onClose}>닫기</button>
        </div>
        {src && !failed && <iframe ref={frame} className="drawio__frame" src={src} title="draw.io" />}
        {failed && <div className="drawio__fail"><p>{status}</p><p className="small muted">관리자는 서버 설정 DRAWIO_URL 로 사내 draw.io 주소를 지정할 수 있습니다.</p></div>}
      </div>
    </div>
  )
}
