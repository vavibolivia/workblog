import { Extension, type Command } from '@tiptap/core'

declare module '@tiptap/core' {
  interface Commands<ReturnType> {
    indent: {
      /** 들여쓰기: 목록 안이면 한 단계 아래로, 아니면 문단 여백 증가 */
      indent: () => ReturnType
      /** 내어쓰기: 목록 안이면 한 단계 위로, 아니면 문단 여백 감소 */
      outdent: () => ReturnType
    }
  }
}

const MAX = 8
const STEP = 32 // px per level
const TYPES = ['paragraph', 'heading', 'blockquote']

/** 네이버 에디터의 들여쓰기/내어쓰기. data-indent 와 margin-left 로 저장되어 읽기 화면에서도 같게 보인다. */
export const Indent = Extension.create({
  name: 'indent',

  addGlobalAttributes() {
    return [{
      types: TYPES,
      attributes: {
        indent: {
          default: 0,
          parseHTML: (el) => {
            const d = Number(el.getAttribute('data-indent'))
            if (d) return Math.min(MAX, Math.max(0, d))
            const ml = parseInt(el.style.marginLeft || '0', 10)
            return ml > 0 ? Math.min(MAX, Math.round(ml / STEP)) : 0
          },
          renderHTML: (attrs) => {
            const n = Number(attrs.indent) || 0
            if (!n) return {}
            return { 'data-indent': String(n), style: `margin-left:${n * STEP}px` }
          },
        },
      },
    }]
  },

  addCommands() {
    // 명령 공용 tr 에만 기록하고 직접 dispatch 하지 않는다(직접 dispatch 하면 "mismatched transaction" 으로 터진다).
    const change = (delta: 1 | -1): (() => Command) => () => ({ editor, commands, state, tr, dispatch }) => {
      if (editor.isActive('listItem')) {
        return delta > 0 ? commands.sinkListItem('listItem') : commands.liftListItem('listItem')
      }
      const { from, to } = state.selection
      let changed = false
      state.doc.nodesBetween(from, to, (node, pos) => {
        if (!TYPES.includes(node.type.name)) return
        const cur = Number(node.attrs.indent) || 0
        const next = Math.min(MAX, Math.max(0, cur + delta))
        if (next === cur) return
        changed = true
        if (dispatch) tr.setNodeMarkup(pos, undefined, { ...node.attrs, indent: next })
      })
      return changed
    }
    return { indent: change(1), outdent: change(-1) }
  },

  addKeyboardShortcuts() {
    // 더 못 들여쓰는 경우에도 Tab 이 에디터 밖으로 포커스를 옮기지 않도록 항상 true
    return {
      Tab: () => { this.editor.commands.indent(); return true },
      'Shift-Tab': () => { this.editor.commands.outdent(); return true },
    }
  },
})
