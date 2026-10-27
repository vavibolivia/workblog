import { useEffect, useRef, useState } from 'react'
import { EditorContent, useEditor, type Editor } from '@tiptap/react'
import StarterKit from '@tiptap/starter-kit'
import { TextStyleKit } from '@tiptap/extension-text-style'
import TextAlign from '@tiptap/extension-text-align'
import Subscript from '@tiptap/extension-subscript'
import Superscript from '@tiptap/extension-superscript'
import { TableKit } from '@tiptap/extension-table'
import Placeholder from '@tiptap/extension-placeholder'
import { Indent } from './Indent'
import { CELL_COLORS, StyledTableCell, StyledTableHeader } from './TableCellStyle'
import { FigureImage } from './FigureImage'
import DrawioDialog from './DrawioDialog'
import { IcDiagram, IcDocument, IcEraser, IcExcel, IcImage, IcLink, IcPaperclip, IcRedo, IcRule, IcTable, IcUndo } from '../components/Icons'
import { dataUriToFile, IMPORT_ACCEPT, toDataUri, type ConvertResponse } from '../lib/editorApi'
import './editor.css'

export interface UploadedFile { id: string; name: string; url: string; kind: 'IMAGE' | 'EXCEL' | 'FILE'; size: number }

interface Props {
  value: string
  onChange: (html: string) => void
  /** 파일 업로드. 이미지면 본문에 삽입, 아니면 첨부 목록에 추가된다. */
  onUpload: (file: File) => Promise<UploadedFile>
  onAttached?: (file: UploadedFile) => void
  /** 문서 파일(HTML·Word·Excel·CSV·Markdown·텍스트) → 본문 변환. 있으면 툴바에 '문서' 버튼이 생긴다. */
  onImportDocument?: (file: File) => Promise<ConvertResponse>
  /** 가져온 문서의 제목(제목 칸이 비어 있을 때 채우라고 알려 준다) */
  onImportedTitle?: (title: string) => void
  placeholder?: string
  /** X5: 본문 안에서 Ctrl+S(임시 저장) / Ctrl+Enter(발행) */
  onSaveShortcut?: () => void
  onPublishShortcut?: () => void
}

type DrawioState = { initial?: { xmlpng?: string; xml?: string }; apply?: (src: string) => void } | null

const FONTS: [string, string][] = [
  ['기본', ''], ['프리텐다드', 'Pretendard Variable, Pretendard, sans-serif'], ['나눔고딕', "'Nanum Gothic', sans-serif"],
  ['나눔명조', "'Nanum Myeongjo', serif"], ['맑은 고딕', "'Malgun Gothic', sans-serif"], ['돋움', 'Dotum, sans-serif'],
  ['굴림', 'Gulim, sans-serif'], ['바탕', 'Batang, serif'], ['Arial', 'Arial, sans-serif'], ['Georgia', 'Georgia, serif'], ['Courier New', "'Courier New', monospace"],
]
const SIZES = [11, 13, 15, 16, 19, 24, 28, 34, 38]
const LINE_HEIGHTS: [string, string][] = [['줄간격', ''], ['100%', '1.2'], ['150%', '1.5'], ['180%', '1.8'], ['200%', '2']]
const COLORS = [
  '#000000', '#1b2430', '#4a5568', '#7a8699', '#a3adbd', '#cfd7e2', '#ffffff',
  '#d7263d', '#f0523f', '#f59e0b', '#fde047', '#15803d', '#22c55e', '#0b2a5b',
  '#2f9be3', '#60a5fa', '#7c3aed', '#c026d3', '#ec4899', '#92400e', '#78716c',
]
const HIGHLIGHTS = ['transparent', '#fff2a8', '#fde68a', '#fecaca', '#bbf7d0', '#bfdbfe', '#ddd6fe', '#fbcfe8', '#e5e7eb']

/** 링크 넣기/고치기. 빈 값이면 링크 해제. 툴바 버튼과 Ctrl+K 가 같이 쓴다. */
function promptLink(editor: Editor) {
  const prev = (editor.getAttributes('link') as { href?: string }).href ?? ''
  const url = window.prompt('링크 주소', prev || 'https://')
  if (url === null) return
  const href = url.trim()
  if (!href || href === 'https://') { editor.chain().focus().extendMarkRange('link').unsetLink().run(); return }
  editor.chain().focus().extendMarkRange('link').setLink({ href: /^(https?:\/\/|mailto:|\/)/i.test(href) ? href : `https://${href}` }).run()
}

export default function RichEditor({ value, onChange, onUpload, onAttached, onImportDocument, onImportedTitle, placeholder = '내용을 입력하세요', onSaveShortcut, onPublishShortcut }: Props) {
  // editorProps 는 처음 한 번만 잡히므로 최신 콜백은 ref 로 읽는다
  const keysRef = useRef({ onSaveShortcut, onPublishShortcut })
  keysRef.current = { onSaveShortcut, onPublishShortcut }
  const fileInput = useRef<HTMLInputElement>(null)
  const excelInput = useRef<HTMLInputElement>(null)
  const docInput = useRef<HTMLInputElement>(null)
  const drawioFileInput = useRef<HTMLInputElement>(null)
  const [drawio, setDrawio] = useState<DrawioState>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const [tick, setTick] = useState(0)
  const editorRef = useRef<Editor | null>(null)

  const editor = useEditor({
    extensions: [
      StarterKit.configure({
        heading: { levels: [1, 2, 3] },
        link: { openOnClick: false, autolink: true, defaultProtocol: 'https', HTMLAttributes: { target: '_blank', rel: 'noopener noreferrer' } },
        codeBlock: { HTMLAttributes: { class: 'code' } },
      }),
      TextStyleKit,
      TextAlign.configure({ types: ['heading', 'paragraph'], alignments: ['left', 'center', 'right', 'justify'] }),
      Subscript, Superscript,
      TableKit.configure({ table: { resizable: true, HTMLAttributes: { class: 'rich-table' } }, tableCell: false, tableHeader: false }),
      StyledTableCell, StyledTableHeader,
      Indent,
      FigureImage.configure({
        onEditDiagram: (src, apply) => {
          setBusy('다이어그램을 여는 중…')
          toDataUri(src).then((xmlpng) => setDrawio({ initial: { xmlpng }, apply }))
            .catch((e: unknown) => window.alert(e instanceof Error ? e.message : '다이어그램을 열지 못했습니다.'))
            .finally(() => setBusy(null))
        },
      }),
      Placeholder.configure({ placeholder }),
    ],
    content: value,
    editorProps: {
      attributes: { class: 'rich rich--edit', spellcheck: 'false' },
      handleDrop: (_view, event) => { const files = event.dataTransfer?.files; if (files?.length) { event.preventDefault(); void handleFiles(Array.from(files)); return true } return false },
      handlePaste: (_view, event) => { const files = Array.from(event.clipboardData?.files ?? []); if (files.length) { event.preventDefault(); void handleFiles(files); return true } return false },
      handleKeyDown: (_view, event) => {
        if ((event.ctrlKey || event.metaKey) && !event.shiftKey && !event.altKey && event.key.toLowerCase() === 'k') {
          event.preventDefault(); promptLink(editorRef.current as Editor); return true
        }
        // Ctrl+S / Ctrl+Enter: 기본 동작(브라우저 저장·줄바꿈)을 막고 화면의 저장·발행을 부른다
        if ((event.ctrlKey || event.metaKey) && !event.shiftKey && !event.altKey) {
          const k = keysRef.current
          if (event.key.toLowerCase() === 's' && k.onSaveShortcut) { event.preventDefault(); k.onSaveShortcut(); return true }
          if (event.key === 'Enter' && k.onPublishShortcut) { event.preventDefault(); k.onPublishShortcut(); return true }
        }
        return false
      },
    },
    onUpdate: ({ editor }) => onChange(editor.getHTML()),
    onSelectionUpdate: () => setTick((t) => t + 1),
    onTransaction: () => setTick((t) => t + 1),
  })
  editorRef.current = editor

  // 외부에서 value 가 바뀐 경우(글 불러오기)에만 반영
  useEffect(() => {
    // StrictMode 가 effect 를 두 번 돌릴 때 첫 인스턴스는 이미 파괴돼 있어 getHTML 이 터진다
    if (!editor || editor.isDestroyed) return
    if (value !== editor.getHTML() && !editor.isFocused) editor.commands.setContent(value, { emitUpdate: false })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value, editor])

  async function handleFiles(files: File[]) {
    if (!editor) return
    for (const f of files) {
      setBusy(`${f.name} 올리는 중…`)
      try {
        const up = await onUpload(f)
        if (up.kind === 'IMAGE') editor.chain().focus().insertFigureImage({ src: up.url, alt: f.name.replace(/\.[^.]+$/, '') }).run()
        else { if (up.kind === 'EXCEL') await insertExcel(f); onAttached?.(up) }
      } catch (e) {
        window.alert(e instanceof Error ? e.message : '파일을 올리지 못했습니다.')
      } finally { setBusy(null) }
    }
  }

  /** 엑셀 → 첫 시트(또는 선택한 시트)를 표로 삽입. 원본은 첨부로 남는다. */
  async function insertExcel(file: File) {
    if (!editor) return
    const XLSX = await import('xlsx')
    const wb = XLSX.read(await file.arrayBuffer(), { type: 'array', cellDates: true })
    let sheet = wb.SheetNames[0]
    if (wb.SheetNames.length > 1) {
      const pick = window.prompt(`삽입할 시트 이름을 입력하세요:\n${wb.SheetNames.join(', ')}`, sheet)
      if (pick === null) return
      if (wb.SheetNames.includes(pick.trim())) sheet = pick.trim()
    }
    const rows = XLSX.utils.sheet_to_json<(string | number | Date | null)[]>(wb.Sheets[sheet], { header: 1, raw: false, defval: '' }) as (string | number)[][]
    const trimmed = rows.filter((r) => r.some((c) => String(c ?? '').trim() !== '')).slice(0, 500)
    if (trimmed.length === 0) { window.alert('시트에 내용이 없습니다.'); return }
    const cols = Math.min(30, Math.max(...trimmed.map((r) => r.length)))
    const esc = (s: unknown) => String(s ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    const html = `<table class="rich-table" data-sheet="${esc(sheet)}"><tbody>` +
      trimmed.map((r, i) => `<tr>${Array.from({ length: cols }, (_, c) => `<${i === 0 ? 'th' : 'td'}><p>${esc(r[c])}</p></${i === 0 ? 'th' : 'td'}>`).join('')}</tr>`).join('') +
      `</tbody></table><p></p>`
    editor.chain().focus().insertContent(html).run()
  }

  /** 문서 파일을 본문으로 변환해 커서 위치에 넣는다. 문서 안 사진은 첨부로 연결. */
  async function importDocument(file: File) {
    if (!editor || !onImportDocument) return
    setBusy(`${file.name} 변환 중…`)
    try {
      const r = await onImportDocument(file)
      editor.chain().focus().insertContent(r.content + '<p></p>').run()
      for (const a of r.attachments) onAttached?.({ id: a.id, name: a.name, url: a.url, kind: a.kind, size: a.size })
      if (r.title) onImportedTitle?.(r.title)
      if (r.warnings.length) window.alert('가져오기 안내:\n- ' + r.warnings.join('\n- '))
    } catch (e) {
      window.alert(e instanceof Error ? e.message : '문서를 가져오지 못했습니다.')
    } finally { setBusy(null) }
  }

  /** draw.io 저장 → PNG(XML 포함) 업로드 → 새 다이어그램이면 사진 블록 삽입, 편집이면 주소 교체 */
  async function saveDiagram(pngDataUri: string, apply?: (src: string) => void) {
    if (!editor) return
    const up = await onUpload(dataUriToFile(pngDataUri, `diagram-${Date.now()}.png`))
    if (apply) apply(up.url)
    else editor.chain().focus().insertFigureImage({ src: up.url, alt: '다이어그램', diagram: 'drawio', width: null }).run()
  }

  async function openDrawioFile(file: File) {
    const text = await file.text()
    setDrawio({ initial: { xml: text } })
  }

  if (!editor) return null
  void tick

  return (
    <div className="rte">
      <Toolbar editor={editor}
               onImage={() => fileInput.current?.click()}
               onExcel={() => excelInput.current?.click()}
               onFile={() => { const i = document.createElement('input'); i.type = 'file'; i.multiple = true; i.onchange = () => i.files && handleFiles(Array.from(i.files)); i.click() }}
               onDocument={onImportDocument ? () => docInput.current?.click() : undefined}
               onDiagram={() => setDrawio({})}
               onDiagramFile={() => drawioFileInput.current?.click()} />
      <input ref={docInput} type="file" accept={IMPORT_ACCEPT} hidden onChange={(e) => { const f = e.target.files?.[0]; if (f) void importDocument(f); e.target.value = '' }} />
      <input ref={drawioFileInput} type="file" accept=".drawio,.xml" hidden onChange={(e) => { const f = e.target.files?.[0]; if (f) void openDrawioFile(f); e.target.value = '' }} />
      {drawio && <DrawioDialog initial={drawio.initial} onSave={(png) => saveDiagram(png, drawio.apply)} onClose={() => setDrawio(null)} />}
      <input ref={fileInput} type="file" accept="image/png,image/jpeg,image/gif,image/webp" multiple hidden onChange={(e) => { if (e.target.files) handleFiles(Array.from(e.target.files)); e.target.value = '' }} />
      <input ref={excelInput} type="file" accept=".xlsx,.xlsm,.xls,.csv" hidden onChange={(e) => { if (e.target.files) handleFiles(Array.from(e.target.files)); e.target.value = '' }} />
      <EditorContent editor={editor} className="rte__content" />
      {busy && <div className="rte__busy">{busy}</div>}
    </div>
  )
}

/** 툴바 버튼. 컴포넌트 밖에 두어야 트랜잭션마다 다시 마운트되지 않는다. mousedown 을 막아 에디터 선택이 유지된다. */
function B({ cmd, active, title, children, disabled }: { cmd: () => void; active?: boolean; title: string; children: React.ReactNode; disabled?: boolean }) {
  return (
    <button type="button" className="rte__btn" aria-pressed={!!active} title={title} disabled={disabled} onMouseDown={(e) => e.preventDefault()} onClick={cmd}>{children}</button>
  )
}

function Toolbar({ editor, onImage, onExcel, onFile, onDocument, onDiagram, onDiagramFile }:
  { editor: Editor; onImage: () => void; onExcel: () => void; onFile: () => void; onDocument?: () => void; onDiagram: () => void; onDiagramFile: () => void }) {
  const [palette, setPalette] = useState<null | 'color' | 'bg'>(null)
  const [tableMenu, setTableMenu] = useState(false)
  const [diagramMenu, setDiagramMenu] = useState(false)
  const c = () => editor.chain().focus()
  const attrs = editor.getAttributes('textStyle') as { fontFamily?: string; fontSize?: string; lineHeight?: string; color?: string; backgroundColor?: string }
  const block = editor.isActive('heading', { level: 1 }) ? 'h1' : editor.isActive('heading', { level: 2 }) ? 'h2' : editor.isActive('heading', { level: 3 }) ? 'h3' : editor.isActive('blockquote') ? 'quote' : editor.isActive('codeBlock') ? 'code' : 'p'
  const setBlock = (v: string) => {
    if (v === 'p') c().setParagraph().run()
    else if (v === 'quote') c().toggleBlockquote().run()
    else if (v === 'code') c().toggleCodeBlock().run()
    else c().toggleHeading({ level: Number(v.slice(1)) as 1 | 2 | 3 }).run()
  }
  const setLink = () => promptLink(editor)
  /** 목록으로 바꿀 때 문단 들여쓰기는 지운다(목록 안에서는 Tab 이 단계 이동이라 둘이 겹치면 어긋나 보인다). */
  const toggleList = (type: 'bulletList' | 'orderedList') => {
    const ch = type === 'bulletList' ? c().toggleBulletList() : c().toggleOrderedList()
    ch.run()
    if (editor.isActive(type)) c().updateAttributes('paragraph', { indent: 0 }).run()
  }
  return (
    <div className="rte__toolbar" role="toolbar" aria-label="서식">
      {/* 1행: 삽입 도구 (네이버 에디터처럼 사진·표·파일이 위, 글자 서식이 아래) */}
      <div className="rte__row rte__row--insert">
        <div className="rte__group rte__group--rel">
          <B cmd={onImage} title="사진 넣기 (끌어다 놓기·붙여넣기도 가능)"><IcImage /> 사진</B>
          <B cmd={() => setTableMenu((v) => !v)} active={editor.isActive('table')} title="표"><IcTable /> 표</B>
          {tableMenu && (
            <div className="rte__menu" onMouseDown={(e) => e.preventDefault()}>
              <button type="button" onClick={() => { c().insertTable({ rows: 3, cols: 3, withHeaderRow: true }).run(); setTableMenu(false) }}>표 삽입 (3×3)</button>
              <button type="button" onClick={() => { const r = Number(window.prompt('행 수', '3')), col = Number(window.prompt('열 수', '3')); if (r > 0 && col > 0) c().insertTable({ rows: r, cols: col, withHeaderRow: true }).run(); setTableMenu(false) }}>표 삽입 (크기 지정)</button>
              <hr />
              <button type="button" disabled={!editor.isActive('table')} onClick={() => c().addRowBefore().run()}>위에 행 추가</button>
              <button type="button" disabled={!editor.isActive('table')} onClick={() => c().addRowAfter().run()}>아래에 행 추가</button>
              <button type="button" disabled={!editor.isActive('table')} onClick={() => c().addColumnBefore().run()}>왼쪽에 열 추가</button>
              <button type="button" disabled={!editor.isActive('table')} onClick={() => c().addColumnAfter().run()}>오른쪽에 열 추가</button>
              <button type="button" disabled={!editor.isActive('table')} onClick={() => c().deleteRow().run()}>행 삭제</button>
              <button type="button" disabled={!editor.isActive('table')} onClick={() => c().deleteColumn().run()}>열 삭제</button>
              <hr />
              <button type="button" disabled={!editor.isActive('table')} onClick={() => c().toggleHeaderRow().run()}>머리글 행 전환</button>
              <button type="button" disabled={!editor.isActive('table')} onClick={() => c().toggleHeaderColumn().run()}>머리글 열 전환</button>
              <button type="button" disabled={!editor.can().mergeCells() && !editor.can().splitCell()} onClick={() => c().mergeOrSplit().run()}>셀 병합/분할</button>
              <hr />
              <span className="rte__menu-label">셀 배경색</span>
              <div className="rte__cellcolors">
                {CELL_COLORS.map(([name, hex]) => (
                  <button key={hex} type="button" className="rte__color" title={name} aria-label={`셀 배경 ${name}`} style={{ background: hex }}
                          disabled={!editor.isActive('table')} onClick={() => c().setCellAttribute('backgroundColor', hex).run()} />
                ))}
                <button type="button" className="rte__color rte__color--none" title="없음" aria-label="셀 배경 없음"
                        disabled={!editor.isActive('table')} onClick={() => c().setCellAttribute('backgroundColor', null).run()}>×</button>
              </div>
              <span className="rte__menu-label">셀 정렬</span>
              <div className="rte__cellalign">
                {([['left', '왼쪽'], ['center', '가운데'], ['right', '오른쪽']] as const).map(([v, label]) => (
                  <button key={v} type="button" disabled={!editor.isActive('table')}
                          aria-pressed={editor.isActive('tableCell', { cellAlign: v }) || editor.isActive('tableHeader', { cellAlign: v })}
                          onClick={() => c().setCellAttribute('cellAlign', v).run()}>{label}</button>
                ))}
              </div>
              <hr />
              <button type="button" disabled={!editor.isActive('table')} className="danger" onClick={() => { c().deleteTable().run(); setTableMenu(false) }}>표 삭제</button>
            </div>
          )}
          <B cmd={onExcel} title="엑셀 파일을 표로 넣고 원본을 첨부"><IcExcel /> 엑셀</B>
          {onDocument && <B cmd={onDocument} title="문서 파일(HTML·Word·Excel·CSV·Markdown·텍스트)을 본문으로 가져오기"><IcDocument /> 문서</B>}
          <B cmd={() => setDiagramMenu((v) => !v)} title="draw.io 다이어그램"><IcDiagram /> 다이어그램</B>
          {diagramMenu && (
            <div className="rte__menu" style={{ left: 'auto' }} onMouseDown={(e) => e.preventDefault()}>
              <button type="button" onClick={() => { onDiagram(); setDiagramMenu(false) }}>새 다이어그램 그리기</button>
              <button type="button" onClick={() => { onDiagramFile(); setDiagramMenu(false) }}>.drawio 파일 열어서 넣기</button>
              <hr />
              <span className="small muted" style={{ padding: '4px 10px' }}>넣은 다이어그램을 선택하면 '다이어그램 편집'으로 다시 고칠 수 있습니다.</span>
            </div>
          )}
          <B cmd={onFile} title="파일 첨부"><IcPaperclip /> 파일</B>
        </div>
        <div className="rte__group">
          <B cmd={setLink} active={editor.isActive('link')} title="링크 (Ctrl+K)"><IcLink /> 링크</B>
          <B cmd={() => c().setHorizontalRule().run()} title="구분선"><IcRule /> 구분선</B>
        </div>
        <div className="rte__group rte__group--end">
          <B cmd={() => c().undo().run()} disabled={!editor.can().undo()} title="되돌리기 (Ctrl+Z)"><IcUndo /></B>
          <B cmd={() => c().redo().run()} disabled={!editor.can().redo()} title="다시 실행 (Ctrl+Y)"><IcRedo /></B>
          <B cmd={() => c().clearNodes().unsetAllMarks().run()} title="서식 지우기"><IcEraser /></B>
        </div>
      </div>
      {/* 2행: 글자·문단 서식 */}
      <div className="rte__row">
      <div className="rte__group">
        <select className="rte__select" value={block} onChange={(e) => setBlock(e.target.value)} aria-label="문단 형식">
          <option value="p">본문</option><option value="h1">제목 1</option><option value="h2">제목 2</option><option value="h3">제목 3</option><option value="quote">인용</option><option value="code">코드</option>
        </select>
        <select className="rte__select" value={attrs.fontFamily ?? ''} onChange={(e) => e.target.value ? c().setFontFamily(e.target.value).run() : c().unsetFontFamily().run()} aria-label="글꼴">
          {FONTS.map(([label, v]) => <option key={label} value={v}>{label}</option>)}
        </select>
        <select className="rte__select rte__select--sm" value={attrs.fontSize ?? ''} onChange={(e) => e.target.value ? c().setFontSize(e.target.value).run() : c().unsetFontSize().run()} aria-label="글자 크기">
          <option value="">크기</option>{SIZES.map((s) => <option key={s} value={`${s}px`}>{s}</option>)}
        </select>
        <select className="rte__select rte__select--sm" value={attrs.lineHeight ?? ''} onChange={(e) => e.target.value ? c().setLineHeight(e.target.value).run() : c().unsetLineHeight().run()} aria-label="줄간격">
          {LINE_HEIGHTS.map(([label, v]) => <option key={label} value={v}>{label}</option>)}
        </select>
      </div>
      <div className="rte__group">
        <B cmd={() => c().toggleBold().run()} active={editor.isActive('bold')} title="굵게 (Ctrl+B)"><b>B</b></B>
        <B cmd={() => c().toggleItalic().run()} active={editor.isActive('italic')} title="기울임 (Ctrl+I)"><i>I</i></B>
        <B cmd={() => c().toggleUnderline().run()} active={editor.isActive('underline')} title="밑줄 (Ctrl+U)"><u>U</u></B>
        <B cmd={() => c().toggleStrike().run()} active={editor.isActive('strike')} title="취소선"><s>S</s></B>
        <B cmd={() => c().toggleSuperscript().run()} active={editor.isActive('superscript')} title="위첨자">x²</B>
        <B cmd={() => c().toggleSubscript().run()} active={editor.isActive('subscript')} title="아래첨자">x₂</B>
        <B cmd={() => c().toggleCode().run()} active={editor.isActive('code')} title="인라인 코드">{'</>'}</B>
      </div>
      <div className="rte__group rte__group--rel">
        <B cmd={() => setPalette(palette === 'color' ? null : 'color')} title="글자색"><span className="rte__swatch" style={{ background: attrs.color ?? '#1b2430' }} />가</B>
        <B cmd={() => setPalette(palette === 'bg' ? null : 'bg')} title="배경색(강조)"><span className="rte__swatch" style={{ background: attrs.backgroundColor ?? '#fff2a8' }} />▣</B>
        {palette && (
          <div className="rte__palette" onMouseDown={(e) => e.preventDefault()}>
            {(palette === 'color' ? COLORS : HIGHLIGHTS).map((col) => (
              <button type="button" key={col} className="rte__color" style={{ background: col === 'transparent' ? '#fff' : col }} title={col}
                      onClick={() => { if (palette === 'color') c().setColor(col).run(); else if (col === 'transparent') c().unsetBackgroundColor().run(); else c().setBackgroundColor(col).run(); setPalette(null) }}>
                {col === 'transparent' ? '✕' : ''}
              </button>
            ))}
            <button type="button" className="rte__color rte__color--reset" onClick={() => { if (palette === 'color') c().unsetColor().run(); else c().unsetBackgroundColor().run(); setPalette(null) }}>기본</button>
          </div>
        )}
      </div>
      <div className="rte__group">
        <B cmd={() => c().setTextAlign('left').run()} active={editor.isActive({ textAlign: 'left' })} title="왼쪽 정렬">≡</B>
        <B cmd={() => c().setTextAlign('center').run()} active={editor.isActive({ textAlign: 'center' })} title="가운데 정렬">☰</B>
        <B cmd={() => c().setTextAlign('right').run()} active={editor.isActive({ textAlign: 'right' })} title="오른쪽 정렬">≡</B>
        <B cmd={() => c().setTextAlign('justify').run()} active={editor.isActive({ textAlign: 'justify' })} title="양쪽 정렬">▤</B>
        <B cmd={() => c().outdent().run()} title="내어쓰기 (Shift+Tab)">⇤</B>
        <B cmd={() => c().indent().run()} title="들여쓰기 (Tab)">⇥</B>
      </div>
      <div className="rte__group">
        <B cmd={() => toggleList('bulletList')} active={editor.isActive('bulletList')} title="글머리 기호">•≡</B>
        <B cmd={() => toggleList('orderedList')} active={editor.isActive('orderedList')} title="번호 매기기">1.≡</B>
        <B cmd={() => c().toggleBlockquote().run()} active={editor.isActive('blockquote')} title="인용구">❝</B>
      </div>
      </div>
    </div>
  )
}
