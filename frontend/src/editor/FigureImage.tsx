import { mergeAttributes, Node } from '@tiptap/core'
import { NodeViewWrapper, ReactNodeViewRenderer, type NodeViewProps } from '@tiptap/react'
import { useRef, useState } from 'react'

export type ImageAlign = 'left' | 'center' | 'right'

declare module '@tiptap/core' {
  interface Commands<ReturnType> {
    figureImage: {
      insertFigureImage: (attrs: { src: string; alt?: string; width?: string | null; align?: ImageAlign; caption?: string; diagram?: 'drawio' | null }) => ReturnType
    }
  }
}

/**
 * 사진 블록: <figure data-align data-width [data-diagram="drawio"]><img><figcaption>.
 * 네이버 에디터처럼 선택하면 크기 조절 손잡이, 정렬·캡션·대체 텍스트·삭제 도구가 뜬다.
 * data-diagram="drawio" 면 draw.io 로 만든 다이어그램(PNG 안에 XML 보존) — '다이어그램 편집'으로 다시 열 수 있다.
 */
export interface FigureImageOptions {
  /** 다이어그램 편집 요청: 현재 PNG 주소와, 새 PNG 주소를 적용하는 콜백 */
  onEditDiagram?: (src: string, apply: (newSrc: string) => void) => void
}

export const FigureImage = Node.create<FigureImageOptions>({
  name: 'figureImage',
  addOptions() { return { onEditDiagram: undefined } },
  group: 'block',
  atom: true,
  draggable: true,
  selectable: true,

  addAttributes() {
    return {
      src: { default: null },
      alt: { default: '' },
      title: { default: null },
      width: { default: null, parseHTML: (el) => el.getAttribute('data-width') || (el.querySelector('img')?.style.width || null) },
      align: { default: 'center', parseHTML: (el) => (el.getAttribute('data-align') as ImageAlign) || 'center' },
      caption: { default: '', parseHTML: (el) => el.querySelector('figcaption')?.textContent ?? '' },
      diagram: { default: null, parseHTML: (el) => el.getAttribute('data-diagram') === 'drawio' ? 'drawio' : null },
    }
  },

  parseHTML() {
    return [
      { tag: 'figure', getAttrs: (el) => { const img = (el as HTMLElement).querySelector('img'); return img ? { src: img.getAttribute('src'), alt: img.getAttribute('alt') ?? '', title: img.getAttribute('title') } : false } },
      { tag: 'img[src]', getAttrs: (el) => ({ src: (el as HTMLElement).getAttribute('src'), alt: (el as HTMLElement).getAttribute('alt') ?? '', title: (el as HTMLElement).getAttribute('title'), width: (el as HTMLElement).style.width || (el as HTMLElement).getAttribute('width') || null }) },
    ]
  },

  renderHTML({ HTMLAttributes }) {
    const { src, alt, title, width, align, caption, diagram } = HTMLAttributes as Record<string, string | null>
    const imgAttrs: Record<string, string> = { src: src ?? '', alt: alt ?? '' }
    if (title) imgAttrs.title = title
    if (width) imgAttrs.style = `width:${width}`
    const figAttrs = mergeAttributes({ class: 'img-figure', 'data-align': align ?? 'center' }, width ? { 'data-width': width } : {}, diagram ? { 'data-diagram': diagram } : {})
    return caption
      ? ['figure', figAttrs, ['img', imgAttrs], ['figcaption', {}, caption]]
      : ['figure', figAttrs, ['img', imgAttrs]]
  },

  addCommands() {
    return {
      insertFigureImage: (attrs) => ({ commands }) => commands.insertContent({ type: this.name, attrs: { align: 'center', caption: '', ...attrs } }),
    }
  },

  addNodeView() {
    return ReactNodeViewRenderer(ImageView)
  },
})

function ImageView({ node, updateAttributes, deleteNode, selected, editor, extension }: NodeViewProps) {
  const { src, alt, width, align, caption, diagram } = node.attrs as { src: string; alt: string; width: string | null; align: ImageAlign; caption: string; diagram: 'drawio' | null }
  const onEditDiagram = (extension.options as FigureImageOptions).onEditDiagram
  const imgRef = useRef<HTMLImageElement>(null)
  const [dragging, setDragging] = useState(false)

  const startResize = (e: React.MouseEvent, dir: 1 | -1) => {
    e.preventDefault(); e.stopPropagation()
    const img = imgRef.current; if (!img) return
    const startX = e.clientX, startW = img.getBoundingClientRect().width
    const parentW = (img.closest('.ProseMirror') as HTMLElement | null)?.clientWidth ?? 800
    setDragging(true)
    const onMove = (ev: MouseEvent) => {
      const w = Math.max(80, Math.min(parentW, startW + (ev.clientX - startX) * dir))
      updateAttributes({ width: `${Math.round((w / parentW) * 100)}%` })
    }
    const onUp = () => { setDragging(false); window.removeEventListener('mousemove', onMove); window.removeEventListener('mouseup', onUp) }
    window.addEventListener('mousemove', onMove); window.addEventListener('mouseup', onUp)
  }

  const editCaption = () => { const v = window.prompt('사진 설명(캡션)', caption ?? ''); if (v !== null) updateAttributes({ caption: v.trim() }) }
  const editAlt = () => { const v = window.prompt('대체 텍스트(화면 낭독기용)', alt ?? ''); if (v !== null) updateAttributes({ alt: v.trim() }) }

  return (
    <NodeViewWrapper className={`img-figure img-figure--edit${selected ? ' is-selected' : ''}${dragging ? ' is-dragging' : ''}`} data-align={align} data-drag-handle>
      <div className="img-figure__box" style={{ width: width ?? undefined }}>
        <img ref={imgRef} src={src} alt={alt} draggable={false} />
        {selected && editor.isEditable && (
          <>
            <span className="img-handle img-handle--l" onMouseDown={(e) => startResize(e, -1)} aria-hidden />
            <span className="img-handle img-handle--r" onMouseDown={(e) => startResize(e, 1)} aria-hidden />
            <div className="img-tools" contentEditable={false}>
              <button type="button" aria-pressed={align === 'left'} onClick={() => updateAttributes({ align: 'left' })} title="왼쪽 정렬">⇤</button>
              <button type="button" aria-pressed={align === 'center'} onClick={() => updateAttributes({ align: 'center' })} title="가운데 정렬">↔</button>
              <button type="button" aria-pressed={align === 'right'} onClick={() => updateAttributes({ align: 'right' })} title="오른쪽 정렬">⇥</button>
              <span className="img-tools__sep" />
              <button type="button" onClick={() => updateAttributes({ width: '25%' })}>25%</button>
              <button type="button" onClick={() => updateAttributes({ width: '50%' })}>50%</button>
              <button type="button" onClick={() => updateAttributes({ width: null })}>원본</button>
              <span className="img-tools__sep" />
              {diagram === 'drawio' && onEditDiagram && <button type="button" onClick={() => onEditDiagram(src, (newSrc) => updateAttributes({ src: newSrc }))}>다이어그램 편집</button>}
              <button type="button" onClick={editCaption}>캡션</button>
              <button type="button" onClick={editAlt}>대체 텍스트</button>
              <button type="button" className="danger" onClick={() => deleteNode()}>삭제</button>
            </div>
          </>
        )}
        {diagram === 'drawio' && selected && <span className="img-figure__badge" contentEditable={false}>draw.io</span>}
        {caption && <figcaption>{caption}</figcaption>}
      </div>
    </NodeViewWrapper>
  )
}
