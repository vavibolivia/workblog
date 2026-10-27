import { createContext, useContext, useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api, errorMessage, type CategoryNode } from '../lib/api'
import { useAuth } from '../lib/auth'
import { useToast } from '../lib/toast'

/**
 * 사이드바용 접고 펴는 카테고리 트리.
 * 글 수 표기 규칙(모든 단계 동일): 숫자 = 이 카테고리에 직접 단 글(postCount)만. 하위 카테고리 글은 더하지 않는다.
 * - 우클릭(또는 행의 ⋯ 버튼): 이 카테고리 글 보기 · 이 카테고리에 글쓰기, 관리자는 하위 추가·이름 바꾸기·순서·이동·삭제
 * - 관리자는 행을 끌어 다른 카테고리 위에 놓으면 그 하위로, 맨 위 '최상위로' 띠에 놓으면 1단계로 옮긴다(최대 5단계 규칙은 서버가 검사).
 */
interface TreeCtx {
  isAdmin: boolean
  dragId: number | null
  setDragId: (id: number | null) => void
  openMenu: (node: CategoryNode, x: number, y: number) => void
  move: (id: number, parentId: number | null) => void
  /** 끌어 놓기: target 기준 앞(before)·뒤(after)·안(inside) */
  drop: (id: number, target: CategoryNode, where: DropWhere) => void
}
type DropWhere = 'before' | 'after' | 'inside'
const Ctx = createContext<TreeCtx | null>(null)

export default function CategoryTree({ nodes, activeId, activePathIds = [], onChanged }:
  { nodes: CategoryNode[]; activeId: number | null; activePathIds?: number[]; onChanged?: () => void }) {
  const { user } = useAuth()
  const toast = useToast()
  const navigate = useNavigate()
  const isAdmin = user?.role === 'ADMIN'
  const [dragId, setDragId] = useState<number | null>(null)
  const [menu, setMenu] = useState<{ node: CategoryNode; x: number; y: number } | null>(null)
  const [rootOver, setRootOver] = useState(false)
  const menuRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!menu) return
    const close = (e: Event) => { if (!(e.target instanceof window.Node && menuRef.current?.contains(e.target))) setMenu(null) }
    const esc = (e: KeyboardEvent) => { if (e.key === 'Escape') setMenu(null) }
    document.addEventListener('mousedown', close)
    document.addEventListener('keydown', esc)
    window.addEventListener('scroll', close, true)
    return () => { document.removeEventListener('mousedown', close); document.removeEventListener('keydown', esc); window.removeEventListener('scroll', close, true) }
  }, [menu])

  const run = async (fn: () => Promise<unknown>, ok: string) => {
    try { await fn(); toast(ok, 'success'); onChanged?.() } catch (e) { toast(errorMessage(e), 'error') }
  }
  const move = (id: number, parentId: number | null) => {
    const node = find(nodes, id)
    if (!node) return
    if (parentId === id || (parentId !== null && find(node.children, parentId))) { toast('자기 자신이나 하위 카테고리 아래로는 옮길 수 없습니다.', 'error'); return }
    if (node.parentId === parentId) return
    void run(() => api.categories.update(id, parentId === null ? { name: node.name, moveToRoot: true } : { name: node.name, parentId }),
      `'${node.name}'을(를) ${parentId === null ? '최상위로' : `'${find(nodes, parentId)?.name}' 아래로`} 옮겼습니다.`)
  }

  const act = (kind: string) => {
    if (!menu) return
    const n = menu.node
    setMenu(null)
    switch (kind) {
      case 'open': navigate(`/?categoryId=${n.id}`); break
      case 'write': navigate(`/write?categoryId=${n.id}`); break
      case 'add': {
        const name = window.prompt(`'${n.name}' 아래에 만들 카테고리 이름`)
        if (name?.trim()) void run(() => api.categories.create(name.trim(), n.id), `'${name.trim()}'을(를) 만들었습니다.`)
        break
      }
      case 'rename': {
        const name = window.prompt('새 이름', n.name)
        if (name?.trim() && name.trim() !== n.name) void run(() => api.categories.update(n.id, { name: name.trim(), parentId: n.parentId }), '이름을 바꿨습니다.')
        break
      }
      case 'up': void run(() => api.categories.move(n.id, -1), '위로 옮겼습니다.'); break
      case 'down': void run(() => api.categories.move(n.id, 1), '아래로 옮겼습니다.'); break
      case 'root': move(n.id, null); break
      case 'delete':
        if (window.confirm(`'${n.name}' 카테고리를 삭제할까요?\n하위 카테고리나 글이 있으면 삭제되지 않습니다.`)) void run(() => api.categories.remove(n.id), '삭제했습니다.')
        break
    }
  }

  /** 형제 목록(부모 기준) */
  const siblingsOf = (parentId: number | null) => parentId === null ? nodes : (find(nodes, parentId)?.children ?? [])
  const drop = (id: number, target: CategoryNode, where: DropWhere) => {
    const node = find(nodes, id)
    if (!node || id === target.id) return
    if (find(node.children, target.id)) { toast('자기 하위 카테고리 쪽으로는 옮길 수 없습니다.', 'error'); return }
    if (where === 'inside') { move(id, target.id); return }
    const parentId = target.parentId
    const sibs = siblingsOf(parentId).filter((s) => s.id !== id)
    let index = sibs.findIndex((s) => s.id === target.id)
    if (where === 'after') index += 1
    void run(() => api.categories.place(id, parentId, Math.max(0, index)),
      `'${node.name}'을(를) '${target.name}' ${where === 'before' ? '앞' : '뒤'}으로 옮겼습니다.`)
  }

  const ctx: TreeCtx = { isAdmin, dragId, setDragId, openMenu: (node, x, y) => setMenu({ node, x, y }), move, drop }
  return (
    <Ctx.Provider value={ctx}>
      {isAdmin && dragId !== null && (
        <div className={`cat-drop-root${rootOver ? ' is-over' : ''}`}
             onDragOver={(e) => { e.preventDefault(); e.dataTransfer.dropEffect = 'move'; setRootOver(true) }}
             onDragLeave={() => setRootOver(false)}
             onDrop={(e) => { e.preventDefault(); setRootOver(false); const id = dragId; setDragId(null); if (id !== null) move(id, null) }}>
          여기에 놓으면 최상위(1단계)로
        </div>
      )}
      <ul className="cat-tree">{nodes.map((n) => <Node key={n.id} node={n} activeId={activeId} activePathIds={activePathIds} />)}</ul>
      {menu && (
        <div ref={menuRef} className="menu cat-menu" role="menu" style={{ left: Math.min(menu.x, window.innerWidth - 240), top: Math.min(menu.y, window.innerHeight - 330) }}>
          <div className="menu__head"><strong className="ellipsis">{menu.node.name}</strong><span className="small muted">{menu.node.depth}단계 · 글 {menu.node.postCount}</span></div>
          <button type="button" role="menuitem" onClick={() => act('open')}>이 카테고리 글 보기</button>
          <button type="button" role="menuitem" onClick={() => act('write')}>이 카테고리에 글쓰기</button>
          {isAdmin && <>
            <hr className="menu__sep" />
            <button type="button" role="menuitem" disabled={menu.node.depth >= 5} onClick={() => act('add')}>하위 카테고리 추가{menu.node.depth >= 5 ? ' (5단계 한도)' : ''}</button>
            <button type="button" role="menuitem" onClick={() => act('rename')}>이름 바꾸기</button>
            <button type="button" role="menuitem" onClick={() => act('up')}>위로</button>
            <button type="button" role="menuitem" onClick={() => act('down')}>아래로</button>
            {menu.node.parentId !== null && <button type="button" role="menuitem" onClick={() => act('root')}>최상위로 옮기기</button>}
            <button type="button" role="menuitem" className="menu__danger" onClick={() => act('delete')}>삭제</button>
            <div className="menu__foot small muted">끌어서 놓기: 행 위쪽 = 앞에, 가운데 = 하위로, 아래쪽 = 뒤에</div>
          </>}
        </div>
      )}
    </Ctx.Provider>
  )
}

function find(nodes: CategoryNode[], id: number): CategoryNode | undefined {
  for (const n of nodes) { if (n.id === id) return n; const f = find(n.children, id); if (f) return f }
  return undefined
}

/**
 * 펼침 상태 규칙(R10)
 * - 기본: 활성 경로에 있거나 1단계면 펼침.
 * - 사용자가 직접 토글하면 그 값이 우선한다(활성 경로여도 접을 수 있음).
 * - 다른 곳에서 이동해 와 이 노드가 새로 활성 경로에 들어오면 사용자 토글을 지우고 다시 펼친다.
 */
function Node({ node, activeId, activePathIds }: { node: CategoryNode; activeId: number | null; activePathIds: number[] }) {
  const tree = useContext(Ctx)!
  const onPath = activePathIds.includes(node.id)
  const active = activeId === node.id
  const hasKids = node.children.length > 0
  const [userOpen, setUserOpen] = useState<boolean | null>(null)
  const [prevOnPath, setPrevOnPath] = useState(onPath)
  const [over, setOver] = useState<DropWhere | null>(null)
  if (onPath !== prevOnPath) {
    setPrevOnPath(onPath)
    if (onPath) setUserOpen(null)
  }
  const expanded = userOpen ?? (onPath || node.depth === 1)

  // 이름 클릭: 이동 + 접힘이면 펼치고, 이미 펼쳐진 활성 노드면 접는다(펼쳐진 다른 노드는 그대로 두고 이동만).
  const onNameClick = () => {
    if (!hasKids) return
    if (!expanded) setUserOpen(true)
    else if (active) setUserOpen(false)
  }
  const dragging = tree.dragId === node.id
  const canDrop = tree.isAdmin && tree.dragId !== null && tree.dragId !== node.id

  return (
    <li className={onPath ? 'on-path' : undefined} data-depth={node.depth}>
      <div className={`cat-tree__row cat-tree__row--d${node.depth}${active ? ' active' : ''}${onPath && !active ? ' on-path' : ''}${over ? ` is-drop-${over}` : ''}${dragging ? ' is-dragging' : ''}`}
           draggable={tree.isAdmin}
           onDragStart={(e) => { e.stopPropagation(); e.dataTransfer.effectAllowed = 'move'; e.dataTransfer.setData('text/plain', String(node.id)); tree.setDragId(node.id) }}
           onDragEnd={() => { tree.setDragId(null); setOver(null) }}
           onDragOver={(e) => {
             if (!canDrop) return
             e.preventDefault(); e.dataTransfer.dropEffect = 'move'
             // 행 높이 기준: 위 30% = 앞에, 아래 30% = 뒤에, 가운데 = 하위로(5단계면 하위 불가 → 뒤에)
             const r = e.currentTarget.getBoundingClientRect(); const y = (e.clientY - r.top) / r.height
             const where: DropWhere = y < 0.3 ? 'before' : y > 0.7 || node.depth >= 5 ? 'after' : 'inside'
             if (where !== over) setOver(where)
           }}
           onDragLeave={(e) => { if (!e.currentTarget.contains(e.relatedTarget as globalThis.Node | null)) setOver(null) }}
           onDrop={(e) => {
             e.preventDefault(); const where = over ?? 'inside'; setOver(null)
             const id = tree.dragId; tree.setDragId(null)
             if (id !== null && id !== node.id) { if (where === 'inside') setUserOpen(true); tree.drop(id, node, where) }
           }}
           onContextMenu={(e) => { e.preventDefault(); tree.openMenu(node, e.clientX, e.clientY) }}>
        {hasKids
          ? <button type="button" className="cat-tree__toggle" aria-label={expanded ? '접기' : '펼치기'} aria-expanded={expanded} onClick={() => setUserOpen(!expanded)}>{expanded ? '▾' : '▸'}</button>
          : <span className="cat-tree__toggle" aria-hidden>·</span>}
        <Link to={`/?categoryId=${node.id}`} className="cat-tree__link" data-depth={node.depth} onClick={onNameClick}
              onDragStart={(e) => { if (!tree.isAdmin) e.preventDefault() }}>
          <span className="ellipsis">{node.name}</span>
          <span className="cat-tree__count" title={`이 카테고리에 직접 단 글 ${node.postCount}`}>{node.postCount}</span>
        </Link>
        <button type="button" className="cat-tree__more" aria-label={`${node.name} 메뉴`} title="메뉴 (우클릭도 가능)"
                onClick={(e) => { const r = (e.currentTarget as HTMLElement).getBoundingClientRect(); tree.openMenu(node, r.left, r.bottom + 4) }}>⋯</button>
      </div>
      {hasKids && expanded && (
        <ul>{node.children.map((c) => <Node key={c.id} node={c} activeId={activeId} activePathIds={activePathIds} />)}</ul>
      )}
    </li>
  )
}
