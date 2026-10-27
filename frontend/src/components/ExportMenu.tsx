import { useEffect, useRef, useState } from 'react'
import { api, blobErrorMessage, saveBlob, type ExportFormat } from '../lib/api'
import { useToast } from '../lib/toast'

const ITEMS: { key: 'pdf' | ExportFormat; label: string; hint: string }[] = [
  { key: 'pdf', label: 'PDF (인쇄)', hint: '브라우저 인쇄 창에서 PDF 로 저장' },
  { key: 'html', label: 'HTML 파일', hint: '.html' },
  { key: 'md', label: 'Markdown 파일', hint: '.md' },
  { key: 'docx', label: 'Word 파일', hint: '.docx' },
]

const EXT: Record<ExportFormat, string> = { html: 'html', md: 'md', docx: 'docx' }

/** 파일 이름에 못 쓰는 글자를 빼고 길이를 줄인다(서버가 이름을 안 줄 때만 쓴다). */
function safeName(title: string) {
  const s = title.replace(/[\\/:*?"<>|]+/g, ' ').replace(/\s+/g, ' ').trim()
  return (s || '글').slice(0, 80)
}

/** 글 상세 헤더의 "내보내기" 드롭다운(R12). PDF 는 인쇄, 나머지는 서버가 만든 파일을 내려받는다. */
export default function ExportMenu({ postId, title }: { postId: number; title: string }) {
  const toast = useToast()
  const [open, setOpen] = useState(false)
  const [withAttachments, setWithAttachments] = useState(true)
  const [busy, setBusy] = useState<ExportFormat | null>(null)
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const onDoc = (e: MouseEvent) => { if (!ref.current?.contains(e.target as Node)) setOpen(false) }
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false) }
    document.addEventListener('mousedown', onDoc)
    document.addEventListener('keydown', onKey)
    return () => { document.removeEventListener('mousedown', onDoc); document.removeEventListener('keydown', onKey) }
  }, [open])

  const printPdf = () => {
    setOpen(false)
    // 인쇄 파일 이름이 글 제목이 되도록 document.title 을 잠시 바꾼다.
    const prev = document.title
    document.title = safeName(title)
    if (!withAttachments) document.body.classList.add('print-no-attach')
    const restore = () => { document.title = prev; document.body.classList.remove('print-no-attach'); window.removeEventListener('afterprint', restore) }
    window.addEventListener('afterprint', restore)
    window.setTimeout(() => { window.print(); window.setTimeout(restore, 1000) }, 50)
  }

  const download = async (format: ExportFormat) => {
    setBusy(format)
    try {
      const { blob, filename } = await api.posts.export(postId, format, withAttachments)
      saveBlob(blob, filename ?? `${safeName(title)}.${EXT[format]}`)
      setOpen(false)
    } catch (e) { toast(await blobErrorMessage(e, '내보내지 못했습니다.'), 'error') } finally { setBusy(null) }
  }

  return (
    <div className="export" ref={ref}>
      <button type="button" className="btn btn--sm" aria-haspopup="menu" aria-expanded={open} disabled={busy !== null} onClick={() => setOpen((v) => !v)}>
        {busy ? '내보내는 중…' : '내보내기 ▾'}
      </button>
      {open && (
        <div className="menu export__menu" role="menu">
          {ITEMS.map((it) => (
            <button key={it.key} type="button" role="menuitem" disabled={busy !== null}
                    onClick={() => it.key === 'pdf' ? printPdf() : download(it.key)}>
              <span className="grow">{it.label}</span>
              <span className="small muted">{it.hint}</span>
            </button>
          ))}
          <div className="menu__foot">
            <label className="check">
              <input type="checkbox" checked={withAttachments} onChange={(e) => setWithAttachments(e.target.checked)} />
              첨부 목록 포함
            </label>
          </div>
        </div>
      )}
    </div>
  )
}
